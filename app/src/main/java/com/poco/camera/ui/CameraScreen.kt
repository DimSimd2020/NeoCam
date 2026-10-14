package com.poco.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.poco.camera.camera.AimController
import com.poco.camera.camera.BindConfig
import com.poco.camera.camera.CamMode
import com.poco.camera.camera.CameraEngine
import com.poco.camera.camera.EngineEvent
import com.poco.camera.camera.EngineState
import com.poco.camera.camera.Feedback
import com.poco.camera.camera.ProValues
import com.poco.camera.camera.SensorTracker
import com.poco.camera.camera.VisionAnalyzer
import com.poco.camera.data.Aspect
import com.poco.camera.data.FlashSetting
import com.poco.camera.data.GalleryRepository
import com.poco.camera.data.SettingsStore
import com.poco.camera.data.VideoRes
import com.poco.camera.data.VolumeAction
import com.poco.camera.data.formatDuration
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class VolumeKey { UP, DOWN }

@Composable
fun CameraScreen(
    engine: CameraEngine,
    settingsStore: SettingsStore,
    gallery: GalleryRepository,
    feedback: Feedback,
    sensorTracker: SensorTracker,
    volumeKeys: SharedFlow<VolumeKey>,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val state by engine.state.collectAsState()
    val settings by settingsStore.state.collectAsState()
    val live by engine.live.collectAsState()
    val capturingCount by engine.capturing.collectAsState()
    val qrHit by engine.qr.collectAsState()
    val tilt by sensorTracker.state.collectAsState()
    val latestUri by gallery.latestUri.collectAsState()
    val latestBitmap by gallery.latestBitmap.collectAsState()

    var proValues by remember { mutableStateOf(ProValues()) }
    var showSettings by remember { mutableStateOf(false) }
    var showQuickSettings by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var shutterFlash by remember { mutableStateOf(false) }
    var frontSelfieLight by remember { mutableStateOf(false) }
    var focusTap by remember { mutableStateOf<FocusTapState?>(null) }
    var dismissedQrText by remember { mutableStateOf<String?>(null) }

    var timerSecondsLeft by remember { mutableIntStateOf(0) }
    var timerJob by remember { mutableStateOf<Job?>(null) }
    var flipAnimDeg by remember { mutableFloatStateOf(0f) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    fun performPhotoCapture() {
        val isFrontFlash = state.facing == CameraSelector.LENS_FACING_FRONT &&
            settings.flash != FlashSetting.OFF &&
            state.mode.isPhotoLike

        if (isFrontFlash) {
            scope.launch {
                frontSelfieLight = true
                delay(140)
                feedback.shutter()
                shutterFlash = true
                engine.capturePhotoInstant(mirrorFront = settings.mirrorFront)
                delay(60)
                shutterFlash = false
                frontSelfieLight = false
            }
        } else {
            feedback.shutter()
            shutterFlash = true
            scope.launch {
                delay(50)
                shutterFlash = false
            }
            engine.capturePhotoInstant(mirrorFront = settings.mirrorFront)
        }
    }

    val aimController = remember {
        AimController(
            scope = scope,
            focus = { pt, cb -> engine.focusAt(pt.x, pt.y, lock = false, onResult = cb) },
            shoot = { performPhotoCapture() },
            tick = { sec -> feedback.countdownTick(sec) },
        )
    }
    val aimUi by aimController.ui.collectAsState()

    LaunchedEffect(settings.aimDelay, settings.autoShoot, live.afFocused) {
        aimController.countdownSeconds = settings.aimDelay
        aimController.autoShootEnabled = settings.autoShoot
        aimController.hwAfFocused = live.afFocused
    }

    LaunchedEffect(Unit) {
        engine.init()
    }

    LaunchedEffect(Unit) {
        engine.events.collect { ev ->
            when (ev) {
                is EngineEvent.InstantCaptured -> gallery.onInstantCaptured(ev.shotId, ev.bitmap, ev.name)
                is EngineEvent.Saved -> gallery.onNewSavedUri(ev.shotId, ev.uri, ev.isVideo)
                is EngineEvent.Error -> Toast.makeText(context, ev.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(settings.autoTrack, state.mode, showGallery, showSettings, timerSecondsLeft) {
        if (!settings.autoTrack || !state.mode.isPhotoLike || showGallery || showSettings || timerSecondsLeft > 0) {
            aimController.reset()
            return@LaunchedEffect
        }
        engine.objects.collect { objs ->
            aimController.onFrame(objs)
        }
    }

    LaunchedEffect(qrHit) {
        val hit = qrHit ?: return@LaunchedEffect
        if (hit.text != dismissedQrText) {
            delay(6000)
            if (engine.qr.value?.at == hit.at) {
                engine.qr.value = null
            }
        }
    }

    LaunchedEffect(focusTap) {
        val tap = focusTap ?: return@LaunchedEffect
        if (!tap.locked) {
            delay(3000)
            if (focusTap?.id == tap.id) focusTap = null
        }
    }

    LaunchedEffect(settings.flash, state.bound, state.facing) {
        engine.setFlash(settings.flash)
    }

    LaunchedEffect(state.lensId, state.mode) {
        focusTap = null
        aimController.reset()
        if (state.mode != CamMode.PRO && proValues != ProValues()) {
            proValues = ProValues()
            engine.setPro(proValues)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    sensorTracker.start()
                    gallery.startObserving()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    timerJob?.cancel()
                    timerSecondsLeft = 0
                    sensorTracker.stop()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    gallery.stopObserving()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun triggerPrimaryShutter() {
        if (showGallery || showSettings || showQuickSettings) return
        if (state.mode == CamMode.ASTRO && capturingCount > 0) return
        if (state.mode == CamMode.VIDEO) {
            if (state.recording != null) {
                feedback.videoStop()
                engine.stopRecording()
            } else {
                val hasAudio = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                feedback.videoStart()
                engine.startRecording(withAudio = hasAudio)
            }
            return
        }

        if (timerJob != null) {
            timerJob?.cancel()
            timerJob = null
            timerSecondsLeft = 0
            feedback.tap()
            return
        }

        if (settings.timer > 0) {
            timerJob = scope.launch {
                for (sec in settings.timer downTo 1) {
                    timerSecondsLeft = sec
                    feedback.countdownTick(sec)
                    delay(1000)
                }
                timerSecondsLeft = 0
                timerJob = null
                performPhotoCapture()
            }
        } else {
            performPhotoCapture()
        }
    }

    LaunchedEffect(Unit) {
        volumeKeys.collect { key ->
            if (showGallery || showSettings) return@collect
            when (settings.volume) {
                VolumeAction.SHUTTER -> triggerPrimaryShutter()
                VolumeAction.ZOOM -> {
                    val factor = if (key == VolumeKey.UP) 1.25f else 0.8f
                    feedback.tick()
                    engine.setZoom(state.zoom * factor)
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Pc.Bg),
    ) {
        val containerW = maxWidth.value.coerceAtLeast(1f)
        val containerH = maxHeight.value.coerceAtLeast(1f)
        val fullRatio = (containerH / containerW).coerceAtLeast(1.33f)

        val effectiveAspect = if (state.mode == CamMode.VIDEO) Aspect.R16_9 else settings.aspect
        val targetViewRatio = when (effectiveAspect) {
            Aspect.R1_1 -> 1f
            Aspect.R4_3 -> 4f / 3f
            Aspect.R16_9 -> 16f / 9f
            Aspect.FULL -> fullRatio
        }

        LaunchedEffect(effectiveAspect) {
            engine.currentAspect = effectiveAspect
        }

        val anchorPt = remember(focusTap, aimUi.target) {
            focusTap?.point ?: aimController.anchorPoint
        }

        LaunchedEffect(
            settings.autoTrack,
            settings.qr,
            state.facing,
            targetViewRatio,
            state.mode,
            showGallery,
            anchorPt,
        ) {
            engine.analysisFlags = VisionAnalyzer.Flags(
                objects = !showGallery && settings.autoTrack && state.mode.isPhotoLike,
                qr = !showGallery && settings.qr && state.mode.isPhotoLike,
                mirror = state.facing == CameraSelector.LENS_FACING_FRONT,
                viewAspect = 1f / targetViewRatio,
                anchorPoint = anchorPt,
            )
        }

        // Привязка CameraX только при реальной смене камеры, режима или соотношения сторон (4:3 vs 16:9)
        val lensId = state.lensId
        val useWide = effectiveAspect == Aspect.R16_9 || effectiveAspect == Aspect.FULL
        if (state.ready && lensId != null) {
            val bindCfg = remember(
                lensId,
                state.mode,
                useWide,
                settings.highRes,
                settings.videoRes,
                settings.fps60,
                settings.stabilization,
                settings.astroExposureSec,
            ) {
                BindConfig(
                    lensId = lensId,
                    mode = state.mode,
                    useWideRatio = useWide,
                    highRes = settings.highRes && (state.mode == CamMode.PHOTO || state.mode == CamMode.PRO),
                    videoRes = settings.videoRes,
                    fps60 = settings.fps60,
                    stabilization = settings.stabilization,
                    astroExposureSec = settings.astroExposureSec,
                )
            }
            LaunchedEffect(bindCfg) {
                engine.bind(lifecycleOwner, previewView, bindCfg)
                engine.setFlash(settings.flash)
            }
        }

        val previewHeightDp by animateDpAsState(
            targetValue = (containerW * targetViewRatio).coerceAtMost(containerH).dp,
            animationSpec = tween(220),
            label = "previewHeight",
        )

        val previewAlign = when (effectiveAspect) {
            Aspect.R4_3, Aspect.R1_1 -> Alignment.TopCenter
            else -> Alignment.Center
        }
        val previewTopPad = when (effectiveAspect) {
            Aspect.R1_1 -> 96.dp
            Aspect.R4_3 -> 72.dp
            else -> 0.dp
        }

        // Видоискатель в стиле GCam
        Box(
            modifier = Modifier
                .align(previewAlign)
                .padding(top = previewTopPad)
                .fillMaxWidth()
                .height(previewHeightDp)
                .clip(RoundedCornerShape(if (effectiveAspect == Aspect.FULL) 0.dp else 24.dp))
                .clipToBounds()
                .pointerInput(state.ready) {
                    awaitEachGesture {
                        var zoomActive = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoomChange = event.calculateZoom()
                                val size = event.calculateCentroidSize(useCurrent = false)
                                if (zoomChange != 1f && size > 12f) {
                                    zoomActive = true
                                    engine.setZoom(engine.state.value.zoom * zoomChange)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomActive) {
                            feedback.tick()
                        }
                    }
                }
                .pointerInput(settings.autoTrack, state.mode, state.extraZoom) {
                    detectTapGestures(
                        onTap = { pos ->
                            val nx = (pos.x / size.width).coerceIn(0.03f, 0.97f)
                            val ny = (pos.y / size.height).coerceIn(0.03f, 0.97f)
                            feedback.tap()
                            if (settings.autoTrack && state.mode.isPhotoLike) {
                                val raw = unzoomPoint(nx, ny, state.extraZoom)
                                aimController.select(raw.x, raw.y)
                            }
                            engine.focusAt(nx, ny, lock = false)
                            focusTap = FocusTapState(
                                id = SystemClock.elapsedRealtime(),
                                point = PointF(nx, ny),
                                locked = false,
                            )
                        },
                        onDoubleTap = {
                            feedback.tick()
                            val nextZoom = if (abs(state.zoom - 1f) < 0.15f) {
                                2f.coerceAtMost(state.maxZoom)
                            } else {
                                1f
                            }
                            engine.setZoom(nextZoom)
                        },
                        onLongPress = { pos ->
                            val nx = (pos.x / size.width).coerceIn(0.03f, 0.97f)
                            val ny = (pos.y / size.height).coerceIn(0.03f, 0.97f)
                            feedback.focusLocked()
                            engine.focusAt(nx, ny, lock = true)
                            focusTap = FocusTapState(
                                id = SystemClock.elapsedRealtime(),
                                point = PointF(nx, ny),
                                locked = true,
                            )
                        },
                    )
                }
                .pointerInput(state.evRange) {
                    var accum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { accum = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            val r = engine.state.value.evRange
                            if (r.first < r.last) {
                                change.consume()
                                accum -= dragAmount / 28f
                                val steps = accum.toInt()
                                if (steps != 0) {
                                    accum -= steps
                                    engine.setEv(engine.state.value.evIndex + steps)
                                    focusTap?.let {
                                        focusTap = it.copy(id = SystemClock.elapsedRealtime())
                                    }
                                }
                            }
                        },
                    )
                },
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = state.extraZoom
                        scaleY = state.extraZoom
                    },
            )

            if (settings.grid) {
                GridOverlay()
            }

            if (settings.level) {
                LevelOverlay(
                    tilt = tilt,
                    onLevelReached = { feedback.tick() },
                )
            }

            if (settings.autoTrack && state.mode.isPhotoLike) {
                AimOverlay(
                    aim = aimUi,
                    extraZoom = state.extraZoom,
                )
            }

            FocusRingOverlay(
                focusTap = focusTap,
                evIndex = state.evIndex,
                evRange = state.evRange,
            )

            AnimatedVisibility(
                visible = shutterFlash,
                enter = fadeIn(tween(20)),
                exit = fadeOut(tween(70)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                )
            }
        }

        AnimatedVisibility(
            visible = frontSelfieLight,
            enter = fadeIn(tween(40)),
            exit = fadeOut(tween(120)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFFFF8E7)),
            )
        }

        AnimatedVisibility(
            visible = timerSecondsLeft > 0,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .glass(CircleShape, strong = true)
                    .border(2.dp, Pc.Accent, CircleShape),
            ) {
                Text(
                    text = timerSecondsLeft.toString(),
                    color = Pc.Accent,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Верхняя панель
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            val rec = state.recording
            if (rec != null) {
                RecordingTimerPill(
                    durationMs = rec.durationMs,
                    paused = rec.paused,
                )
            } else {
                TopControlBar(
                    state = state,
                    settings = settings,
                    uiRotation = tilt.uiRotation,
                    onCycleFlash = {
                        feedback.tap()
                        val next = when (settings.flash) {
                            FlashSetting.OFF -> FlashSetting.AUTO
                            FlashSetting.AUTO -> FlashSetting.ON
                            FlashSetting.ON -> FlashSetting.OFF
                        }
                        settingsStore.update { it.copy(flash = next) }
                    },
                    onToggleTorch = {
                        feedback.tap()
                        engine.setTorch(!state.torch)
                    },
                    onTogglePeaking = {
                        feedback.tap()
                        settingsStore.update { it.copy(autoTrack = !it.autoTrack) }
                    },
                    onCycleAspect = {
                        feedback.tap()
                        val list = Aspect.entries
                        val next = list[(list.indexOf(settings.aspect) + 1) % list.size]
                        settingsStore.update { it.copy(aspect = next) }
                    },
                    onCycleVideoRes = {
                        feedback.tap()
                        val avail = state.videoQualities.ifEmpty { VideoRes.entries }
                        val idx = avail.indexOf(settings.videoRes).coerceAtLeast(0)
                        val next = avail[(idx + 1) % avail.size]
                        settingsStore.update { it.copy(videoRes = next) }
                    },
                    onToggleFps60 = {
                        feedback.tap()
                        settingsStore.update { it.copy(fps60 = !it.fps60) }
                    },
                    onCycleTimer = {
                        feedback.tap()
                        val timers = listOf(0, 3, 10)
                        val next = timers[(timers.indexOf(settings.timer) + 1) % timers.size]
                        settingsStore.update { it.copy(timer = next) }
                    },
                    onOpenSettings = {
                        feedback.tap()
                        showSettings = true
                    },
                )
            }

            Spacer(Modifier.height(6.dp))

            ExposureLockBadge(
                aeAfLocked = state.aeAfLocked,
                evIndex = state.evIndex,
                evStep = state.evStep,
                onUnlock = {
                    feedback.tap()
                    engine.cancelFocusLock()
                    focusTap = null
                },
                onResetEv = {
                    feedback.tap()
                    engine.setEv(0)
                },
            )

            val visibleQr = qrHit?.takeIf { it.text != dismissedQrText }
            QrBanner(
                hit = visibleQr,
                onDismiss = {
                    dismissedQrText = visibleQr?.text
                    engine.qr.value = null
                },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // Нижняя панель GCam
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            AnimatedVisibility(
                visible = state.mode == CamMode.PRO && state.recording == null,
                enter = fadeIn() + scaleIn(initialScale = 0.95f),
                exit = fadeOut() + scaleOut(targetScale = 0.95f),
            ) {
                ProPanel(
                    pro = proValues,
                    live = live,
                    lens = engine.currentLens,
                    evIndex = state.evIndex,
                    evRange = state.evRange,
                    evStep = state.evStep,
                    onProChange = { next ->
                        feedback.tick()
                        proValues = next
                        engine.setPro(next)
                    },
                    onEvChange = { ev ->
                        engine.setEv(ev)
                    },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            if (state.mode != CamMode.PORTRAIT && state.mode != CamMode.NIGHT && state.mode != CamMode.ASTRO) {
                ZoomBar(
                    zoom = state.zoom,
                    minZoom = state.minZoom,
                    maxZoom = state.maxZoom,
                    stops = state.zoomStops,
                    onZoomChange = { z -> engine.setZoom(z) },
                    onStepTick = { feedback.tick() },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            AnimatedVisibility(visible = state.recording == null) {
                ModeSelectorRow(
                    state = state,
                    onSelectMode = { m ->
                        feedback.tick()
                        engine.setMode(m)
                    },
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }

            BottomShutterRow(
                state = state,
                latestUri = latestUri,
                latestBitmap = latestBitmap,
                timerSecondsLeft = timerSecondsLeft,
                capturingCount = capturingCount,
                uiRotation = tilt.uiRotation,
                flipAnimDeg = flipAnimDeg,
                onOpenQuickSettings = { showQuickSettings = true },
                onOpenGallery = {
                    feedback.tap()
                    showGallery = true
                },
                onTogglePause = {
                    feedback.tap()
                    engine.togglePause()
                },
                onShutterClick = { triggerPrimaryShutter() },
                onFlipCamera = {
                    feedback.tap()
                    flipAnimDeg += 180f
                    engine.switchFacing()
                },
            )
        }

        // Полноэкранная галерея поверх камеры (0 мс открытие и закрытие)
        AnimatedVisibility(
            visible = showGallery,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(100)),
        ) {
            GalleryScreen(
                gallery = gallery,
                onClose = { showGallery = false },
            )
        }
    }

    if (showQuickSettings) {
        QuickSettingsSheet(
            settings = settings,
            state = state,
            maxAstroSeconds = ((engine.currentLens?.exposureRange?.upper ?: 0L) / 1_000_000_000L).toInt().coerceAtMost(30),
            onUpdate = { block -> settingsStore.update(block) },
            onDismiss = { showQuickSettings = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = settings,
            state = state,
            onUpdate = { block ->
                feedback.tap()
                settingsStore.update(block)
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun RecordingTimerPill(
    durationMs: Long,
    paused: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .glass(CircleShape, strong = true)
            .border(1.dp, Pc.Record.copy(alpha = 0.65f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (paused) Pc.Accent else Pc.Record),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(durationMs),
            color = Pc.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ModeSelectorRow(
    state: EngineState,
    onSelectMode: (CamMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableModes = remember(state.nightAvailable, state.portraitAvailable, state.astroAvailable) {
        CamMode.entries.filter { m ->
            when (m) {
                CamMode.ASTRO -> state.astroAvailable
                CamMode.NIGHT -> state.nightAvailable
                CamMode.PORTRAIT -> state.portraitAvailable
                else -> true
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.mode, availableModes) {
        val index = availableModes.indexOf(state.mode)
        if (index >= 0) listState.animateScrollToItem(index)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sidePadding = (maxWidth / 2 - 43.dp).coerceAtLeast(0.dp)
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth().glass(CircleShape, strong = true),
        ) {
            itemsIndexed(availableModes) { _, mode ->
            val selected = state.mode == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) Pc.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelectMode(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode.title,
                    color = if (selected) Pc.Accent else Pc.TextDim,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
    }
}

@Composable
private fun BottomShutterRow(
    state: EngineState,
    latestUri: android.net.Uri?,
    latestBitmap: Bitmap?,
    timerSecondsLeft: Int,
    capturingCount: Int,
    uiRotation: Float,
    flipAnimDeg: Float,
    onOpenGallery: () -> Unit,
    onTogglePause: () -> Unit,
    onShutterClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onOpenQuickSettings: () -> Unit,
) {
    val rot by animateFloatAsState(targetValue = uiRotation, animationSpec = tween(220), label = "bottomRot")
    val flipRot by animateFloatAsState(targetValue = flipAnimDeg + uiRotation, animationSpec = tween(320), label = "flipRot")
    val rec = state.recording
    val isVideo = state.mode == CamMode.VIDEO

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        if (rec != null) {
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .size(54.dp)
                    .glass(CircleShape, strong = true)
                    .rotate(rot),
            ) {
                Icon(
                    imageVector = if (rec.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = if (rec.paused) "Продолжить" else "Пауза",
                    tint = Pc.Text,
                    modifier = Modifier.size(26.dp),
                )
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .glass(CircleShape, strong = true)
                    .border(1.5.dp, Pc.GlassStroke, CircleShape)
                    .clickable(onClick = onOpenGallery)
                    .rotate(rot),
            ) {
                when {
                    latestBitmap != null -> {
                        val imgBmp = remember(latestBitmap) { latestBitmap.asImageBitmap() }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = "Галерея",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                .clip(CircleShape),
                        )
                    }
                    latestUri != null -> {
                        AsyncImage(
                            model = latestUri,
                            contentDescription = "Галерея",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                .clip(CircleShape),
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = "Галерея",
                            tint = Pc.Text,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }

        // Кнопка затвора GCam
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .border(
                    width = 3.dp,
                    color = if (isVideo) Pc.Record else Pc.Accent,
                    shape = CircleShape,
                )
                .clickable(onClick = onShutterClick)
                .padding(6.dp),
        ) {
            val innerSize by animateDpAsState(
                targetValue = if (rec != null) 32.dp else 62.dp,
                animationSpec = tween(160),
                label = "shutterInnerSize",
            )
            val innerCorner by animateDpAsState(
                targetValue = if (rec != null) 10.dp else 31.dp,
                animationSpec = tween(160),
                label = "shutterInnerCorner",
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(innerSize)
                    .clip(RoundedCornerShape(innerCorner))
                    .background(if (isVideo) Pc.Record else Pc.Text),
            ) {
                AnimatedContent(
                    targetState = Triple(timerSecondsLeft > 0, capturingCount > 0, state.mode),
                    label = "shutterCenterIcon",
                ) { (inTimer, isCapturing, mode) ->
                    when {
                        inTimer -> Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Отмена",
                            tint = Pc.Bg,
                            modifier = Modifier.size(24.dp),
                        )
                        isCapturing && (mode == CamMode.NIGHT || mode == CamMode.ASTRO) -> CircularProgressIndicator(
                            color = Pc.Bg,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp),
                        )
                        mode == CamMode.ASTRO -> Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Астрофото",
                            tint = Pc.Bg,
                            modifier = Modifier.size(22.dp),
                        )
                        mode == CamMode.NIGHT -> Icon(
                            imageVector = Icons.Outlined.Nightlight,
                            contentDescription = null,
                            tint = Pc.Bg,
                            modifier = Modifier.size(22.dp),
                        )
                        else -> Unit
                    }
                }
            }
        }

        if (rec != null) {
            Spacer(Modifier.size(54.dp))
        } else {
            IconButton(
                onClick = onFlipCamera,
                modifier = Modifier
                    .size(54.dp)
                    .glass(CircleShape, strong = true)
                    .rotate(flipRot),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = "Сменить камеру",
                    tint = Pc.Text,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        if (rec == null) {
            IconButton(
                onClick = onOpenQuickSettings,
                modifier = Modifier
                    .size(44.dp)
                    .glass(CircleShape, strong = true)
                    .rotate(rot),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Быстрые настройки",
                    tint = Pc.Accent,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
    }
}
