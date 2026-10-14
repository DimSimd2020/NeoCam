package com.poco.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.poco.camera.data.Aspect
import com.poco.camera.data.FlashSetting
import com.poco.camera.data.VideoRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "NeoCam"

/**
 * Максимальная выдержка в обычном режиме ФОТО (1/250 с = 4.0 мс),
 * чтобы даже быстрое движение руки вблизи камеры замораживалось без смаза.
 */
private const val MAX_ACTION_SHUTTER_NS = 4_000_000L

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureExecutor = Executors.newCachedThreadPool()
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()
    private val shotIdGen = AtomicLong(1L)

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private val infos = mutableMapOf<String, CameraInfo>()
    private val fastFpsRanges = mutableMapOf<String, Range<Int>>()
    private val motionDeblurKeys = mutableMapOf<String, CaptureRequest.Key<Byte>>()
    private val is1PKeys = mutableMapOf<String, CaptureRequest.Key<Byte>>()

    private var camera: Camera? = null
    private var boundConfig: BindConfig? = null
    private var boundLensId: String? = null
    private var boundMode: CamMode? = null
    private var boundZoomRange: ClosedFloatingPointRange<Float>? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var previewView: PreviewView? = null
    private var targetRotation = Surface.ROTATION_0
    private var pro = ProValues()

    @Volatile
    var currentAspect: Aspect = Aspect.R4_3

    @Volatile
    private var meteredTotalLight: Double = 0.0

    @Volatile
    private var lastAeProbeAt: Long = 0L

    @Volatile
    private var antiBlurAppliedIso: Int = 0

    @Volatile
    private var antiBlurAppliedExp: Long = 0L

    private var mainBack: Lens? = null
    private var ultraWide: Lens? = null
    private var teleLenses: List<Lens> = emptyList()
    private var front: Lens? = null
    var lenses: List<Lens> = emptyList()
        private set

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()
    val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    val objects = MutableStateFlow<List<TrackedObj>>(emptyList())
    val qr = MutableStateFlow<QrHit?>(null)
    val live = MutableStateFlow(LiveExposure())
    val capturing = MutableStateFlow(0)

    @Volatile
    var analysisFlags = VisionAnalyzer.Flags(
        objects = true,
        qr = true,
        mirror = false,
        viewAspect = 0.75f,
    )

    private val analyzer = VisionAnalyzer(
        flags = { analysisFlags },
        onObjects = { objects.value = it },
        onQr = { qr.value = it },
    )

    val currentLens: Lens?
        get() = lenses.firstOrNull { it.id == (boundLensId ?: _state.value.lensId) }

    // ---------------------------------------------------------------- инициализация

    suspend fun init() = bindMutex.withLock {
        if (provider != null) return@withLock
        try {
            val p = ProcessCameraProvider.getInstance(context).await()
            provider = p
            discover(p)
            val start = mainBack ?: front ?: error("камера не найдена")
            _state.update { it.copy(ready = true, lensId = start.id, facing = start.facing) }
            updateRanges()
            engineScope.launch {
                extensions = runCatching { ExtensionsManager.getInstanceAsync(context, p).await() }.getOrNull()
                updateRanges()
            }
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            _state.update { it.copy(error = "Не удалось открыть камеру: ${e.message}") }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun discover(p: ProcessCameraProvider) {
        class Raw(val lens: Lens, val fov: Float, val pixels: Long)
        val camManager = context.getSystemService(CameraManager::class.java)

        val raws = p.availableCameraInfos.mapNotNull { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                val id = c2.cameraId
                fun <T> ch(key: CameraCharacteristics.Key<T>): T? = c2.getCameraCharacteristic(key)
                val focal = ch(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f
                val phys = ch(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val diag = phys?.let { hypot(it.width, it.height) } ?: 7f
                val zoomRange = if (Build.VERSION.SDK_INT >= 30) ch(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
                val caps = ch(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val map = ch(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val normal = map?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() }
                val high = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.area() }
                val highRes = if (high != null && normal != null && high.area() > normal.area() * 3 / 2) high else null
                val minFocus = ch(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

                // Выбираем 60 FPS диапазон для короткой выдержки и мгновенного ZSL без смаза в движении
                val fpsRanges = ch(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                val bestFastFps = fpsRanges.firstOrNull { it.lower == 60 && it.upper == 60 }
                    ?: fpsRanges.firstOrNull { it.lower == 30 && it.upper == 60 }
                    ?: fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }
                    ?: fpsRanges.firstOrNull { it.lower >= 24 && it.upper >= 30 }
                if (bestFastFps != null) fastFpsRanges[id] = bestFastFps

                // Берём только проверенные ключи из availableCaptureRequestKeys
                runCatching {
                    val allKeys = camManager?.getCameraCharacteristics(id)?.availableCaptureRequestKeys.orEmpty()
                    (allKeys.firstOrNull { it.name.contains("motionDeblur.enabled", true) } as? CaptureRequest.Key<Byte>)
                        ?.let { motionDeblurKeys[id] = it }
                    (allKeys.firstOrNull { it.name.contains("IsCameraApp1P", true) } as? CaptureRequest.Key<Byte>)
                        ?.let { is1PKeys[id] = it }
                }

                infos[id] = info
                Raw(
                    Lens(
                        id = id,
                        facing = info.lensFacing,
                        factor = 1f,
                        minZoom = zoomRange?.lower ?: 1f,
                        maxZoom = zoomRange?.upper ?: (ch(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 8f),
                        hasAf = minFocus > 0f,
                        hasFlash = ch(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                        isoRange = ch(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                        exposureRange = ch(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                        minFocusDistance = minFocus,
                        highResSize = highRes,
                    ),
                    fov = focal / diag,
                    pixels = normal?.area() ?: 0L,
                )
            }.onFailure { Log.w(TAG, "skip camera", it) }.getOrNull()
        }

        val backs = raws.filter { it.lens.facing == CameraSelector.LENS_FACING_BACK }
        val main = backs.firstOrNull()
        mainBack = main?.lens
        front = raws.firstOrNull { it.lens.facing == CameraSelector.LENS_FACING_FRONT }?.lens

        if (main != null && main.lens.minZoom >= 0.95f) {
            val others = backs.drop(1).filter { it.pixels >= 4_000_000 }.map { it.lens.copy(factor = it.fov / main.fov) }
            ultraWide = others.filter { it.factor < 0.85f }.minByOrNull { it.factor }
            teleLenses = others
                .filter { it.factor > 1.75f }
                .sortedBy { it.factor }
                .fold(mutableListOf()) { acc, lens ->
                    if (acc.none { abs(it.factor - lens.factor) < 0.4f }) acc.add(lens)
                    acc
                }
        } else {
            ultraWide = null
            teleLenses = emptyList()
        }

        lenses = buildList {
            mainBack?.let(::add)
            ultraWide?.let(::add)
            addAll(teleLenses)
            front?.let(::add)
        }
        Log.i(TAG, "lenses discovered: $lenses")
    }

    private fun baseLens(): Lens? = if (_state.value.facing == CameraSelector.LENS_FACING_FRONT) front else mainBack

    private fun updateRanges() {
        val s = _state.value
        val base = baseLens() ?: return
        val isFront = base.isFront
        val minZ = if (isFront) base.minZoom else ultraWide?.let { it.factor * it.minZoom } ?: base.minZoom
        val maxTeleHw = teleLenses.maxOfOrNull { it.factor * it.maxZoom } ?: 0f
        val hwMax = if (isFront) base.maxZoom else max(base.maxZoom, maxTeleHw)
        val maxZ = when {
            s.mode == CamMode.VIDEO -> hwMax.coerceAtMost(12f)
            isFront -> hwMax.coerceAtMost(4f)
            else -> max(hwMax, 15f).coerceAtMost(30f)
        }

        val stops = if (isFront) {
            buildList {
                if (minZ < 0.94f) add(minZ)
                add(1f)
                if (maxZ >= 1.4f) add(1.4f)
                if (maxZ >= 2f) add(2f)
            }
        } else {
            buildList {
                if (minZ < 0.92f) add(minZ)
                add(1f)
                if (maxZ >= 2f) add(2f)
                val teleFactors = teleLenses.map { (it.factor * 10f).roundToInt() / 10f }
                for (tf in teleFactors) {
                    if (tf <= maxZ && none { abs(it - tf) < 0.4f }) add(tf)
                }
                if (none { abs(it - 5f) < 0.8f } && maxZ >= 5f) add(5f)
            }.sorted()
        }

        val sel = selectorFor(base.id)
        val ext = extensions
        val night = ext != null && runCatching { ext.isExtensionAvailable(sel, ExtensionMode.NIGHT) }.getOrDefault(false)
        val bokeh = ext != null && runCatching { ext.isExtensionAvailable(sel, ExtensionMode.BOKEH) }.getOrDefault(false)
        val info = infos[base.id]
        val caps = info?.let { runCatching { Recorder.getVideoCapabilities(it) }.getOrNull() }
        val qualities = caps?.getSupportedQualities(DynamicRange.SDR).orEmpty().mapNotNull { q ->
            when (q) {
                Quality.UHD -> VideoRes.UHD
                Quality.FHD -> VideoRes.FHD
                Quality.HD -> VideoRes.HD
                else -> null
            }
        }
        val astroAvailable = !isFront && base.manualSensor && (base.exposureRange?.upper ?: 0L) >= 1_000_000_000L
        val stab = caps?.isStabilizationSupported == true
        val fps60 = info?.supportedFrameRateRanges?.any { it.lower >= 60 } == true
        val rawMp = base.highResSize?.let { sz -> (sz.area() / 1_000_000).toInt() }

        _state.update {
            it.copy(
                minZoom = minZ,
                maxZoom = maxZ,
                hwMaxZoom = hwMax,
                zoomStops = stops,
                nightAvailable = night,
                astroAvailable = astroAvailable,
                portraitAvailable = bokeh,
                highResMp = rawMp,
                videoQualities = qualities,
                stabilizationSupported = stab,
                fps60Supported = fps60,
                hasFlash = base.hasFlash,
            )
        }
    }

    // ---------------------------------------------------------------- режимы и объективы

    fun setMode(mode: CamMode) {
        val s = _state.value
        if (s.recording != null || s.mode == mode) return
        if (mode == CamMode.NIGHT && !s.nightAvailable) return
        if (mode == CamMode.ASTRO && !s.astroAvailable) return
        if (mode == CamMode.PORTRAIT && !s.portraitAvailable) return
        _state.update { it.copy(mode = mode) }
        updateRanges()
        setZoom(if (mode == CamMode.NIGHT || mode == CamMode.PORTRAIT || mode == CamMode.ASTRO) 1f else _state.value.zoom)
    }

    fun switchFacing() {
        val s = _state.value
        if (s.recording != null) return
        val toFront = s.facing != CameraSelector.LENS_FACING_FRONT
        val lens = (if (toFront) front else mainBack) ?: return
        _state.update { it.copy(facing = lens.facing, lensId = lens.id, zoom = 1f, extraZoom = 1f) }
        updateRanges()
        if (_state.value.mode == CamMode.ASTRO && !_state.value.astroAvailable ||
            _state.value.mode == CamMode.NIGHT && !_state.value.nightAvailable ||
            _state.value.mode == CamMode.PORTRAIT && !_state.value.portraitAvailable
        ) {
            _state.update { it.copy(mode = CamMode.PHOTO) }
            updateRanges()
        }
    }

    private fun pickLens(z: Float): Lens? {
        val s = _state.value
        if (s.facing == CameraSelector.LENS_FACING_FRONT) return front
        val main = mainBack ?: return null
        if (s.mode == CamMode.NIGHT || s.mode == CamMode.PORTRAIT || s.mode == CamMode.ASTRO) return main
        ultraWide?.let { if (z < 0.999f) return it }
        val tele = teleLenses.lastOrNull { z >= it.factor * 0.98f }
        if (tele != null) return tele
        return main
    }

    fun setZoom(z: Float) {
        val s = _state.value
        if (!s.ready) return
        var zc = z.coerceIn(s.minZoom, s.maxZoom)
        val lens = (if (s.recording != null || capturing.value > 0) currentLens else pickLens(zc)) ?: return
        val range = if (lens.id == boundLensId) boundZoomRange ?: (lens.minZoom..lens.maxZoom) else lens.minZoom..lens.maxZoom
        if (s.recording != null) zc = zc.coerceIn(lens.factor * range.start, lens.factor * range.endInclusive)
        val hw = (zc / lens.factor).coerceIn(range)
        val extra = (zc / (lens.factor * hw)).coerceAtLeast(1f)
        _state.update {
            it.copy(
                zoom = zc,
                extraZoom = extra,
                lensId = lens.id,
            )
        }
        if (lens.id == boundLensId) camera?.cameraControl?.setZoomRatio(hw)
    }

    // ---------------------------------------------------------------- привязка

    suspend fun bind(owner: LifecycleOwner, pv: PreviewView, cfg: BindConfig) = bindMutex.withLock {
        val p = provider ?: return@withLock
        val activeUseCaseBound = (imageCapture?.let { p.isBound(it) } == true) ||
            (videoCapture?.let { p.isBound(it) } == true)
        if (boundConfig == cfg && camera != null && activeUseCaseBound && previewView === pv) {
            return@withLock
        }
        val lens = lenses.firstOrNull { it.id == cfg.lensId } ?: return@withLock
        previewView = pv

        repeat(20) {
            if (capturing.value == 0) return@repeat
            delay(25)
        }

        recording?.stop()
        recording = null
        antiBlurAppliedIso = 0
        antiBlurAppliedExp = 0L

        val base = selectorFor(lens.id)
        val extMode = when (cfg.mode) {
            CamMode.NIGHT -> ExtensionMode.NIGHT
            CamMode.PORTRAIT -> ExtensionMode.BOKEH
            else -> null
        }
        val ext = extensions
        val usingExt = extMode != null && ext != null && runCatching { ext.isExtensionAvailable(base, extMode) }.getOrDefault(false)
        val selector = if (usingExt) ext!!.getExtensionEnabledCameraSelector(base, extMode!!) else base
        val ratio = if (cfg.useWideRatio || cfg.mode == CamMode.VIDEO) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3

        val fastFps = fastFpsRanges[lens.id]
        val deblurKey = motionDeblurKeys[lens.id]
        val is1PKey = is1PKeys[lens.id]

        fun applyFastZslOptions(ext2: Camera2Interop.Extender<*>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && cfg.mode != CamMode.VIDEO && cfg.mode != CamMode.ASTRO) {
                ext2.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, true)
            }
            if (fastFps != null && cfg.mode != CamMode.VIDEO && cfg.mode != CamMode.NIGHT && cfg.mode != CamMode.ASTRO) {
                ext2.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fastFps)
            }
            if (cfg.mode == CamMode.PHOTO) {
                if (deblurKey != null) ext2.setCaptureRequestOption(deblurKey, 1.toByte())
                if (is1PKey != null) ext2.setCaptureRequestOption(is1PKey, 1.toByte())
            }
        }

        data class Variant(val analysis: Boolean, val highRes: Boolean, val fps60: Boolean, val stab: Boolean)
        val wantHighRes = cfg.highRes && lens.highResSize != null && cfg.mode != CamMode.ASTRO
        val first = Variant(analysis = !usingExt && cfg.mode != CamMode.VIDEO && cfg.mode != CamMode.ASTRO, highRes = wantHighRes, fps60 = cfg.fps60, stab = cfg.stabilization)
        val variants = listOf(
            first,
            first.copy(analysis = false),
            first.copy(analysis = false, highRes = false),
            first.copy(analysis = false, highRes = false, fps60 = false),
            Variant(analysis = false, highRes = false, fps60 = false, stab = false),
        ).distinct()

        camera = null
        boundLensId = null
        _state.update { it.copy(bound = false, error = null, aeAfLocked = false, torch = false) }
        var lastError: Throwable? = null
        for (v in variants) {
            val preview = Preview.Builder()
                .setResolutionSelector(simpleSelector(ratio))
                .also { b ->
                    if (!usingExt) {
                        val ext2 = Camera2Interop.Extender(b)
                        ext2.setSessionCaptureCallback(liveCallback)
                        applyFastZslOptions(ext2)
                    }
                }
                .build()
            val useCases = mutableListOf<UseCase>(preview)
            var ic: ImageCapture? = null
            var vc: VideoCapture<Recorder>? = null
            if (cfg.mode == CamMode.VIDEO) {
                val q = when (cfg.videoRes) {
                    VideoRes.UHD -> Quality.UHD
                    VideoRes.FHD -> Quality.FHD
                    VideoRes.HD -> Quality.HD
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
                    .build()
                vc = VideoCapture.Builder(recorder).apply {
                    if (v.stab) setVideoStabilizationEnabled(true)
                    if (v.fps60) setTargetFrameRate(Range(60, 60))
                    setTargetRotation(targetRotation)
                }.build()
                useCases += vc
            } else {
                val captureMode = if (v.highRes || usingExt) {
                    ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                } else {
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }
                ic = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setResolutionSelector(captureSelector(ratio, v.highRes))
                    .setTargetRotation(targetRotation)
                    .also { b ->
                        if (!usingExt) {
                            val ext2 = Camera2Interop.Extender(b)
                            applyFastZslOptions(ext2)
                        }
                    }
                    .build()
                useCases += ic
                if (v.analysis) {
                    val ia = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(analysisSelector(ratio))
                        .build()
                    ia.setAnalyzer(analysisExecutor, analyzer)
                    useCases += ia
                }
            }
            val group = UseCaseGroup.Builder().apply {
                useCases.forEach { addUseCase(it) }
            }.build()
            try {
                p.unbindAll()
                preview.setSurfaceProvider(pv.surfaceProvider)
                camera = p.bindToLifecycle(owner, selector, group)
                imageCapture = ic
                videoCapture = vc
                boundLensId = lens.id
                boundMode = cfg.mode
                boundConfig = cfg
                Log.i(TAG, "bound ${lens.id} ${cfg.mode} $v ext=$usingExt fastFps=$fastFps")
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "bind failed for $v", e)
            }
        }
        val cam = camera ?: run {
            imageCapture = null
            videoCapture = null
            boundConfig = null
            _state.update { it.copy(error = "Камера не запустилась: ${lastError?.message}") }
            return@withLock
        }

        boundZoomRange = cam.cameraInfo.zoomState.value?.let { it.minZoomRatio..it.maxZoomRatio }
        val es = cam.cameraInfo.exposureState
        val evRange = if (es.isExposureCompensationSupported) es.exposureCompensationRange.let { it.lower..it.upper } else 0..0
        _state.update {
            it.copy(
                bound = true,
                evRange = evRange,
                evStep = es.exposureCompensationStep.toFloat(),
                evIndex = it.evIndex.coerceIn(evRange),
            )
        }
        if (_state.value.evIndex != 0) cam.cameraControl.setExposureCompensationIndex(_state.value.evIndex)
        setZoom(_state.value.zoom)
        applyPro()
    }

    fun unbind() {
        recording?.stop()
        recording = null
        provider?.unbindAll()
        camera = null
        boundConfig = null
        boundLensId = null
        imageCapture = null
        videoCapture = null
        _state.update { it.copy(bound = false, recording = null, torch = false) }
    }

    fun shutdown() {
        unbind()
        analyzer.close()
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
        engineScope.cancel()
    }

    private fun selectorFor(id: String) = CameraSelector.Builder()
        .addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()

    private fun simpleSelector(ratio: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .build()

    private fun captureSelector(ratio: Int, highRes: Boolean) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .apply { if (highRes) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
        .build()

    private fun analysisSelector(ratio: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(
            ResolutionStrategy(
                if (ratio == AspectRatio.RATIO_4_3) Size(640, 480) else Size(640, 360),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
            )
        )
        .build()

    // ---------------------------------------------------------------- управление

    fun setTargetRotation(rotation: Int) {
        if (rotation == targetRotation) return
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    fun focusAt(nx: Float, ny: Float, lock: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        val cam = camera ?: return
        val pv = previewView ?: return
        if (pv.width <= 0 || pv.height <= 0) return
        val point = pv.meteringPointFactory.createPoint(
            (nx.coerceIn(0.02f, 0.98f)) * pv.width,
            (ny.coerceIn(0.02f, 0.98f)) * pv.height,
            0.18f,
        )
        val manualFocus = boundMode == CamMode.PRO && pro.focus != null
        val flags = if (manualFocus) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val action = FocusMeteringAction.Builder(point, flags).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(2, TimeUnit.SECONDS)
        }.build()
        _state.update { it.copy(aeAfLocked = lock) }
        val hasAf = currentLens?.hasAf == true
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
            onResult?.invoke(ok || !hasAf)
        }, mainExecutor)
    }

    fun cancelFocusLock() {
        camera?.cameraControl?.cancelFocusAndMetering()
        _state.update { it.copy(aeAfLocked = false) }
    }

    fun setEv(index: Int) {
        val s = _state.value
        val i = index.coerceIn(s.evRange)
        if (i == s.evIndex) return
        _state.update { it.copy(evIndex = i) }
        camera?.cameraControl?.setExposureCompensationIndex(i)
        if (boundMode == CamMode.PHOTO) updateAntiBlurAe(force = true)
    }

    fun setFlash(flash: FlashSetting) {
        val ic = imageCapture ?: return
        if (_state.value.mode == CamMode.ASTRO) {
            ic.flashMode = ImageCapture.FLASH_MODE_OFF
            return
        }
        val front = _state.value.facing == CameraSelector.LENS_FACING_FRONT
        runCatching {
            ic.flashMode = if (front) ImageCapture.FLASH_MODE_OFF else when (flash) {
                FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
                FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
                FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
            }
        }
    }

    fun setTorch(on: Boolean) {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        cam.cameraControl.enableTorch(on)
        _state.update { it.copy(torch = on) }
    }

    fun setPro(values: ProValues) {
        pro = values
        applyPro()
    }

    private fun applyPro() {
        val cam = camera ?: return
        val c2 = runCatching { Camera2CameraControl.from(cam.cameraControl) }.getOrNull() ?: return
        if (boundMode == CamMode.ASTRO) {
            val lens = currentLens ?: return
            if (!lens.manualSensor) return
            val sec = (boundConfig?.astroExposureSec ?: 2).coerceIn(1, 30)
            val exp = (sec * 1_000_000_000L).coerceIn(
                lens.exposureRange?.lower ?: 1L,
                lens.exposureRange?.upper ?: 30_000_000_000L,
            )
            val iso = 1600.coerceIn(lens.isoRange?.lower ?: 100, lens.isoRange?.upper ?: 1600)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
                .setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            if (lens.hasAf) {
                options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            runCatching { c2.setCaptureRequestOptions(options.build()) }
            return
        }
        if (boundMode != CamMode.PRO) {
            if (boundMode == CamMode.PHOTO && meteredTotalLight > 0.0) {
                updateAntiBlurAe(force = true)
            } else {
                runCatching { c2.clearCaptureRequestOptions() }
            }
            return
        }
        val lens = currentLens ?: return
        val b = CaptureRequestOptions.Builder()
        if (lens.manualSensor && (pro.iso != null || pro.shutterNs != null)) {
            val iso = pro.iso ?: live.value.iso.takeIf { it > 0 } ?: 100
            val exp = pro.shutterNs ?: live.value.shutterNs.takeIf { it > 0 } ?: 8_000_000L
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 16_666_666L))
        }
        if (pro.focus != null && lens.hasAf) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, pro.focus!!)
        }
        if (pro.wb != WbPreset.AUTO) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, pro.wb.awb)
        }
        runCatching { c2.setCaptureRequestOptions(b.build()) }
    }

    /**
     * Сверхкороткая выдержка против смаза (1/250 с = 4.0 мс):
     * Берёт точную экспозицию, замеренную штатным AE, и пересчитывает её на выдержку 1/250 с
     * с сохранением непрерывного автофокуса (CONTROL_AF_MODE_CONTINUOUS_PICTURE) и ZSL.
     */
    private fun updateAntiBlurAe(force: Boolean = false) {
        val cam = camera ?: return
        val lens = currentLens ?: return
        if (!lens.manualSensor || boundMode != CamMode.PHOTO || _state.value.torch || meteredTotalLight <= 0.0) return
        val isoRange = lens.isoRange ?: return
        val expRange = lens.exposureRange ?: return
        val c2 = runCatching { Camera2CameraControl.from(cam.cameraControl) }.getOrNull() ?: return

        val evStops = _state.value.evIndex * _state.value.evStep
        val evMul = 2.0.pow(evStops.toDouble())
        val desiredLight = meteredTotalLight * evMul

        val baseIso = isoRange.lower.coerceAtLeast(40)
        if (desiredLight / baseIso <= MAX_ACTION_SHUTTER_NS) {
            if (antiBlurAppliedIso != 0 || force) {
                antiBlurAppliedIso = 0
                antiBlurAppliedExp = 0L
                runCatching { c2.clearCaptureRequestOptions() }
            }
            return
        }

        // Целевая выдержка 1/250 с (4.0 мс); если ISO превышает 3200 в темноте — ослабляем до 1/125 с (8.0 мс)
        val maxCleanIso = (isoRange.upper * 0.45).roundToInt().coerceIn(800, 3600)
        var targetExp = MAX_ACTION_SHUTTER_NS.coerceIn(expRange.lower, expRange.upper)
        var targetIso = (desiredLight / targetExp).roundToInt()
        if (targetIso > maxCleanIso) {
            targetExp = 8_000_000L.coerceIn(expRange.lower, expRange.upper)
            targetIso = (desiredLight / targetExp).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
        } else {
            targetIso = targetIso.coerceIn(isoRange.lower, maxCleanIso)
        }

        val isoDiff = abs(targetIso - antiBlurAppliedIso).toFloat() / antiBlurAppliedIso.coerceAtLeast(1)
        if (!force && antiBlurAppliedIso > 0 && isoDiff < 0.12f && targetExp == antiBlurAppliedExp) return

        antiBlurAppliedIso = targetIso
        antiBlurAppliedExp = targetExp

        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, targetExp)
            .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 16_666_666L)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_ENABLE_ZSL, true)
        }
        runCatching { c2.setCaptureRequestOptions(b.build()) }
    }

    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 120) return
            last = now

            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val focused = afState == null ||
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            val aeConverged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L

            live.value = LiveExposure(
                iso = iso,
                shutterNs = exp,
                focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                afFocused = focused,
            )

            val reqAeMode = request.get(CaptureRequest.CONTROL_AE_MODE)
            if (iso > 0 && exp > 0L) {
                if (reqAeMode != CameraMetadata.CONTROL_AE_MODE_OFF) {
                    if (aeConverged) {
                        meteredTotalLight = iso.toDouble() * exp.toDouble()
                        lastAeProbeAt = now
                        if (boundMode == CamMode.PHOTO && exp > MAX_ACTION_SHUTTER_NS) {
                            updateAntiBlurAe()
                        }
                    }
                } else if (boundMode == CamMode.PHOTO && now - lastAeProbeAt > 1500L && capturing.value == 0) {
                    lastAeProbeAt = now
                    val cam = camera
                    if (cam != null) {
                        antiBlurAppliedIso = 0
                        runCatching { Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- честная мгновенная съёмка с матрицы (ZSL + HDR+ + 60 FPS)

    fun capturePhotoInstant(mirrorFront: Boolean) {
        val ic = imageCapture ?: run {
            events.tryEmit(EngineEvent.Error("Камера ещё инициализируется"))
            return
        }
        val s = _state.value
        if (s.mode == CamMode.ASTRO && capturing.value > 0) return
        val extra = s.extraZoom
        val isSquare = currentAspect == Aspect.R1_1 && s.mode != CamMode.VIDEO
        val isMirror = s.facing == CameraSelector.LENS_FACING_FRONT && mirrorFront
        val shotId = shotIdGen.getAndIncrement()
        val baseName = MediaSaver.newBaseName("IMG_")

        capturing.update { it + 1 }
        mainExecutor.execute {
            try {
                ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val rot = image.imageInfo.rotationDegrees
                        val bytes = try {
                            val buf = image.planes[0].buffer
                            ByteArray(buf.remaining()).also { buf.get(it) }
                        } finally {
                            image.close()
                        }

                        // 1. Декодируем превью из РЕАЛЬНОГО аппаратного JPEG с сенсора (занимает ~10 мс в RAM)
                        val realBmp = MediaSaver.decodeFastPreviewBitmap(bytes, rot, isMirror, extra, isSquare)
                        if (realBmp != null) {
                            events.tryEmit(EngineEvent.InstantCaptured(shotId, realBmp, "$baseName.jpg"))
                        }

                        // 2. Пишем оригинальный аппаратный JPEG напрямую в MediaStore
                        engineScope.launch {
                            try {
                                val uri = MediaSaver.saveJpegBytes(
                                    context = context,
                                    jpegBytes = bytes,
                                    rotationDegrees = rot,
                                    mirrorHoriz = isMirror,
                                    factor = extra,
                                    squareCrop = isSquare,
                                    baseName = baseName,
                                )
                                if (uri != null) {
                                    events.tryEmit(EngineEvent.Saved(shotId, uri, isVideo = false))
                                } else {
                                    events.tryEmit(EngineEvent.Error("Не удалось сохранить фото"))
                                }
                            } finally {
                                capturing.update { (it - 1).coerceAtLeast(0) }
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "capture failed (${exception.imageCaptureError})", exception)
                        capturing.update { (it - 1).coerceAtLeast(0) }
                        events.tryEmit(EngineEvent.Error("Не удалось сделать снимок"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "takePicture invocation error", e)
                capturing.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean) {
        if (recording != null) return
        engineScope.launch(Dispatchers.Main) {
            var vc = videoCapture
            var tries = 0
            while (vc == null && tries < 25) {
                delay(25)
                vc = videoCapture
                tries++
            }
            if (vc == null) {
                events.tryEmit(EngineEvent.Error("Видеокамера ещё не готова"))
                return@launch
            }
            val shotId = shotIdGen.getAndIncrement()
            val baseName = MediaSaver.newBaseName("VID_")
            val tmpVideoFile = File(context.cacheDir, "$baseName.mp4")
            val opts = FileOutputOptions.Builder(tmpVideoFile).build()
            var pending = vc.output.prepareRecording(context, opts)
            if (withAudio) pending = pending.withAudioEnabled()
            _state.update { it.copy(recording = RecState()) }
            recording = pending.start(mainExecutor) { ev ->
                when (ev) {
                    is VideoRecordEvent.Status -> _state.update { s ->
                        s.recording?.let {
                            s.copy(recording = it.copy(durationMs = ev.recordingStats.recordedDurationNanos / 1_000_000))
                        } ?: s
                    }
                    is VideoRecordEvent.Pause -> _state.update { s ->
                        s.recording?.let { s.copy(recording = it.copy(paused = true)) } ?: s
                    }
                    is VideoRecordEvent.Resume -> _state.update { s ->
                        s.recording?.let { s.copy(recording = it.copy(paused = false)) } ?: s
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        _state.update { it.copy(recording = null) }
                        engineScope.launch {
                            try {
                                if (tmpVideoFile.exists() && tmpVideoFile.length() > 0L) {
                                    val savedUri = MediaSaver.insertVideoFile(context, tmpVideoFile, "$baseName.mp4")
                                    if (savedUri != null) {
                                        events.tryEmit(EngineEvent.Saved(shotId, savedUri, isVideo = true))
                                    } else {
                                        events.tryEmit(EngineEvent.Error("Не удалось сохранить видео в галерею"))
                                    }
                                } else if (ev.hasError()) {
                                    Log.e(TAG, "video error ${ev.error}", ev.cause)
                                    events.tryEmit(EngineEvent.Error("Ошибка записи видео (${ev.error})"))
                                }
                            } finally {
                                tmpVideoFile.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    fun togglePause() {
        val r = recording ?: return
        if (_state.value.recording?.paused == true) r.resume() else r.pause()
    }

    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            }
        }, mainExecutor)
    }
}

private fun Size.area(): Long = width.toLong() * height
