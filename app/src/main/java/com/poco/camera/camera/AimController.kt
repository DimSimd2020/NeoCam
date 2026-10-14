package com.poco.camera.camera

import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class AimPhase { SEARCHING, FOCUSING, READY, COUNTDOWN, SHOT }

data class AimUi(
    val target: TrackedObj? = null,
    val others: List<TrackedObj> = emptyList(),
    val phase: AimPhase = AimPhase.SEARCHING,
    val countdown: Int = 0,
    val userLocked: Boolean = false,
)

/**
 * Трекинг объекта и автофокус в стиле GCam:
 * плавно сглаживает координаты объекта (EMA-фильтр без дёрганий), удерживает фокус на объекте
 * и при включённом автоспуске делает снимок, когда объект в фокусе и стабилен.
 */
class AimController(
    private val scope: CoroutineScope,
    private val focus: (PointF, (Boolean) -> Unit) -> Unit,
    private val shoot: () -> Unit,
    private val tick: (Int) -> Unit,
) {
    private val _ui = MutableStateFlow(AimUi())
    val ui: StateFlow<AimUi> = _ui.asStateFlow()

    var countdownSeconds = 2
    var autoShootEnabled = false

    private var target: TrackedObj? = null
    private var smoothRect: RectF? = null
    private var userLocked = false
    private var lastSeenAt = 0L
    private var lastCenter: PointF? = null
    private var stableSince = 0L
    private var focusedAt: PointF? = null
    private var focusOk = false
    private var focusInFlight = false
    private var focusFails = 0
    private var nextFocusAt = 0L
    private var armed = true
    private var shotCenter: PointF? = null
    private var countdownJob: Job? = null
    private var phase = AimPhase.SEARCHING

    fun reset() {
        cancelCountdown()
        target = null
        smoothRect = null
        userLocked = false
        lastCenter = null
        focusedAt = null
        focusOk = false
        focusFails = 0
        armed = true
        shotCenter = null
        phase = AimPhase.SEARCHING
        _ui.value = AimUi()
    }

    /** Тап по объекту на превью — захватить его в трекинг-фокус. */
    fun select(nx: Float, ny: Float): Boolean {
        val hit = _ui.value.let { listOfNotNull(it.target) + it.others }
            .filter { it.rect.contains(nx, ny) }
            .minByOrNull { it.rect.width() * it.rect.height() } ?: return false
        cancelCountdown()
        target = hit
        smoothRect = RectF(hit.rect)
        userLocked = true
        focusedAt = null
        focusOk = false
        focusFails = 0
        armed = true
        shotCenter = null
        publish(_ui.value.others)
        return true
    }

    fun onFrame(objs: List<TrackedObj>) {
        val now = SystemClock.elapsedRealtime()
        val prev = target
        var t = prev?.let { p ->
            objs.firstOrNull { it.id == p.id && p.id >= 0 }
                ?: objs.maxByOrNull { iou(it.rect, p.rect) }?.takeIf { iou(it.rect, p.rect) > 0.25f }
        }
        if (t == null && (!userLocked || now - lastSeenAt > 1400)) {
            t = pickBest(objs)
            if (t != null) {
                userLocked = false
                if (prev == null || iou(t.rect, prev.rect) < 0.25f) onNewTarget()
            }
        }

        if (t == null) {
            if (now - lastSeenAt > 550) {
                cancelCountdown()
                target = null
                smoothRect = null
                if (phase != AimPhase.SHOT) phase = AimPhase.SEARCHING
            }
            publish(objs)
            return
        }

        // Плавная EMA-интерполяция рамки, чтобы кольцо фокуса двигалось мягко как в GCam
        val sr = smoothRect
        val nextRect = if (sr == null) {
            RectF(t.rect)
        } else {
            val a = 0.35f
            RectF(
                sr.left + a * (t.rect.left - sr.left),
                sr.top + a * (t.rect.top - sr.top),
                sr.right + a * (t.rect.right - sr.right),
                sr.bottom + a * (t.rect.bottom - sr.bottom),
            )
        }
        smoothRect = nextRect
        val smoothedTarget = t.copy(rect = nextRect)
        target = smoothedTarget
        lastSeenAt = now

        val c = PointF(nextRect.centerX(), nextRect.centerY())
        val moved = lastCenter?.let { dist(it, c) } ?: 1f
        lastCenter = c
        if (moved > 0.025f) stableSince = now
        val stable = now - stableSince > 450

        // Перефокусировка без рывков (не чаще раза в 1.1 с и только при заметном смещении цели)
        if (!focusInFlight && now >= nextFocusAt && (focusedAt == null || dist(focusedAt!!, c) > 0.11f)) {
            focusInFlight = true
            focusOk = false
            val at = PointF(c.x, c.y)
            focus(at) { ok ->
                focusInFlight = false
                nextFocusAt = SystemClock.elapsedRealtime() + 1100
                if (ok) {
                    focusOk = true
                    focusFails = 0
                    focusedAt = at
                } else {
                    focusFails++
                    if (focusFails >= 2) {
                        focusOk = true
                        focusedAt = at
                    }
                }
            }
        }

        if (!armed && shotCenter != null && dist(shotCenter!!, c) > 0.15f) armed = true
        if (countdownJob != null && (!autoShootEnabled || moved > 0.07f)) cancelCountdown()

        if (countdownJob == null && phase != AimPhase.SHOT) {
            phase = if (!focusOk) AimPhase.FOCUSING else AimPhase.READY
        }
        if (autoShootEnabled && armed && focusOk && stable && countdownJob == null && phase != AimPhase.SHOT) {
            startCountdown(c)
        }
        publish(objs)
    }

    private fun onNewTarget() {
        cancelCountdown()
        smoothRect = null
        focusedAt = null
        focusOk = false
        focusFails = 0
        armed = true
        shotCenter = null
        stableSince = SystemClock.elapsedRealtime()
    }

    private fun startCountdown(center: PointF) {
        countdownJob = scope.launch {
            for (n in countdownSeconds downTo 1) {
                phase = AimPhase.COUNTDOWN
                _ui.value = _ui.value.copy(phase = AimPhase.COUNTDOWN, countdown = n)
                tick(n)
                delay(1000)
            }
            shoot()
            armed = false
            shotCenter = lastCenter ?: center
            phase = AimPhase.SHOT
            _ui.value = _ui.value.copy(phase = AimPhase.SHOT, countdown = 0)
            countdownJob = null
            delay(1000)
            phase = if (target == null) AimPhase.SEARCHING else AimPhase.READY
            _ui.value = _ui.value.copy(phase = phase)
        }
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        if (phase == AimPhase.COUNTDOWN) phase = AimPhase.READY
    }

    private fun publish(objs: List<TrackedObj>) {
        val t = target
        _ui.value = AimUi(
            target = t,
            others = objs.filter { t == null || (it.id != t.id && iou(it.rect, t.rect) < 0.5f) },
            phase = phase,
            countdown = if (phase == AimPhase.COUNTDOWN) _ui.value.countdown else 0,
            userLocked = userLocked,
        )
    }

    private fun pickBest(objs: List<TrackedObj>): TrackedObj? = objs
        .filter { it.rect.width() in 0.06f..0.85f && it.rect.height() in 0.06f..0.85f }
        .maxByOrNull { o ->
            val area = o.rect.width() * o.rect.height()
            val centerDist = hypot(o.rect.centerX() - 0.5f, o.rect.centerY() - 0.5f)
            area * (1.35f - centerDist)
        }

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val btm = min(a.bottom, b.bottom)
        if (r <= l || btm <= t) return 0f
        val inter = (r - l) * (btm - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}
