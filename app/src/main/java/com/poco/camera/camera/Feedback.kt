package com.poco.camera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Surface
import com.poco.camera.data.CamSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Тактильный отклик и системные звуки затвора/таймера/записи.
 */
class Feedback(
    context: Context,
    private val settings: () -> CamSettings,
) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val sound = runCatching {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
            load(MediaActionSound.FOCUS_COMPLETE)
        }
    }.getOrNull()

    private val tone = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, 65)
    }.getOrNull()

    fun tap() = vibrate(VibrationEffect.EFFECT_CLICK)

    fun tick() = vibrate(VibrationEffect.EFFECT_TICK)

    fun heavy() = vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)

    fun shutter() {
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        if (settings().shutterSound) {
            runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    fun videoStart() {
        vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
        if (settings().shutterSound) {
            runCatching { sound?.play(MediaActionSound.START_VIDEO_RECORDING) }
        }
    }

    fun videoStop() {
        vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        if (settings().shutterSound) {
            runCatching { sound?.play(MediaActionSound.STOP_VIDEO_RECORDING) }
        }
    }

    fun countdownTick(secondsLeft: Int) {
        vibrate(if (secondsLeft <= 1) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK)
        if (settings().shutterSound) {
            val t = if (secondsLeft <= 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
            runCatching { tone?.startTone(t, 90) }
        }
    }

    fun focusLocked() {
        vibrate(VibrationEffect.EFFECT_CLICK)
    }

    private fun vibrate(effectId: Int) {
        if (!settings().haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.vibrate(VibrationEffect.createPredefined(effectId))
        }
    }

    fun release() {
        runCatching { sound?.release() }
        runCatching { tone?.release() }
    }
}

data class TiltState(
    /** Отклонение от ближайшего горизонта в градусах (-45..45). */
    val rollDeg: Float = 0f,
    /** Наклон телефона вперёд/назад в градусах (90 = телефон лежит плашмя). */
    val pitchDeg: Float = 0f,
    /** Поворот для CameraX ImageCapture / VideoCapture. */
    val surfaceRotation: Int = Surface.ROTATION_0,
    /** Поворот иконок UI (0, 90, -90, 180). */
    val uiRotation: Float = 0f,
    /** Угол раскрытия шарнира раскладушки (Galaxy Z Flip 7), если датчик есть (0..180°). */
    val hingeAngleDeg: Float? = null,
) {
    val isLevel: Boolean get() = abs(rollDeg) < 1.0f && abs(pitchDeg) < 75f
    /** Сложен ли телефон под углом 65..140° (режим FlexCam на столе). */
    val isFlexPosture: Boolean get() = hingeAngleDeg != null && hingeAngleDeg in 65f..140f
}

/**
 * Датчик ориентации, электронного уровня (горизонта) и шарнира раскладушки (Galaxy Z Flip 7 FlexCam).
 */
class SensorTracker(
    context: Context,
    private val onRotationChanged: (Int) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val hingeSensor: Sensor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    } else null

    private val _state = MutableStateFlow(TiltState())
    val state: StateFlow<TiltState> = _state.asStateFlow()

    private var smoothX = 0f
    private var smoothY = 9.8f
    private var smoothZ = 0f
    private var initialized = false
    private var currentQuadrant = 0 // 0 = 0°, 1 = 90° (landscape left), 2 = 180°, 3 = 270° (landscape right)

    fun start() {
        gravitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        hingeSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && event.sensor.type == Sensor.TYPE_HINGE_ANGLE) {
            val angle = event.values.firstOrNull() ?: return
            _state.value = _state.value.copy(hingeAngleDeg = angle)
            return
        }

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        if (!initialized) {
            smoothX = x
            smoothY = y
            smoothZ = z
            initialized = true
        } else {
            val a = 0.14f
            smoothX += a * (x - smoothX)
            smoothY += a * (y - smoothY)
            smoothZ += a * (z - smoothZ)
        }

        val xy = hypot(smoothX, smoothY)
        val pitch = Math.toDegrees(atan2(smoothZ.toDouble(), xy.toDouble())).toFloat()

        if (xy > 2.2f) {
            // Угол в плоскости экрана: 0 = портрет, +90 = наклон влево (альбомная), -90 = наклон вправо
            val rawDeg = Math.toDegrees(atan2(smoothX.toDouble(), smoothY.toDouble())).toFloat()
            val normalized = ((rawDeg % 360f) + 360f) % 360f // 0..360

            // Гистерезис 15° при смене квадранта
            val targetCenter = currentQuadrant * 90f
            val diffFromCurrent = shortestAngleDiff(normalized, targetCenter)
            if (abs(diffFromCurrent) > 60f) {
                currentQuadrant = (((normalized + 45f) / 90f).toInt()) and 3
            }

            val nearestCenter = currentQuadrant * 90f
            val roll = shortestAngleDiff(normalized, nearestCenter).coerceIn(-45f, 45f)

            val surfRot = when (currentQuadrant) {
                1 -> Surface.ROTATION_90
                2 -> Surface.ROTATION_180
                3 -> Surface.ROTATION_270
                else -> Surface.ROTATION_0
            }
            val uiRot = when (currentQuadrant) {
                1 -> 90f
                2 -> 180f
                3 -> -90f
                else -> 0f
            }
            onRotationChanged(surfRot)
            _state.value = _state.value.copy(
                rollDeg = roll,
                pitchDeg = pitch,
                surfaceRotation = surfRot,
                uiRotation = uiRot,
            )
        } else {
            _state.value = _state.value.copy(pitchDeg = pitch, rollDeg = 0f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun shortestAngleDiff(angle: Float, target: Float): Float {
        var d = (angle - target) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
