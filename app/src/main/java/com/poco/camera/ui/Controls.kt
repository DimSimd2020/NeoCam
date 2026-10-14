package com.poco.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ScreenRotationAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.camera.camera.CamMode
import com.poco.camera.camera.EngineState
import com.poco.camera.camera.Lens
import com.poco.camera.camera.LiveExposure
import com.poco.camera.camera.ProValues
import com.poco.camera.camera.WbPreset
import com.poco.camera.data.Aspect
import com.poco.camera.data.CamSettings
import com.poco.camera.data.DeviceProfile
import com.poco.camera.data.FlashSetting
import com.poco.camera.data.VideoRes
import com.poco.camera.data.VolumeAction
import com.poco.camera.ui.theme.NeoCamLogo
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Верхняя стеклянная панель быстрых переключателей.
 */
@Composable
fun TopControlBar(
    state: EngineState,
    settings: CamSettings,
    flexActive: Boolean,
    uiRotation: Float,
    onCycleFlash: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleAim: () -> Unit,
    onToggleFlex: () -> Unit,
    onCycleAspect: () -> Unit,
    onToggleHighRes: () -> Unit,
    onCycleVideoRes: () -> Unit,
    onToggleFps60: () -> Unit,
    onCycleTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot by animateFloatAsState(targetValue = uiRotation, animationSpec = tween(250), label = "topRot")
    val isVideo = state.mode == CamMode.VIDEO

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .glass(CircleShape, strong = true)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Вспышка и фонарик
        if (state.hasFlash) {
            if (isVideo || state.mode == CamMode.NIGHT) {
                IconButton(
                    onClick = onToggleTorch,
                    modifier = Modifier.size(36.dp).rotate(rot),
                ) {
                    Icon(
                        imageVector = if (state.torch) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashOff,
                        contentDescription = "Фонарик",
                        tint = if (state.torch) Pc.Accent else Pc.Text,
                        modifier = Modifier.size(19.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onCycleFlash,
                        modifier = Modifier.size(36.dp).rotate(rot),
                    ) {
                        val icon = when (settings.flash) {
                            FlashSetting.OFF -> Icons.Outlined.FlashOff
                            FlashSetting.AUTO -> Icons.Outlined.FlashAuto
                            FlashSetting.ON -> Icons.Outlined.FlashOn
                        }
                        val active = settings.flash != FlashSetting.OFF
                        Icon(
                            imageVector = icon,
                            contentDescription = "Вспышка",
                            tint = if (active) Pc.Accent else Pc.Text,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    IconButton(
                        onClick = onToggleTorch,
                        modifier = Modifier.size(32.dp).rotate(rot),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FlashlightOn,
                            contentDescription = "Фонарик",
                            tint = if (state.torch) Pc.Accent else Pc.TextDim,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }
        }

        // Для Samsung Galaxy Z Flip 7 — быстрая кнопка FlexCam (режим сложенного экрана 90°)
        if (settings.profile.supportsFlexCam) {
            TopPillButton(
                text = "FLEX",
                active = flexActive,
                onClick = onToggleFlex,
                leading = {
                    Icon(
                        imageVector = Icons.Outlined.ScreenRotationAlt,
                        contentDescription = null,
                        tint = if (flexActive) Pc.OnAccent else Pc.Text,
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }

        // Кнопка «Аимбот» для фото-режимов
        if (state.mode.isPhotoLike) {
            TopPillButton(
                text = "АИМ",
                active = settings.aim,
                onClick = onToggleAim,
                leading = {
                    Icon(
                        imageVector = Icons.Outlined.CenterFocusStrong,
                        contentDescription = null,
                        tint = if (settings.aim) Pc.OnAccent else Pc.Text,
                        modifier = Modifier.size(14.dp),
                    )
                },
            )
        }

        // Разрешение видео + 60fps ИЛИ формат кадра + 200MP/50MP
        if (isVideo) {
            TopPillButton(
                text = settings.videoRes.label,
                active = settings.videoRes == VideoRes.UHD,
                onClick = onCycleVideoRes,
            )
            if (state.fps60Supported) {
                TopPillButton(
                    text = if (settings.fps60) "60 FPS" else "30 FPS",
                    active = settings.fps60,
                    onClick = onToggleFps60,
                )
            }
        } else {
            TopPillButton(
                text = settings.aspect.label,
                active = false,
                onClick = onCycleAspect,
            )
            val mp = state.highResMp
            if (mp != null && (state.mode == CamMode.PHOTO || state.mode == CamMode.PRO)) {
                TopPillButton(
                    text = if (settings.highRes) "${mp}MP" else "12MP",
                    active = settings.highRes,
                    onClick = onToggleHighRes,
                )
            }
            // Таймер
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (settings.timer > 0) Pc.Accent.copy(alpha = 0.2f) else Color.Transparent)
                    .clickable(onClick = onCycleTimer)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = "Таймер",
                    tint = if (settings.timer > 0) Pc.Accent else Pc.Text,
                    modifier = Modifier.size(17.dp).rotate(rot),
                )
                if (settings.timer > 0) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "${settings.timer}с",
                        color = Pc.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Настройки
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(36.dp).rotate(rot),
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Настройки",
                tint = Pc.Text,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun TopPillButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .let {
                if (active) {
                    it.background(Pc.AccentBrush).border(1.dp, Pc.Accent, CircleShape)
                } else {
                    it.background(Color(0x220055FF)).border(0.8.dp, Pc.GlassStroke, CircleShape)
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = if (active) Pc.Text else Pc.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Панель зума: пресеты под S25 Ultra (0.6 / 1 / 2 / 3 / 5 / 10 / 30 / 100x), Z Flip 7 и Poco X8 Pro + плавный скраббинг.
 */
@Composable
fun ZoomBar(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    hwMaxZoom: Float,
    profile: DeviceProfile,
    lenses: List<Lens>,
    onZoomChange: (Float) -> Unit,
    onStepTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFineSlider by remember { mutableStateOf(false) }

    val stops = remember(minZoom, maxZoom, profile, lenses) {
        val raw = when (profile) {
            DeviceProfile.PIXEL_8 -> listOf(minZoom.coerceAtMost(0.6f), 1f, 2f, 5f, 8f, 15f, 30f)
            DeviceProfile.S25_ULTRA -> listOf(0.6f, 1f, 2f, 3f, 5f, 10f, 30f, 100f)
            DeviceProfile.Z_FLIP_7 -> listOf(0.6f, 1f, 2f, 4f, 10f, 30f)
            DeviceProfile.POCO_X8_PRO -> listOf(0.6f, 1f, 2f, 5f, 10f, 30f)
        }
        raw.filter { s ->
            (s >= minZoom - 0.05f || (s < 1f && minZoom < 0.95f)) && s <= maxZoom + 0.1f
        }.map { it.coerceIn(minZoom, maxZoom) }.distinctBy { (it * 10f).roundToInt() }
    }

    val activeStopIdx = stops.indices.minByOrNull { i ->
        val next = stops.getOrNull(i + 1)
        val prev = stops.getOrNull(i - 1)
        val s = stops[i]
        when {
            abs(zoom - s) < 0.08f -> 0f
            next != null && zoom in s..next -> abs(ln(zoom / s))
            prev != null && zoom in prev..s -> abs(ln(zoom / s))
            else -> abs(ln(zoom / s)) + 10f
        }
    } ?: 0

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Индикатор Super Res Zoom / Space Zoom / Супер-зума
        AnimatedVisibility(
            visible = zoom > hwMaxZoom + 0.08f || (profile == DeviceProfile.PIXEL_8 && zoom >= 2f),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val label = when {
                profile == DeviceProfile.PIXEL_8 && zoom >= 8f -> String.format(Locale.US, "SUPER RES ZOOM %.1fx", zoom)
                profile == DeviceProfile.PIXEL_8 && zoom >= 2f -> String.format(Locale.US, "SUPER RES %.1fx", zoom)
                profile == DeviceProfile.S25_ULTRA && zoom >= 30f -> String.format(Locale.US, "SPACE ZOOM %.0fx", zoom)
                else -> String.format(Locale.US, "СУПЕР-ЗУМ %.1fx", zoom)
            }
            Text(
                text = label,
                color = Pc.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 5.dp)
                    .glass(CircleShape, strong = true)
                    .border(1.dp, Pc.Accent.copy(alpha = 0.65f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        // Тонкая логарифмическая шкала зума
        AnimatedVisibility(
            visible = showFineSlider,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val minLn = ln(minZoom.coerceAtLeast(0.1f))
            val maxLn = ln(maxZoom.coerceAtLeast(minZoom + 0.1f))
            val frac = ((ln(zoom.coerceIn(minZoom, maxZoom)) - minLn) / (maxLn - minLn)).coerceIn(0f, 1f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 6.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth()
                    .glass(CircleShape, strong = true)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            ) {
                Text(formatZoomLabel(minZoom, selected = false), color = Pc.TextDim, fontSize = 11.sp)
                Slider(
                    value = frac,
                    onValueChange = { f ->
                        val newZ = exp(minLn + f * (maxLn - minLn)).coerceIn(minZoom, maxZoom)
                        onZoomChange(newZ)
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Pc.Accent,
                        activeTrackColor = Pc.Accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(formatZoomLabel(maxZoom, selected = false), color = Pc.TextDim, fontSize = 11.sp)
            }
        }

        // Кнопки пресетов зума
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .glass(CircleShape, strong = true)
                .pointerInput(minZoom, maxZoom) {
                    var dragZoom = zoom
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragZoom = zoom
                            showFineSlider = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val factor = exp(dragAmount / 210f)
                            val prevInt = dragZoom.toInt()
                            dragZoom = (dragZoom * factor).coerceIn(minZoom, maxZoom)
                            if (dragZoom.toInt() != prevInt) onStepTick()
                            onZoomChange(dragZoom)
                        },
                    )
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            stops.forEachIndexed { idx, stop ->
                val isSelected = idx == activeStopIdx
                val displayVal = if (isSelected) zoom else stop
                val label = formatZoomLabel(displayVal, selected = isSelected)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(31.dp)
                        .clip(CircleShape)
                        .let {
                            if (isSelected) {
                                it.background(Pc.AccentBrush).border(1.dp, Pc.Accent, CircleShape)
                            } else {
                                it
                            }
                        }
                        .clickable {
                            if (isSelected && abs(zoom - stop) < 0.05f) {
                                showFineSlider = !showFineSlider
                            } else {
                                onStepTick()
                                onZoomChange(stop)
                            }
                        }
                        .padding(horizontal = if (isSelected) 11.dp else 8.dp),
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Pc.Text else Pc.TextDim,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun formatZoomLabel(z: Float, selected: Boolean): String {
    if (z in 0.48f..0.57f) {
        return if (selected) "0.5x" else "0.5"
    }
    if (z >= 10f) {
        val r = z.roundToInt()
        return if (selected) "${r}x" else "$r"
    }
    val rounded10 = (z * 10f).roundToInt()
    val isInt = rounded10 % 10 == 0
    return when {
        isInt && !selected -> "${rounded10 / 10}"
        isInt && selected -> "${rounded10 / 10}x"
        selected -> String.format(Locale.US, "%.1fx", rounded10 / 10f)
        else -> String.format(Locale.US, "%.1f", rounded10 / 10f)
    }
}

private enum class ProTab { ISO, SHUTTER, EV, FOCUS, WB }

private val ISO_PRESETS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400)
private val SHUTTER_PRESETS: List<Pair<String, Long>> = listOf(
    "1/4000" to 250_000L,
    "1/2000" to 500_000L,
    "1/1000" to 1_000_000L,
    "1/500" to 2_000_000L,
    "1/250" to 4_000_000L,
    "1/125" to 8_000_000L,
    "1/60" to 16_666_667L,
    "1/30" to 33_333_333L,
    "1/15" to 66_666_667L,
    "1/8" to 125_000_000L,
    "1/4" to 250_000_000L,
    "0.5с" to 500_000_000L,
    "1с" to 1_000_000_000L,
    "2с" to 2_000_000_000L,
    "4с" to 4_000_000_000L,
    "8с" to 8_000_000_000L,
)

/**
 * Панель ручных настроек PRO-режима (Expert RAW / Pro).
 */
@Composable
fun ProPanel(
    pro: ProValues,
    live: LiveExposure,
    lens: Lens?,
    evIndex: Int,
    evRange: IntRange,
    evStep: Float,
    onProChange: (ProValues) -> Unit,
    onEvChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf<ProTab?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        AnimatedVisibility(
            visible = activeTab != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val tab = activeTab
            if (tab != null) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(18.dp), strong = true)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    when (tab) {
                        ProTab.ISO -> {
                            val range = lens?.isoRange
                            val options = ISO_PRESETS.filter { range == null || it in range }
                                .ifEmpty { ISO_PRESETS }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                ProChoiceChip(
                                    label = "АВТО",
                                    selected = pro.iso == null,
                                    onClick = { onProChange(pro.copy(iso = null)) },
                                )
                                options.forEach { v ->
                                    ProChoiceChip(
                                        label = v.toString(),
                                        selected = pro.iso == v,
                                        onClick = { onProChange(pro.copy(iso = v)) },
                                    )
                                }
                            }
                        }

                        ProTab.SHUTTER -> {
                            val range = lens?.exposureRange
                            val options = SHUTTER_PRESETS.filter { range == null || it.second in range }
                                .ifEmpty { SHUTTER_PRESETS }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                ProChoiceChip(
                                    label = "АВТО",
                                    selected = pro.shutterNs == null,
                                    onClick = { onProChange(pro.copy(shutterNs = null)) },
                                )
                                options.forEach { (label, ns) ->
                                    ProChoiceChip(
                                        label = label,
                                        selected = pro.shutterNs == ns,
                                        onClick = { onProChange(pro.copy(shutterNs = ns)) },
                                    )
                                }
                            }
                        }

                        ProTab.EV -> {
                            if (evRange.first < evRange.last) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    ProChoiceChip(
                                        label = "0.0",
                                        selected = evIndex == 0,
                                        onClick = { onEvChange(0) },
                                    )
                                    Slider(
                                        value = evIndex.toFloat(),
                                        onValueChange = { onEvChange(it.roundToInt()) },
                                        valueRange = evRange.first.toFloat()..evRange.last.toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Pc.Accent,
                                            activeTrackColor = Pc.Accent,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 10.dp),
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%+.1f EV", evIndex * evStep),
                                        color = Pc.Accent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                Text("Экспокоррекция недоступна", color = Pc.TextDim, fontSize = 12.sp)
                            }
                        }

                        ProTab.FOCUS -> {
                            val maxFocus = (lens?.minFocusDistance ?: 10f).coerceAtLeast(1f)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ProChoiceChip(
                                    label = "AF",
                                    selected = pro.focus == null,
                                    onClick = { onProChange(pro.copy(focus = null)) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("∞", color = Pc.TextDim, fontSize = 13.sp)
                                Slider(
                                    value = (pro.focus ?: live.focus).coerceIn(0f, maxFocus),
                                    onValueChange = { onProChange(pro.copy(focus = it)) },
                                    valueRange = 0f..maxFocus,
                                    colors = SliderDefaults.colors(
                                        thumbColor = Pc.Accent,
                                        activeTrackColor = Pc.Accent,
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                )
                                Text("Макро", color = Pc.TextDim, fontSize = 11.sp)
                            }
                        }

                        ProTab.WB -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            ) {
                                WbPreset.entries.forEach { wb ->
                                    ProChoiceChip(
                                        label = wb.label,
                                        selected = pro.wb == wb,
                                        onClick = { onProChange(pro.copy(wb = wb)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Основная полоса параметров PRO
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp), strong = true)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            ProParamPill(
                title = "ISO",
                value = pro.iso?.toString() ?: live.iso.takeIf { it > 0 }?.let { "A $it" } ?: "АВТО",
                manual = pro.iso != null,
                selected = activeTab == ProTab.ISO,
                onClick = { activeTab = if (activeTab == ProTab.ISO) null else ProTab.ISO },
            )
            ProParamPill(
                title = "S",
                value = pro.shutterNs?.let(::formatShutterNs)
                    ?: live.shutterNs.takeIf { it > 0 }?.let { "A ${formatShutterNs(it)}" }
                    ?: "АВТО",
                manual = pro.shutterNs != null,
                selected = activeTab == ProTab.SHUTTER,
                onClick = { activeTab = if (activeTab == ProTab.SHUTTER) null else ProTab.SHUTTER },
            )
            ProParamPill(
                title = "EV",
                value = String.format(Locale.US, "%+.1f", evIndex * evStep),
                manual = evIndex != 0,
                selected = activeTab == ProTab.EV,
                onClick = { activeTab = if (activeTab == ProTab.EV) null else ProTab.EV },
            )
            ProParamPill(
                title = "FOCUS",
                value = when {
                    pro.focus == null -> "AF"
                    pro.focus < 0.08f -> "MF ∞"
                    else -> String.format(Locale.US, "MF %.1f", pro.focus)
                },
                manual = pro.focus != null,
                selected = activeTab == ProTab.FOCUS,
                onClick = { activeTab = if (activeTab == ProTab.FOCUS) null else ProTab.FOCUS },
            )
            ProParamPill(
                title = "WB",
                value = pro.wb.label,
                manual = pro.wb != WbPreset.AUTO,
                selected = activeTab == ProTab.WB,
                onClick = { activeTab = if (activeTab == ProTab.WB) null else ProTab.WB },
            )
            if (pro != ProValues() || evIndex != 0) {
                IconButton(
                    onClick = {
                        onProChange(ProValues())
                        onEvChange(0)
                        activeTab = null
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Сбросить PRO",
                        tint = Pc.Accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProParamPill(
    title: String,
    value: String,
    manual: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x26FFFFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            color = if (selected || manual) Pc.Accent else Pc.TextDim,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            color = if (manual) Pc.Accent else Pc.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ProChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Pc.Accent else Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Pc.OnAccent else Pc.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

fun formatShutterNs(ns: Long): String {
    if (ns <= 0L) return "АВТО"
    val sec = ns / 1_000_000_000.0
    return if (sec >= 0.4) {
        if (sec >= 1.0 && abs(sec - sec.roundToInt()) < 0.05) "${sec.roundToInt()}с"
        else String.format(Locale.US, "%.1fс", sec)
    } else {
        val denom = (1.0 / sec).roundToInt().coerceAtLeast(1)
        "1/$denom"
    }
}

/**
 * Нижняя шторка полных настроек камеры (Samsung S25 Ultra / Z Flip 7 / Poco X8 Pro).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: CamSettings,
    state: EngineState,
    onUpdate: ((CamSettings) -> CamSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Pc.Surface,
        contentColor = Pc.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NeoCamLogo(size = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "NeoCam",
                            style = MaterialTheme.typography.titleLarge,
                            color = Pc.Text,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "by DimSimd",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Pc.Accent,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Pc.Accent.copy(alpha = 0.14f))
                                .border(1.dp, Pc.Accent.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = Pc.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${settings.profile.title} • ${if (state.zslActive) "Zero-Lag 0 мс" else "Ultra Engine"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Pc.TextDim,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- Секция: Профиль флагмана ----
            SectionHeader("ПРОФИЛЬ УСТРОЙСТВА")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                DeviceProfile.entries.forEach { prof ->
                    val isSel = settings.profile == prof
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSel) Pc.Accent else Pc.SurfaceHi)
                            .clickable { onUpdate { it.copy(profile = prof) } }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    ) {
                        Text(
                            text = prof.shortBadge,
                            color = if (isSel) Pc.OnAccent else Pc.Text,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            // ---- Секция: Скорость и Мгновенный спуск ----
            SectionHeader("МГНОВЕННАЯ СЪЁМКА БЕЗ ЛАГОВ")
            ToggleSettingRow(
                title = "Мгновенный затвор (Zero-Shutter-Lag)",
                subtitle = "Снимок делается в ту же миллисекунду при нажатии без задержки",
                checked = settings.zeroLag,
                onCheckedChange = { v -> onUpdate { it.copy(zeroLag = v) } },
            )
            ToggleSettingRow(
                title = "Мгновенное превью («Сфоткал — сразу открыл»)",
                subtitle = "Всплывающая карточка кадра из памяти (0 мс до просмотра)",
                checked = settings.instantReview,
                onCheckedChange = { v -> onUpdate { it.copy(instantReview = v) } },
            )
            if (settings.profile.supportsFlexCam) {
                ToggleSettingRow(
                    title = "Режим FlexCam (Galaxy Z Flip 7)",
                    subtitle = "Разделять экран на видоискатель сверху и пульт снизу (авто по датчику сгиба или вручную)",
                    checked = settings.flexModeManual,
                    onCheckedChange = { v -> onUpdate { it.copy(flexModeManual = v) } },
                )
            }

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            // ---- Секция: Фото ----
            SectionHeader("ФОТОСЪЁМКА")
            ChipSettingRow(
                title = "Формат кадра",
                options = Aspect.entries,
                selected = settings.aspect,
                labelOf = { it.label },
                onSelect = { a -> onUpdate { it.copy(aspect = a) } },
            )
            ChipSettingRow(
                title = "Таймер затвора",
                options = listOf(0, 3, 5, 10),
                selected = settings.timer,
                labelOf = { if (it == 0) "Выкл" else "${it} с" },
                onSelect = { t -> onUpdate { it.copy(timer = t) } },
            )
            state.highResMp?.let { mp ->
                ToggleSettingRow(
                    title = "Ультра-разрешение сенсора (${mp} МП)",
                    subtitle = "Максимальная детализация матрицы",
                    checked = settings.highRes,
                    onCheckedChange = { v -> onUpdate { it.copy(highRes = v) } },
                )
            }
            ToggleSettingRow(
                title = "Зеркальное селфи",
                subtitle = "Сохранять фронтальные фото как в превью",
                checked = settings.mirrorFront,
                onCheckedChange = { v -> onUpdate { it.copy(mirrorFront = v) } },
            )

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            // ---- Секция: ИИ и Аимбот ----
            SectionHeader("УМНЫЙ АИМБОТ И ИИ")
            ToggleSettingRow(
                title = "Аимбот (автозахват и автоспуск)",
                subtitle = "Держит цель в фокусе и сам делает кадр при стабилизации",
                checked = settings.aim,
                onCheckedChange = { v -> onUpdate { it.copy(aim = v) } },
            )
            if (settings.aim) {
                ChipSettingRow(
                    title = "Отсчёт автоспуска аимбота",
                    options = listOf(1, 2, 3, 5),
                    selected = settings.aimDelay,
                    labelOf = { "${it} с" },
                    onSelect = { d -> onUpdate { it.copy(aimDelay = d) } },
                )
            }
            ToggleSettingRow(
                title = "Автораспознавание QR-кодов",
                subtitle = "Мгновенно сканировать ссылки и текст в кадре",
                checked = settings.qr,
                onCheckedChange = { v -> onUpdate { it.copy(qr = v) } },
            )

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            // ---- Секция: Видео ----
            SectionHeader("ВИДЕОСЪЁМКА")
            val qualities = state.videoQualities.ifEmpty { VideoRes.entries }
            ChipSettingRow(
                title = "Разрешение видео",
                options = qualities,
                selected = settings.videoRes,
                labelOf = { it.label },
                onSelect = { q -> onUpdate { it.copy(videoRes = q) } },
            )
            if (state.fps60Supported) {
                ToggleSettingRow(
                    title = "Плавная съёмка 60 FPS",
                    subtitle = "Повышенная частота кадров для динамичных сцен",
                    checked = settings.fps60,
                    onCheckedChange = { v -> onUpdate { it.copy(fps60 = v) } },
                )
            }
            if (state.stabilizationSupported) {
                ToggleSettingRow(
                    title = "Супер-стабилизация видео (OIS + VDIS)",
                    subtitle = "Компенсация тряски рук при видеозаписи",
                    checked = settings.stabilization,
                    onCheckedChange = { v -> onUpdate { it.copy(stabilization = v) } },
                )
            }

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            // ---- Секция: Видоискатель и система ----
            SectionHeader("ВИДОИСКАТЕЛЬ И УПРАВЛЕНИЕ")
            ToggleSettingRow(
                title = "Сетка композиции 3×3",
                subtitle = "Линии правила третей на экране",
                checked = settings.grid,
                onCheckedChange = { v -> onUpdate { it.copy(grid = v) } },
            )
            ToggleSettingRow(
                title = "Электронный уровень горизонта",
                subtitle = "Индикатор наклона с тактильной фиксацией 0°",
                checked = settings.level,
                onCheckedChange = { v -> onUpdate { it.copy(level = v) } },
            )
            ChipSettingRow(
                title = "Кнопки громкости",
                options = VolumeAction.entries,
                selected = settings.volume,
                labelOf = { it.label },
                onSelect = { a -> onUpdate { it.copy(volume = a) } },
            )
            ToggleSettingRow(
                title = "Звук затвора и таймера",
                subtitle = "Звуковое подтверждение снимка и отсчёта",
                checked = settings.shutterSound,
                onCheckedChange = { v -> onUpdate { it.copy(shutterSound = v) } },
            )
            ToggleSettingRow(
                title = "Тактильный виброотклик",
                subtitle = "Чёткая вибрация при переключении режимов и зума",
                checked = settings.haptics,
                onCheckedChange = { v -> onUpdate { it.copy(haptics = v) } },
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "NeoCam v2.0 • Author: DimSimd",
                color = Pc.TextDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Pc.Accent,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Pc.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = Pc.TextDim, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Pc.OnAccent,
                checkedTrackColor = Pc.Accent,
                uncheckedThumbColor = Pc.TextDim,
                uncheckedTrackColor = Pc.SurfaceHi,
            ),
        )
    }
}

@Composable
private fun <T> ChipSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = title,
            color = Pc.Text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val isSel = opt == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSel) Pc.Accent else Pc.SurfaceHi)
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = labelOf(opt),
                        color = if (isSel) Pc.OnAccent else Pc.Text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
