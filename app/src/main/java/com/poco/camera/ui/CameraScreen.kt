package com.poco.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
    var showGallery by remember { mutableStateOf(false) }
    var shutterFlash by remember { mutableStateOf(false) }
    var frontSelfieLight by remember { mutableStateOf(false) }
    var focusTap by remember { mutableStateOf<FocusTapState?>(null) }
    var dismissedQrText by remember { mutableStateOf<String?>(null) }

    // Всплывающее превью последнего мгновенного кадра
    var popupBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showInstantPopup by remember { mutableStateOf(false) }
    var popupJob by remember { mutableStateOf<Job?>(null) }

    // Ручной таймер спуска
    var timerSecondsLeft by remember { mutableIntStateOf(0) }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    // Переворот иконки смены камеры
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

    // Функция мгновенного снимка (0 мс задержка — кадр сразу в RAM и параллельно пишется на диск)
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
                delay(65)
                shutterFlash = false
                frontSelfieLight = false
            }
        } else {
            feedback.shutter()
            shutterFlash = true
            scope.launch {
                delay(55)
                shutterFlash = false
            }
            engine.capturePhotoInstant(mirrorFront = settings.mirrorFront)
        }
    }

    // Контроллер «Аимбота»
    val aimController = remember {
        AimController(
            scope = scope,
            focus = { pt, cb -> engine.focusAt(pt.x, pt.y, lock = false, onResult = cb) },
            shoot = { performPhotoCapture() },
            tick = { sec -> feedback.countdownTick(sec) },
        )
    }
    val aimUi by aimController.ui.collectAsState()

    LaunchedEffect(settings.aimDelay) {
        aimController.countdownSeconds = settings.aimDelay
    }

    LaunchedEffect(settings.profile) {
        engine.setProfile(settings.profile)
    }

    // Инициализация движка камеры
    LaunchedEffect(Unit) {
        engine.init()
    }

    // Подписка на мгновенные снимки (0 мс) и завершение записи в MediaStore
    LaunchedEffect(Unit) {
        engine.events.collect { ev ->
            when (ev) {
                is EngineEvent.InstantCaptured -> {
                    gallery.onInstantCaptured(ev.shotId, ev.bitmap, ev.name)
                    if (settings.instantReview) {
                        popupBitmap = ev.bitmap
                        showInstantPopup = true
                        popupJob?.cancel()
                        popupJob = scope.launch {
                            delay(2800)
                            showInstantPopup = false
                        }
                    }
                }
                is EngineEvent.Saved -> gallery.onNewSavedUri(ev.shotId, ev.uri, ev.isVideo)
                is EngineEvent.Error -> Toast.makeText(context, ev.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Передача кадров из анализатора в AimController
    LaunchedEffect(settings.aim, state.mode, showGallery, showSettings, timerSecondsLeft) {
        if (!settings.aim || !state.mode.isPhotoLike || showGallery || showSettings || timerSecondsLeft > 0) {
            aimController.reset()
            return@LaunchedEffect
        }
        engine.objects.collect { objs ->
            aimController.onFrame(objs)
        }
    }

    // Автоскрытие QR-баннера через 6 секунд после последнего обнаружения
    var lastQrAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(qrHit) {
        val hit = qrHit ?: return@LaunchedEffect
        if (hit.text != dismissedQrText) {
            lastQrAt = hit.at
            delay(6000)
            if (engine.qr.value?.at == hit.at) {
                engine.qr.value = null
            }
        }
    }

    // Автоскрытие кольца фокуса (если не заблокировано AE/AF)
    LaunchedEffect(focusTap) {
        val tap = focusTap ?: return@LaunchedEffect
        if (!tap.locked) {
            delay(3200)
            if (focusTap?.id == tap.id) focusTap = null
        }
    }

    // Синхронизация режима вспышки с ImageCapture
    LaunchedEffect(settings.flash, state.bound, state.facing) {
        engine.setFlash(settings.flash)
    }

    // Сброс фокуса/аимбота при смене объектива или режима
    LaunchedEffect(state.lensId, state.mode) {
        focusTap = null
        aimController.reset()
        if (state.mode != CamMode.PRO && proValues != ProValues()) {
            proValues = ProValues()
            engine.setPro(proValues)
        }
    }

    // Жизненный цикл датчиков и галереи
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

    // Действие по нажатию главной кнопки затвора / записи
    fun triggerPrimaryShutter() {
        if (showGallery || showSettings) return
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

    // Обработка кнопок громкости
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

    // Активен ли режим Samsung Galaxy Z Flip 7 FlexCam (по датчику сгиба шарнира 65..140° или вручную)
    val flexActive = settings.profile.supportsFlexCam && (settings.flexModeManual || tilt.isFlexPosture)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Pc.Bg),
    ) {
        val containerW = maxWidth.value.coerceAtLeast(1f)
        val containerH = maxHeight.value.coerceAtLeast(1f)
        val fullRatio = (containerH / containerW).coerceAtLeast(1.33f)

        val effectiveAspect = when {
            flexActive -> Aspect.R1_1
            state.mode == CamMode.VIDEO -> Aspect.R16_9
            else -> settings.aspect
        }
        val targetViewRatio = when (effectiveAspect) {
            Aspect.R1_1 -> 1f
            Aspect.R4_3 -> 4f / 3f
            Aspect.R16_9 -> 16f / 9f
            Aspect.FULL -> fullRatio
        }

        // Обновляем флаги анализатора кадров (при открытой галерее ставим анализ на паузу без отвязки камеры!)
        val needAnalysis = state.mode.isPhotoLike && (settings.aim || settings.qr)
        LaunchedEffect(settings.aim, settings.qr, state.facing, targetViewRatio, state.mode, showGallery) {
            engine.analysisFlags = VisionAnalyzer.Flags(
                objects = !showGallery && settings.aim && state.mode.isPhotoLike,
                qr = !showGallery && settings.qr && state.mode.isPhotoLike,
                mirror = state.facing == CameraSelector.LENS_FACING_FRONT,
                viewAspect = 1f / targetViewRatio,
            )
        }

        // Привязка CameraX (держим сессию живой даже во время просмотра галереи — 0 мс возврат!)
        val lensId = state.lensId
        if (state.ready && lensId != null) {
            val bindCfg = remember(
                lensId,
                state.mode,
                effectiveAspect == Aspect.R16_9 || effectiveAspect == Aspect.FULL,
                settings.highRes,
                settings.zeroLag,
                needAnalysis,
                settings.videoRes,
                settings.fps60,
                settings.stabilization,
                settings.profile,
            ) {
                BindConfig(
                    lensId = lensId,
                    mode = state.mode,
                    aspect = effectiveAspect,
                    highRes = settings.highRes && (state.mode == CamMode.PHOTO || state.mode == CamMode.PRO),
                    zeroLag = settings.zeroLag,
                    analysis = needAnalysis,
                    videoRes = settings.videoRes,
                    fps60 = settings.fps60,
                    stabilization = settings.stabilization,
                    profile = settings.profile,
                    viewRatio = targetViewRatio,
                )
            }
            LaunchedEffect(bindCfg) {
                engine.bind(lifecycleOwner, previewView, bindCfg)
                engine.setFlash(settings.flash)
            }
        }

        val maxPreviewHeight = if (flexActive) containerH * 0.48f else containerH
        val previewHeightDp by animateDpAsState(
            targetValue = (containerW * targetViewRatio).coerceAtMost(maxPreviewHeight).dp,
            animationSpec = tween(220),
            label = "previewHeight",
        )

        val previewAlign = when {
            flexActive -> Alignment.TopCenter
            effectiveAspect == Aspect.R4_3 || effectiveAspect == Aspect.R1_1 -> Alignment.TopCenter
            else -> Alignment.Center
        }
        val previewTopPad = when {
            flexActive -> 36.dp
            effectiveAspect == Aspect.R1_1 -> 96.dp
            effectiveAspect == Aspect.R4_3 -> 76.dp
            else -> 0.dp
        }

        // Контейнер видоискателя
        Box(
            modifier = Modifier
                .align(previewAlign)
                .padding(top = previewTopPad)
                .fillMaxWidth()
                .height(previewHeightDp)
                .clip(RoundedCornerShape(if (effectiveAspect == Aspect.FULL && !flexActive) 0.dp else 22.dp))
                .clipToBounds()
                // Мультитач зум щипком
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
                // Тап для фокуса / выбора цели аимбота, двойной тап для 1x/2x, долгий тап для AE/AF БЛОК
                .pointerInput(settings.aim, state.mode, state.extraZoom) {
                    detectTapGestures(
                        onTap = { pos ->
                            val nx = (pos.x / size.width).coerceIn(0.02f, 0.98f)
                            val ny = (pos.y / size.height).coerceIn(0.02f, 0.98f)
                            feedback.tap()
                            if (settings.aim && state.mode.isPhotoLike) {
                                val raw = unzoomPoint(nx, ny, state.extraZoom)
                                if (aimController.select(raw.x, raw.y)) return@detectTapGestures
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
                            val nx = (pos.x / size.width).coerceIn(0.02f, 0.98f)
                            val ny = (pos.y / size.height).coerceIn(0.02f, 0.98f)
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
                // Вертикальный свайп по видоискателю для быстрой регулировки экспозиции (EV)
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

            if (settings.aim && state.mode.isPhotoLike) {
                AimOverlay(
                    aim = aimUi,
                    extraZoom = state.extraZoom,
                )
            }

            FocusRingOverlay(
                focusTap = focusTap,
                evIndex = state.evIndex,
                evRange = state.evRange,
                evStep = state.evStep,
            )

            // Радар-карта 20x–100x Space Zoom (Samsung S25 Ultra)
            if (settings.profile.supportsSpaceZoomMap) {
                SpaceZoomMapOverlay(
                    zoom = state.zoom,
                    extraZoom = state.extraZoom,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp, end = 14.dp),
                )
            }

            // Бейдж Авто-Макро (Focus Enhancer)
            MacroBadge(
                active = state.macroAutoActive,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 14.dp),
            )

            // Мгновенное всплывающее превью («Нажал — сфоткало — сразу посмотрел»)
            InstantShotPopup(
                bitmap = popupBitmap,
                visible = showInstantPopup,
                onOpenNow = {
                    showInstantPopup = false
                    showGallery = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp),
            )

            // Мгновенная вспышка затвора (55 мс)
            AnimatedVisibility(
                visible = shutterFlash,
                enter = fadeIn(tween(20)),
                exit = fadeOut(tween(80)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = 0.65f)),
                )
            }
        }

        // Экранная подсветка при селфи со вспышкой
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

        // Крупный отсчёт ручного таймера в центре
        AnimatedVisibility(
            visible = timerSecondsLeft > 0,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(104.dp)
                    .glass(CircleShape, strong = true)
                    .border(2.dp, Pc.Accent, CircleShape),
            ) {
                Text(
                    text = timerSecondsLeft.toString(),
                    color = Pc.Accent,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Плашка длительной выдержки / ночного снимка (в обычном фото не блокирует экран!)
        AnimatedVisibility(
            visible = capturingCount > 0 && (state.mode == CamMode.NIGHT || (state.mode == CamMode.PRO && (proValues.shutterNs ?: 0L) >= 250_000_000L)),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .glass(RoundedCornerShape(20.dp), strong = true)
                    .border(1.dp, Pc.Accent.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                CircularProgressIndicator(
                    color = Pc.Accent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Ночная склейка кадра…",
                        color = Pc.Text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Держите смартфон неподвижно",
                        color = Pc.TextDim,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        state.error?.let { err ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .glass(RoundedCornerShape(18.dp), strong = true)
                    .padding(16.dp),
            ) {
                Text(text = err, color = Pc.Record, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---------------- Верхняя зона управления ----------------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    flexActive = flexActive,
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
                    onToggleAim = {
                        feedback.tap()
                        settingsStore.update { it.copy(aim = !it.aim) }
                    },
                    onToggleFlex = {
                        feedback.tap()
                        settingsStore.update { it.copy(flexModeManual = !it.flexModeManual) }
                    },
                    onCycleAspect = {
                        feedback.tap()
                        val list = Aspect.entries
                        val next = list[(list.indexOf(settings.aspect) + 1) % list.size]
                        settingsStore.update { it.copy(aspect = next) }
                    },
                    onToggleHighRes = {
                        feedback.tap()
                        settingsStore.update { it.copy(highRes = !it.highRes) }
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
                        val timers = listOf(0, 3, 5, 10)
                        val next = timers[(timers.indexOf(settings.timer) + 1) % timers.size]
                        settingsStore.update { it.copy(timer = next) }
                    },
                    onOpenSettings = {
                        feedback.tap()
                        showSettings = true
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

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

        // ---------------- Нижняя зона: пульт управления (или нижняя половина Z Flip 7 FlexCam) ----------------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (flexActive) Arrangement.SpaceEvenly else Arrangement.Bottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .let { m ->
                    if (flexActive) {
                        m.height((containerH * 0.48f).dp)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(Pc.Surface.copy(alpha = 0.94f))
                            .border(1.dp, Pc.GlassStroke, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .padding(top = 10.dp)
                    } else {
                        m
                    }
                }
                .navigationBarsPadding()
                .padding(bottom = 14.dp),
        ) {
            if (flexActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .glass(CircleShape, strong = true)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ScreenRotationAlt,
                        contentDescription = null,
                        tint = Pc.Accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append("GALAXY Z FLIP 7 • FLEXCAM")
                            tilt.hingeAngleDeg?.let { append(" (${it.toInt()}°)") }
                        },
                        color = Pc.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Панель PRO-режима
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

            // Панель зума
            if (state.mode != CamMode.PORTRAIT && state.mode != CamMode.NIGHT) {
                ZoomBar(
                    zoom = state.zoom,
                    minZoom = state.minZoom,
                    maxZoom = state.maxZoom,
                    hwMaxZoom = state.hwMaxZoom,
                    profile = settings.profile,
                    lenses = engine.lenses,
                    onZoomChange = { z -> engine.setZoom(z) },
                    onStepTick = { feedback.tick() },
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            // Карусель режимов
            AnimatedVisibility(visible = state.recording == null) {
                ModeSelectorRow(
                    state = state,
                    onSelectMode = { m ->
                        feedback.tick()
                        engine.setMode(m)
                    },
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }

            // Главный ряд кнопки затвора
            BottomShutterRow(
                state = state,
                latestUri = latestUri,
                latestBitmap = latestBitmap,
                timerSecondsLeft = timerSecondsLeft,
                capturingCount = capturingCount,
                uiRotation = tilt.uiRotation,
                flipAnimDeg = flipAnimDeg,
                onOpenGallery = {
                    feedback.tap()
                    showInstantPopup = false
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
                onVideoSnapshot = {
                    performPhotoCapture()
                },
            )
        }

        // Полноэкранная галерея поверх камеры (без отвязки CameraX — 0 мс вход и 0 мс возврат!)
        AnimatedVisibility(
            visible = showGallery,
            enter = fadeIn(tween(110)),
            exit = fadeOut(tween(110)),
        ) {
            GalleryScreen(
                gallery = gallery,
                onClose = { showGallery = false },
            )
        }
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
                .background(if (paused) Color(0xFFFFD54F) else Pc.Record),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(durationMs),
            color = Pc.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (paused) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "ПАУЗА",
                color = Color(0xFFFFD54F),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun ModeSelectorRow(
    state: EngineState,
    onSelectMode: (CamMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableModes = remember(state.nightAvailable, state.portraitAvailable) {
        CamMode.entries.filter { m ->
            when (m) {
                CamMode.NIGHT -> state.nightAvailable
                CamMode.PORTRAIT -> state.portraitAvailable
                else -> true
            }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .glass(CircleShape, strong = true)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        availableModes.forEach { mode ->
            val selected = state.mode == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .let {
                        if (selected) {
                            it.background(Pc.AccentBrush).border(1.dp, Pc.Accent, CircleShape)
                        } else {
                            it
                        }
                    }
                    .clickable { onSelectMode(mode) }
                    .padding(horizontal = 13.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode.title,
                    color = if (selected) Pc.Text else Pc.TextDim,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                )
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
    onVideoSnapshot: () -> Unit,
) {
    val rot by animateFloatAsState(targetValue = uiRotation, animationSpec = tween(250), label = "bottomRot")
    val flipRot by animateFloatAsState(targetValue = flipAnimDeg + uiRotation, animationSpec = tween(350), label = "flipRot")
    val rec = state.recording
    val isVideo = state.mode == CamMode.VIDEO

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        // Левая кнопка: Миниатюра галереи (показывает мгновенный Bitmap за 0 мс!) ИЛИ Пауза во время записи
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
                    .border(1.5.dp, if (latestBitmap != null) Pc.Accent else Pc.GlassStroke, CircleShape)
                    .clickable(onClick = onOpenGallery)
                    .rotate(rot),
            ) {
                when {
                    latestBitmap != null -> {
                        val imgBmp = remember(latestBitmap) { latestBitmap.asImageBitmap() }
                        Image(
                            bitmap = imgBmp,
                            contentDescription = "Последний снимок",
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

        // Центральная кнопка Затвора / Записи видео в стиле Чёрный Обсидиан + Синий Неон
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Color(0xFF040812))
                .border(
                    width = 3.5.dp,
                    brush = if (isVideo) {
                        androidx.compose.ui.graphics.Brush.linearGradient(listOf(Pc.Record, Color(0xFFFF6B8B)))
                    } else {
                        Pc.RingBrush
                    },
                    shape = CircleShape,
                )
                .clickable(onClick = onShutterClick)
                .padding(7.dp),
        ) {
            val innerSize by animateDpAsState(
                targetValue = if (rec != null) 34.dp else 64.dp,
                animationSpec = tween(180),
                label = "shutterInnerSize",
            )
            val innerCorner by animateDpAsState(
                targetValue = if (rec != null) 10.dp else 32.dp,
                animationSpec = tween(180),
                label = "shutterInnerCorner",
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(innerSize)
                    .clip(RoundedCornerShape(innerCorner))
                    .let {
                        if (isVideo) {
                            it.background(Pc.Record)
                        } else {
                            it.background(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(Color(0xFF0E2954), Color(0xFF050B16)),
                                )
                            ).border(2.dp, Pc.Accent, RoundedCornerShape(innerCorner))
                        }
                    },
            ) {
                AnimatedContent(
                    targetState = Triple(timerSecondsLeft > 0, capturingCount > 0, state.mode),
                    label = "shutterCenterIcon",
                ) { (inTimer, isCapturing, mode) ->
                    when {
                        inTimer -> Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Отмена таймера",
                            tint = Pc.Accent,
                            modifier = Modifier.size(26.dp),
                        )
                        isCapturing && mode == CamMode.NIGHT -> CircularProgressIndicator(
                            color = Pc.Accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(24.dp),
                        )
                        mode == CamMode.NIGHT -> Icon(
                            imageVector = Icons.Outlined.Nightlight,
                            contentDescription = null,
                            tint = Pc.Accent,
                            modifier = Modifier.size(24.dp),
                        )
                        !isVideo -> Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Pc.AccentBrush),
                        )
                        else -> Unit
                    }
                }
            }
        }

        // Правая кнопка: Переключение камеры (фронт/тыл) ИЛИ Фото-снимок во время записи видео
        if (rec != null) {
            if (state.snapshotAvailable) {
                IconButton(
                    onClick = onVideoSnapshot,
                    modifier = Modifier
                        .size(54.dp)
                        .glass(CircleShape, strong = true)
                        .rotate(rot),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = "Снимок во время видео",
                        tint = Pc.Text,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(54.dp))
            }
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
    }
}
