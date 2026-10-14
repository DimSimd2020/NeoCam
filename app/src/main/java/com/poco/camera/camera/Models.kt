package com.poco.camera.camera

import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.camera2.CameraMetadata
import android.net.Uri
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import com.poco.camera.data.Aspect
import com.poco.camera.data.DeviceProfile
import com.poco.camera.data.VideoRes

enum class CamMode(val title: String) {
    NIGHT("НОЧЬ"), PORTRAIT("ПОРТРЕТ"), PHOTO("ФОТО"), VIDEO("ВИДЕО"), PRO("PRO");

    val isPhotoLike get() = this != VIDEO
}

/** Физическая камера телефона. factor — во сколько раз «длиннее» основной (UW ≈ 0.6, Tele ≈ 3.0 / 5.0). */
data class Lens(
    val id: String,
    val facing: Int,
    val factor: Float,
    val minZoom: Float,
    val maxZoom: Float,
    val hasAf: Boolean,
    val hasFlash: Boolean,
    val manualSensor: Boolean,
    val zslSupported: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val minFocusDistance: Float,
    val highResSize: Size?,
) {
    val isFront get() = facing == CameraSelector.LENS_FACING_FRONT
}

enum class WbPreset(val label: String, val awb: Int) {
    AUTO("Авто", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    DAYLIGHT("Солнце", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Облачно", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Тень", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    INCANDESCENT("Лампа", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Люмин.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

/** Ручные параметры PRO-режима; null = автомат. focus — в диоптриях (0 = бесконечность). */
data class ProValues(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focus: Float? = null,
    val wb: WbPreset = WbPreset.AUTO,
)

data class LiveExposure(val iso: Int = 0, val shutterNs: Long = 0, val focus: Float = 0f)

data class RecState(val paused: Boolean = false, val durationMs: Long = 0)

data class EngineState(
    val ready: Boolean = false,
    val bound: Boolean = false,
    val mode: CamMode = CamMode.PHOTO,
    val lensId: String? = null,
    val facing: Int = CameraSelector.LENS_FACING_BACK,
    /** Итоговый зум, который видит пользователь (с учётом смены объективов и цифрового добора). */
    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 100f,
    /** До этого значения зум оптический/сенсорный, выше — цифровой «супер-зум». */
    val hwMaxZoom: Float = 10f,
    /** Дополнительное цифровое увеличение поверх аппаратного (масштаб превью и кроп снимка). */
    val extraZoom: Float = 1f,
    val evIndex: Int = 0,
    val evRange: IntRange = 0..0,
    val evStep: Float = 0f,
    val hasFlash: Boolean = false,
    val torch: Boolean = false,
    val nightAvailable: Boolean = false,
    val portraitAvailable: Boolean = false,
    val zslActive: Boolean = false,
    val highResMp: Int? = null,
    val videoQualities: List<VideoRes> = emptyList(),
    val stabilizationSupported: Boolean = false,
    val fps60Supported: Boolean = false,
    val recording: RecState? = null,
    val snapshotAvailable: Boolean = false,
    val aeAfLocked: Boolean = false,
    val macroAutoActive: Boolean = false,
    val error: String? = null,
)

data class BindConfig(
    val lensId: String,
    val mode: CamMode,
    val aspect: Aspect,
    val highRes: Boolean,
    val zeroLag: Boolean,
    val analysis: Boolean,
    val videoRes: VideoRes,
    val fps60: Boolean,
    val stabilization: Boolean,
    val profile: DeviceProfile,
    /** Ожидаемое отношение высоты PreviewView к ширине — чтобы дождаться актуальной разметки. */
    val viewRatio: Float,
)

sealed interface EngineEvent {
    data class InstantCaptured(val shotId: Long, val bitmap: Bitmap, val name: String) : EngineEvent
    data class Saved(val shotId: Long, val uri: Uri, val isVideo: Boolean) : EngineEvent
    data class Error(val message: String) : EngineEvent
}

/** Объект в кадре; rect нормирован к PreviewView (0..1) без учёта цифрового супер-зума. */
data class TrackedObj(val id: Int, val rect: RectF, val label: String?)

data class QrHit(val text: String, val isUrl: Boolean, val at: Long)
