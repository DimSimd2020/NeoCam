package com.poco.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
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
import com.poco.camera.data.DeviceProfile
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
import kotlinx.coroutines.withContext
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
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "PocoCam"

/**
 * Движок камеры с поддержкой:
 * - Zero-Shutter-Lag (ZSL) и мгновенного захвата кадра превью (0 мс до просмотра)
 * - Мульти-телеобъективов Samsung S25 Ultra (0.6x UW, 1x 200MP, 3x Tele, 5x Periscope, 100x Space Zoom)
 * - Samsung Z Flip 7 FlexCam и Poco X8 Pro
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class)
class CameraEngine(private val context: Context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val captureIoExecutor = Executors.newFixedThreadPool(2)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shotIdGen = AtomicLong(1L)

    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private val infos = mutableMapOf<String, CameraInfo>()

    private var camera: Camera? = null
    private var boundLensId: String? = null
    private var boundMode: CamMode? = null
    private var boundAspect: Aspect = Aspect.R4_3
    private var boundZoomRange: ClosedFloatingPointRange<Float>? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var previewView: PreviewView? = null
    private var targetRotation = Surface.ROTATION_0
    private var pro = ProValues()
    private var activeProfile: DeviceProfile = DeviceProfile.detectDefault()

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
    /** Сколько снимков сейчас в фоновом сохранении (для индикатора ночного режима). */
    val capturing = MutableStateFlow(0)

    @Volatile
    var analysisFlags = VisionAnalyzer.Flags(objects = false, qr = false, mirror = false, viewAspect = 0.75f)
    private val analyzer = VisionAnalyzer({ analysisFlags }, { objects.value = it }, { qr.value = it })

    val currentLens: Lens?
        get() = lenses.firstOrNull { it.id == (boundLensId ?: _state.value.lensId) }

    // ---------------------------------------------------------------- инициализация

    suspend fun init() {
        if (provider != null) return
        try {
            val p = ProcessCameraProvider.getInstance(context).await()
            provider = p
            // Запускаем обнаружение камер мгновенно, а Extensions подгружаем параллельно без блокировки старта
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

    private fun discover(p: ProcessCameraProvider) {
        class Raw(val lens: Lens, val fov: Float, val pixels: Long)

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
                val zsl = runCatching { info.isZslSupported }.getOrDefault(false)
                infos[id] = info
                Raw(
                    Lens(
                        id = id,
                        facing = info.lensFacing,
                        factor = 1f,
                        minZoom = zoomRange?.lower ?: 1f,
                        maxZoom = zoomRange?.upper ?: (ch(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f),
                        hasAf = minFocus > 0f,
                        hasFlash = ch(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
                        zslSupported = zsl,
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
        if (main != null) {
            val others = backs.drop(1).filter { it.pixels >= 4_000_000 }.map { it.lens.copy(factor = it.fov / main.fov) }
            ultraWide = if (main.lens.minZoom < 0.95f) null else others.filter { it.factor < 0.85f }.minByOrNull { it.factor }
            // Поддержка нескольких телеобъективов (например 3x + 5x перископ на Samsung S25 Ultra)
            teleLenses = others
                .filter { it.factor > 1.75f }
                .sortedBy { it.factor }
                .fold(mutableListOf()) { acc, lens ->
                    if (acc.none { abs(it.factor - lens.factor) < 0.4f }) acc.add(lens)
                    acc
                }
        }
        lenses = buildList {
            mainBack?.let(::add)
            ultraWide?.let(::add)
            addAll(teleLenses)
            front?.let(::add)
        }
        Log.i(TAG, "lenses discovered: $lenses")
    }

    fun setProfile(profile: DeviceProfile) {
        if (activeProfile == profile) return
        activeProfile = profile
        updateRanges()
        setZoom(_state.value.zoom)
    }

    private fun baseLens(): Lens? = if (_state.value.facing == CameraSelector.LENS_FACING_FRONT) front else mainBack

    private fun updateRanges() {
        val s = _state.value
        val base = baseLens() ?: return
        val isFront = base.isFront
        val minZ = when {
            isFront -> base.minZoom
            ultraWide != null -> ultraWide!!.let { it.factor * it.minZoom }
            else -> min(base.minZoom, 0.6f.takeIf { base.minZoom < 0.95f } ?: base.minZoom)
        }
        val maxTeleHw = teleLenses.maxOfOrNull { it.factor * it.maxZoom } ?: 0f
        val hwMax = if (isFront) base.maxZoom else max(base.maxZoom, maxTeleHw)
        val maxZ = when {
            s.mode == CamMode.VIDEO -> max(hwMax, if (activeProfile == DeviceProfile.S25_ULTRA) 20f else 10f)
            isFront -> max(hwMax, 4f)
            else -> max(hwMax, activeProfile.maxSuperZoom)
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
        val stab = caps?.isStabilizationSupported == true
        val fps60 = info?.supportedFrameRateRanges?.any { it.lower >= 60 } == true

        val rawMp = base.highResSize?.let { sz -> (sz.area() / 1_000_000).toInt() }
        val profileMp = when {
            isFront -> rawMp
            activeProfile == DeviceProfile.S25_ULTRA -> rawMp?.takeIf { it >= 50 } ?: 200
            else -> rawMp ?: 50
        }

        _state.update {
            it.copy(
                minZoom = minZ, maxZoom = maxZ, hwMaxZoom = hwMax,
                nightAvailable = night, portraitAvailable = bokeh,
                highResMp = profileMp,
                videoQualities = qualities, stabilizationSupported = stab, fps60Supported = fps60,
                hasFlash = base.hasFlash,
            )
        }
    }

    // ---------------------------------------------------------------- режимы и объективы

    fun setMode(mode: CamMode) {
        val s = _state.value
        if (s.recording != null || s.mode == mode) return
        if (mode == CamMode.NIGHT && !s.nightAvailable) return
        if (mode == CamMode.PORTRAIT && !s.portraitAvailable) return
        _state.update { it.copy(mode = mode) }
        updateRanges()
        setZoom(if (mode == CamMode.NIGHT || mode == CamMode.PORTRAIT) 1f else _state.value.zoom)
    }

    fun switchFacing() {
        val s = _state.value
        if (s.recording != null) return
        val toFront = s.facing != CameraSelector.LENS_FACING_FRONT
        val lens = (if (toFront) front else mainBack) ?: return
        _state.update { it.copy(facing = lens.facing, lensId = lens.id, zoom = 1f, extraZoom = 1f) }
        updateRanges()
        if (_state.value.mode == CamMode.NIGHT && !_state.value.nightAvailable ||
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
        if (s.mode == CamMode.NIGHT || s.mode == CamMode.PORTRAIT) return main
        // Если основная логическая камера сама умеет оптический/широкий зум (как на Samsung One UI Camera2),
        // не перепривязываем камеру лишний раз — это даёт 0 мс переключение между 0.6x / 1x / 3x / 5x!
        ultraWide?.let { if (z < 0.999f) return it }
        val tele = teleLenses.lastOrNull { z >= it.factor * 0.98f }
        if (tele != null) return tele
        return main
    }

    fun setZoom(z: Float) {
        val s = _state.value
        if (!s.ready) return
        var zc = z.coerceIn(s.minZoom, s.maxZoom)
        val lens = (if (s.recording != null) currentLens else pickLens(zc)) ?: return
        val range = if (lens.id == boundLensId) boundZoomRange ?: (lens.minZoom..lens.maxZoom) else lens.minZoom..lens.maxZoom
        if (s.recording != null) zc = zc.coerceIn(lens.factor * range.start, lens.factor * (range.endInclusive * 2f))
        val hw = (zc / lens.factor).coerceIn(range)
        val extra = (zc / (lens.factor * hw)).coerceAtLeast(1f)
        val isMacro = !lens.isFront && zc < 0.95f && lens.hasAf
        _state.update {
            it.copy(
                zoom = zc,
                extraZoom = extra,
                lensId = lens.id,
                macroAutoActive = isMacro,
            )
        }
        if (lens.id == boundLensId) camera?.cameraControl?.setZoomRatio(hw)
    }

    // ---------------------------------------------------------------- привязка

    suspend fun bind(owner: LifecycleOwner, pv: PreviewView, cfg: BindConfig) {
        val p = provider ?: return
        val lens = lenses.firstOrNull { it.id == cfg.lensId } ?: return
        previewView = pv
        activeProfile = cfg.profile
        boundAspect = cfg.aspect
        recording?.stop()
        recording = null

        val base = selectorFor(lens.id)
        val extMode = when (cfg.mode) {
            CamMode.NIGHT -> ExtensionMode.NIGHT
            CamMode.PORTRAIT -> ExtensionMode.BOKEH
            else -> null
        }
        val ext = extensions
        val usingExt = extMode != null && ext != null && runCatching { ext.isExtensionAvailable(base, extMode) }.getOrDefault(false)
        val selector = if (usingExt) ext!!.getExtensionEnabledCameraSelector(base, extMode!!) else base
        val ratio = if ((cfg.aspect == Aspect.R4_3 || cfg.aspect == Aspect.R1_1) && cfg.mode != CamMode.VIDEO) {
            AspectRatio.RATIO_4_3
        } else {
            AspectRatio.RATIO_16_9
        }
        val viewPort = awaitViewPort(pv, cfg.viewRatio)

        data class Variant(val analysis: Boolean, val highRes: Boolean, val zsl: Boolean, val fps60: Boolean, val stab: Boolean, val snapshot: Boolean)
        val wantHighRes = cfg.highRes && lens.highResSize != null
        val wantZsl = cfg.zeroLag && !wantHighRes && !usingExt && lens.zslSupported && cfg.mode == CamMode.PHOTO
        val first = Variant(cfg.analysis && !usingExt, wantHighRes, wantZsl, cfg.fps60, cfg.stabilization, true)
        val variants = listOf(
            first,
            first.copy(zsl = false),
            first.copy(zsl = false, analysis = false),
            first.copy(zsl = false, analysis = false, highRes = false),
            first.copy(zsl = false, analysis = false, highRes = false, fps60 = false),
            Variant(analysis = false, highRes = false, zsl = false, fps60 = false, stab = false, snapshot = false),
        ).distinct()

        camera = null
        boundLensId = null
        _state.update { it.copy(bound = false, error = null, aeAfLocked = false, torch = false, zslActive = false) }
        var lastError: Throwable? = null
        var boundZsl = false
        for (v in variants) {
            val preview = Preview.Builder()
                .setResolutionSelector(simpleSelector(ratio))
                .also { b -> if (!usingExt) Camera2Interop.Extender(b).setSessionCaptureCallback(liveCallback) }
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
                if (v.snapshot) {
                    ic = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setResolutionSelector(simpleSelector(AspectRatio.RATIO_16_9))
                        .setTargetRotation(targetRotation)
                        .build()
                    useCases += ic
                }
            } else {
                val captureMode = when {
                    v.zsl -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    cfg.zeroLag && !v.highRes && !usingExt -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    else -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                }
                ic = ImageCapture.Builder()
                    .setCaptureMode(captureMode)
                    .setResolutionSelector(captureSelector(ratio, v.highRes))
                    .setTargetRotation(targetRotation)
                    .setIoExecutor(captureIoExecutor)
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
                viewPort?.let { setViewPort(it) }
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
                boundZsl = v.zsl || (cfg.zeroLag && !v.highRes && !usingExt)
                Log.i(TAG, "bound ${lens.id} ${cfg.mode} $v ext=$usingExt")
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "bind failed for $v", e)
            }
        }
        val cam = camera ?: run {
            imageCapture = null
            videoCapture = null
            _state.update { it.copy(error = "Камера не запустилась: ${lastError?.message}") }
            return
        }

        boundZoomRange = cam.cameraInfo.zoomState.value?.let { it.minZoomRatio..it.maxZoomRatio }
        val es = cam.cameraInfo.exposureState
        val evRange = if (es.isExposureCompensationSupported) es.exposureCompensationRange.let { it.lower..it.upper } else 0..0
        _state.update {
            it.copy(
                bound = true,
                zslActive = boundZsl,
                evRange = evRange,
                evStep = es.exposureCompensationStep.toFloat(),
                evIndex = it.evIndex.coerceIn(evRange),
                snapshotAvailable = cfg.mode == CamMode.VIDEO && imageCapture != null,
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
        boundLensId = null
        imageCapture = null
        videoCapture = null
        _state.update { it.copy(bound = false, recording = null, torch = false) }
    }

    fun shutdown() {
        unbind()
        analyzer.close()
        analysisExecutor.shutdown()
        captureIoExecutor.shutdown()
        engineScope.cancel()
    }

    private suspend fun awaitViewPort(pv: PreviewView, ratio: Float): ViewPort? {
        repeat(15) {
            if (pv.width > 0 && pv.height > 0 && abs(pv.height.toFloat() / pv.width - ratio) < 0.05f) return pv.viewPort
            delay(8)
        }
        return pv.viewPort
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
                if (ratio == AspectRatio.RATIO_4_3) Size(960, 720) else Size(960, 540),
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

    /** Фокус и экспозиция по точке (координаты PreviewView 0..1). */
    fun focusAt(nx: Float, ny: Float, lock: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        val cam = camera ?: return
        val pv = previewView ?: return
        val point = pv.meteringPointFactory.createPoint(nx * pv.width, ny * pv.height)
        val manualFocus = boundMode == CamMode.PRO && pro.focus != null
        val flags = if (manualFocus) FocusMeteringAction.FLAG_AE else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        val action = FocusMeteringAction.Builder(point, flags).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(4, TimeUnit.SECONDS)
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
    }

    fun setFlash(flash: FlashSetting) {
        val ic = imageCapture ?: return
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
        if (boundMode != CamMode.PRO) {
            runCatching { c2.clearCaptureRequestOptions() }
            return
        }
        val lens = currentLens ?: return
        val b = CaptureRequestOptions.Builder()
        if (lens.manualSensor && (pro.iso != null || pro.shutterNs != null)) {
            val iso = pro.iso ?: live.value.iso.takeIf { it > 0 } ?: 100
            val exp = pro.shutterNs ?: live.value.shutterNs.takeIf { it > 0 } ?: 10_000_000L
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
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

    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        private var last = 0L
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 200) return
            last = now
            live.value = LiveExposure(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
            )
        }
    }

    // ---------------------------------------------------------------- мгновенная съёмка (0 мс пролагов)

    /**
     * Мгновенно захватывает текущий кадр превью в память (0 мс задержка — снимок можно открыть СРАЗУ ЖЕ!),
     * и параллельно в фоновом пуле сохраняет полноразмерный кадр с матрицы в DCIM/PocoCam без блокировки затвора.
     */
    fun capturePhotoInstant(mirrorFront: Boolean) {
        val ic = imageCapture ?: return
        val s = _state.value
        val extra = s.extraZoom
        val isSquare = boundAspect == Aspect.R1_1 && s.mode != CamMode.VIDEO
        val shotId = shotIdGen.getAndIncrement()
        val name = MediaSaver.newName("IMG_")

        // 1. Мгновенный снимок с экрана превью (занимает ~2 мс на UI-потоке)
        val rawPreview = runCatching { previewView?.bitmap }.getOrNull()
        if (rawPreview != null) {
            val previewBmp = cropPreviewIfNeeded(rawPreview, extra)
            events.tryEmit(EngineEvent.InstantCaptured(shotId, previewBmp, "$name.jpg"))
        }

        // 2. Асинхронный захват полного разрешения с сенсора и запись в MediaStore в фоне
        val meta = ImageCapture.Metadata().apply {
            isReversedHorizontal = s.facing == CameraSelector.LENS_FACING_FRONT && mirrorFront
        }
        engineScope.launch {
            capturing.update { it + 1 }
            try {
                val uri = if (extra < 1.02f && !isSquare) {
                    val opts = ImageCapture.OutputFileOptions.Builder(
                        context.contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaSaver.imageValues(name),
                    ).setMetadata(meta).build()
                    ic.saveAsync(opts)?.savedUri
                } else {
                    val tmp = File(context.cacheDir, "$name.jpg")
                    try {
                        ic.saveAsync(ImageCapture.OutputFileOptions.Builder(tmp).setMetadata(meta).build()) ?: return@launch
                        MediaSaver.saveProcessed(context, tmp, extra, isSquare, name)
                    } finally {
                        tmp.delete()
                    }
                }
                if (uri != null) {
                    events.tryEmit(EngineEvent.Saved(shotId, uri, isVideo = false))
                }
            } finally {
                capturing.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun cropPreviewIfNeeded(src: Bitmap, extraZoom: Float): Bitmap {
        if (extraZoom <= 1.02f) return src
        val cw = (src.width / extraZoom).roundToInt().coerceIn(16, src.width)
        val ch = (src.height / extraZoom).roundToInt().coerceIn(16, src.height)
        val left = (src.width - cw) / 2
        val top = (src.height - ch) / 2
        return runCatching { Bitmap.createBitmap(src, left, top, cw, ch) }.getOrDefault(src)
    }

    private suspend fun ImageCapture.saveAsync(opts: ImageCapture.OutputFileOptions): ImageCapture.OutputFileResults? =
        suspendCancellableCoroutine { cont ->
            takePicture(opts, captureIoExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (cont.isActive) cont.resume(output)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    events.tryEmit(EngineEvent.Error("Не удалось сохранить фото"))
                    if (cont.isActive) cont.resume(null)
                }
            })
        }

    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean) {
        val vc = videoCapture ?: return
        if (recording != null) return
        val shotId = shotIdGen.getAndIncrement()
        val opts = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(MediaSaver.videoValues(MediaSaver.newName("VID_")))
            .build()
        var pending = vc.output.prepareRecording(context, opts)
        if (withAudio) pending = pending.withAudioEnabled()
        _state.update { it.copy(recording = RecState()) }
        recording = pending.start(mainExecutor) { ev ->
            when (ev) {
                is VideoRecordEvent.Status -> _state.update { s ->
                    s.recording?.let { s.copy(recording = it.copy(durationMs = ev.recordingStats.recordedDurationNanos / 1_000_000)) } ?: s
                }
                is VideoRecordEvent.Pause -> _state.update { s -> s.recording?.let { s.copy(recording = it.copy(paused = true)) } ?: s }
                is VideoRecordEvent.Resume -> _state.update { s -> s.recording?.let { s.copy(recording = it.copy(paused = false)) } ?: s }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    _state.update { it.copy(recording = null) }
                    val uri = ev.outputResults.outputUri
                    if (uri != Uri.EMPTY) events.tryEmit(EngineEvent.Saved(shotId, uri, true))
                    else if (ev.hasError()) events.tryEmit(EngineEvent.Error("Ошибка записи видео (${ev.error})"))
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
