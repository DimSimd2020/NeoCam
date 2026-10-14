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
import kotlin.math.abs
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
 * Следящий автофокус по объектам в стиле GCam:
 * - Плавно отслеживает главный или выбранный тапом объект.
 * - Не блокирует аппаратный Continuous AF (Dual-Pixel / Laser ToF), а мягко передаёт область замера,
 *   когда объект смещается или меняет масштаб (приближение/удаление телефона).
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
    var hwAfFocused = true

    private var target: TrackedObj? = null
    private var smoothRect: RectF? = null
    private var userLocked = false
    private var userAnchor: PointF? = null
    private var lastSeenAt = 0L
    private var lastCenter: PointF? = null
    private var stableSince = 0L
    private var focusedAt: PointF? = null
    private var focusedScale = 0f
    private var focusOk = true
    private var focusInFlight = false
    private var nextFocusAt = 0L
    private var armed = true
    private var shotCenter: PointF? = null
    private var countdownJob: Job? = null
    private var phase = AimPhase.SEARCHING

    val anchorPoint: PointF?
        get() = target?.rect?.let { PointF(it.centerX(), it.centerY()) } ?: userAnchor

    fun reset() {
        cancelCountdown()
        target = null
        smoothRect = null
        userLocked = false
        userAnchor = null
        lastCenter = null
        focusedAt = null
        focusedScale = 0f
        focusOk = true
        armed = true
        shotCenter = null
        phase = AimPhase.SEARCHING
        _ui.value = AimUi()
    }

    /** Тап по экрану: захватывает объект под пальцем или создаёт точку слежения в месте касания. */
    fun select(nx: Float, ny: Float): Boolean {
        userAnchor = PointF(nx, ny)
        val hit = _ui.value.let { listOfNotNull(it.target) + it.others }
            .filter { it.rect.contains(nx, ny) }
            .minByOrNull { it.rect.width() * it.rect.height() }
            ?: TrackedObj(
                id = -99,
                rect = RectF(
                    (nx - 0.11f).coerceIn(0.02f, 0.86f),
                    (ny - 0.11f).coerceIn(0.02f, 0.86f),
                    (nx + 0.11f).coerceIn(0.14f, 0.98f),
                    (ny + 0.11f).coerceIn(0.14f, 0.98f),
                ),
                label = null,
            )
        cancelCountdown()
        target = hit
        smoothRect = RectF(hit.rect)
        userLocked = true
        focusedAt = null
        focusedScale = 0f
        focusOk = false
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
                ?: objs.maxByOrNull { iou(it.rect, p.rect) }?.takeIf { iou(it.rect, p.rect) > 0.22f }
        }
        if (t == null && (!userLocked || now - lastSeenAt > 1800)) {
            t = pickBest(objs)
            if (t != null) {
                userLocked = false
                userAnchor = null
                if (prev == null || iou(t.rect, prev.rect) < 0.22f) onNewTarget()
            }
        }

        if (t == null) {
            if (userLocked && prev != null && now - lastSeenAt <= 1800) {
                t = prev
            } else {
                if (now - lastSeenAt > 500) {
                    cancelCountdown()
                    target = null
                    smoothRect = null
                    userAnchor = null
                    if (phase != AimPhase.SHOT) phase = AimPhase.SEARCHING
                }
                publish(objs)
                return
            }
        }

        // Плавная фильтрация координат и размера
        val sr = smoothRect
        val nextRect = if (sr == null) {
            RectF(t.rect)
        } else {
            val a = 0.42f
            RectF(
                sr.left + a * (t.rect.left - sr.left),
                sr.top + a * (t.rect.top - sr.top),
                sr.right + a * (t.rect.right - sr.right),
                sr.bottom + a * (t.rect.bottom - sr.bottom),
            )
        }
        smoothRect = nextRect
        val smoothed = t.copy(rect = nextRect)
        target = smoothed
        userAnchor = PointF(nextRect.centerX(), nextRect.centerY())
        lastSeenAt = now

        val c = PointF(nextRect.centerX(), nextRect.centerY())
        val curScale = hypot(nextRect.width(), nextRect.height())
        val moved = lastCenter?.let { dist(it, c) } ?: 1f
        lastCenter = c
        if (moved > 0.022f) stableSince = now
        val stable = now - stableSince > 420

        // Вызываем точечный замер только если пользователь сам выбрал цель тапом (userLocked)
        // или если включён автоспуск — иначе оставляем непрерывный аппаратный Continuous AF (Laser ToF + Dual-Pixel PDAF),
        // чтобы камера мгновенно фокусировалась на руке/предмете при резком приближении без блокировки линзы!
        val isNewTarget = focusedAt == null
        val scaleChanged = focusedScale > 0f && abs(curScale - focusedScale) / focusedScale > 0.16f
        val posChanged = focusedAt != null && dist(focusedAt!!, c) > 0.08f

        if ((userLocked || autoShootEnabled) && !focusInFlight && now >= nextFocusAt && (isNewTarget || scaleChanged || posChanged)) {
            focusInFlight = true
            focusOk = false
            val at = PointF(c.x, c.y)
            focusedScale = curScale
            focusedAt = at
            focus(at) { ok ->
                focusInFlight = false
                nextFocusAt = SystemClock.elapsedRealtime() + 600
                focusOk = ok || hwAfFocused
            }
        } else if (!focusInFlight) {
            focusOk = hwAfFocused
        }

        if (!armed && shotCenter != null && dist(shotCenter!!, c) > 0.14f) armed = true
        if (countdownJob != null && (!autoShootEnabled || moved > 0.07f)) cancelCountdown()

        if (countdownJob == null && phase != AimPhase.SHOT) {
            phase = if (focusOk && hwAfFocused) AimPhase.READY else AimPhase.FOCUSING
        }
        if (autoShootEnabled && armed && focusOk && hwAfFocused && stable && countdownJob == null && phase != AimPhase.SHOT) {
            startCountdown(c)
        }
        publish(objs)
    }

    private fun onNewTarget() {
        cancelCountdown()
        smoothRect = null
        focusedAt = null
        focusedScale = 0f
        focusOk = hwAfFocused
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
            delay(900)
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
            others = objs.filter { t == null || (it.id != t.id && iou(it.rect, t.rect) < 0.45f) },
            phase = phase,
            countdown = if (phase == AimPhase.COUNTDOWN) _ui.value.countdown else 0,
            userLocked = userLocked,
        )
    }

    private fun pickBest(objs: List<TrackedObj>): TrackedObj? = objs
        .filter { it.rect.width() in 0.05f..0.82f && it.rect.height() in 0.05f..0.82f }
        .maxByOrNull { o ->
            val area = o.rect.width() * o.rect.height()
            val centerDist = hypot(o.rect.centerX() - 0.5f, o.rect.centerY() - 0.5f)
            area * (1.4f - centerDist)
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
