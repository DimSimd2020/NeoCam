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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import com.poco.camera.data.FlashSetting
import com.poco.camera.data.VideoRes
import com.poco.camera.data.VolumeAction
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.glass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Лаконичная верхняя панель в стиле GCam (только иконки и краткие значения).
 */
@Composable
fun TopControlBar(
    state: EngineState,
    settings: CamSettings,
    uiRotation: Float,
    onCycleFlash: () -> Unit,
    onToggleTorch: () -> Unit,
    onTogglePeaking: () -> Unit,
    onCycleAspect: () -> Unit,
    onCycleVideoRes: () -> Unit,
    onToggleFps60: () -> Unit,
    onCycleTimer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rot by animateFloatAsState(targetValue = uiRotation, animationSpec = tween(220), label = "topRot")
    val isVideo = state.mode == CamMode.VIDEO

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .glass(CircleShape, strong = true)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        // Вспышка / Фонарик
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
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = onCycleFlash,
                    modifier = Modifier.size(36.dp).rotate(rot),
                ) {
                    val icon = when (settings.flash) {
                        FlashSetting.OFF -> Icons.Outlined.FlashOff
                        FlashSetting.AUTO -> Icons.Outlined.FlashAuto
                        FlashSetting.ON -> Icons.Outlined.FlashOn
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = "Вспышка",
                        tint = if (settings.flash != FlashSetting.OFF) Pc.Accent else Pc.Text,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (isVideo) {
            TopCompactPill(
                text = settings.videoRes.label,
                active = settings.videoRes == VideoRes.UHD,
                onClick = onCycleVideoRes,
            )
            if (state.fps60Supported) {
                TopCompactPill(
                    text = if (settings.fps60) "60" else "30",
                    active = settings.fps60,
                    onClick = onToggleFps60,
                )
            }
        } else {
            // Таймер
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onCycleTimer)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = "Таймер",
                    tint = if (settings.timer > 0) Pc.Accent else Pc.Text,
                    modifier = Modifier.size(19.dp).rotate(rot),
                )
                if (settings.timer > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = "${settings.timer}с",
                        color = Pc.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Трекинг и подсветка объектов
            IconButton(
                onClick = onTogglePeaking,
                modifier = Modifier.size(36.dp).rotate(rot),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CenterFocusStrong,
                    contentDescription = "Слежение за объектом",
                    tint = if (settings.autoTrack) Pc.Accent else Pc.TextDim,
                    modifier = Modifier.size(19.dp),
                )
            }

            // Формат кадра
            TopCompactPill(
                text = settings.aspect.label,
                active = false,
                onClick = onCycleAspect,
            )
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
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TopCompactPill(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (active) Pc.Accent.copy(alpha = 0.2f) else Color(0x1AFFFFFF))
            .border(1.dp, if (active) Pc.Accent else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = if (active) Pc.Accent else Pc.Text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Круглые кнопки зума в стиле GCam (.5, 1x, 2, 5) с плавным свайп-зумом.
 */
@Composable
fun ZoomBar(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    stops: List<Float>,
    onZoomChange: (Float) -> Unit,
    onStepTick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFineSlider by remember { mutableStateOf(false) }

    val safeStops = if (stops.isEmpty()) listOf(1f, 2f) else stops
    val activeStopIdx = safeStops.indices.minByOrNull { i ->
        val next = safeStops.getOrNull(i + 1)
        val prev = safeStops.getOrNull(i - 1)
        val s = safeStops[i]
        when {
            abs(zoom - s) < 0.07f -> 0f
            next != null && zoom in s..next -> abs(ln(zoom / s))
            prev != null && zoom in prev..s -> abs(ln(zoom / s))
            else -> abs(ln(zoom / s)) + 10f
        }
    } ?: 0

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
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
                    .padding(bottom = 8.dp, start = 28.dp, end = 28.dp)
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

        // GCam пилюля с круглыми кнопками кратности
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            val factor = exp(dragAmount / 220f)
                            val prevInt = dragZoom.toInt()
                            dragZoom = (dragZoom * factor).coerceIn(minZoom, maxZoom)
                            if (dragZoom.toInt() != prevInt) onStepTick()
                            onZoomChange(dragZoom)
                        },
                    )
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            safeStops.forEachIndexed { idx, stop ->
                val isSelected = idx == activeStopIdx
                val displayVal = if (isSelected) zoom else stop
                val label = formatZoomLabel(displayVal, selected = isSelected)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (isSelected) 38.dp else 32.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Pc.SurfaceHi else Color.Transparent)
                        .border(
                            width = if (isSelected) 1.5.dp else 0.dp,
                            color = if (isSelected) Pc.Accent else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable {
                            if (isSelected && abs(zoom - stop) < 0.05f) {
                                showFineSlider = !showFineSlider
                            } else {
                                onStepTick()
                                onZoomChange(stop)
                            }
                        },
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Pc.Accent else Pc.Text,
                        fontSize = if (isSelected) 11.sp else 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private fun formatZoomLabel(z: Float, selected: Boolean): String {
    if (z in 0.48f..0.64f) {
        return if (selected) ".5x" else ".5"
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
                value = pro.iso?.toString() ?: live.iso.takeIf { it > 0 }?.toString() ?: "АВТО",
                manual = pro.iso != null,
                selected = activeTab == ProTab.ISO,
                onClick = { activeTab = if (activeTab == ProTab.ISO) null else ProTab.ISO },
            )
            ProParamPill(
                title = "S",
                value = pro.shutterNs?.let(::formatShutterNs)
                    ?: live.shutterNs.takeIf { it > 0 }?.let(::formatShutterNs)
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
                title = "AF",
                value = when {
                    pro.focus == null -> "АВТО"
                    pro.focus < 0.08f -> "∞"
                    else -> String.format(Locale.US, "%.1f", pro.focus)
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
                        contentDescription = "Сбросить",
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
 * Частые параметры прямо рядом с кнопкой переключения камеры.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSettingsSheet(
    settings: CamSettings,
    state: EngineState,
    maxAstroSeconds: Int,
    onUpdate: ((CamSettings) -> CamSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Pc.Surface,
        contentColor = Pc.Text,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
        ) {
            Text(
                text = "Быстрые настройки",
                style = MaterialTheme.typography.titleLarge,
                color = Pc.Text,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            ToggleSettingRow(
                title = "Звук затвора и записи",
                checked = settings.shutterSound,
                onCheckedChange = { value -> onUpdate { it.copy(shutterSound = value) } },
            )
            ChipSettingRow(
                title = "Вспышка",
                options = FlashSetting.entries,
                selected = settings.flash,
                labelOf = { v -> when (v) {
                    FlashSetting.OFF -> "Выкл"
                    FlashSetting.AUTO -> "Авто"
                    FlashSetting.ON -> "Вкл"
                } },
                onSelect = { value -> onUpdate { it.copy(flash = value) } },
            )
            ChipSettingRow(
                title = "Таймер перед съёмкой",
                options = listOf(0, 3, 10),
                selected = settings.timer,
                labelOf = { if (it == 0) "Выкл" else "${it} с" },
                onSelect = { value -> onUpdate { it.copy(timer = value) } },
            )
            if (state.mode == CamMode.ASTRO) {
                val choices = listOf(1, 2, 4, 8, 15, 30).filter { it <= maxAstroSeconds }
                if (choices.isNotEmpty()) {
                    ChipSettingRow(
                        title = "Выдержка астрофото",
                        options = choices,
                        selected = settings.astroExposureSec.coerceAtMost(maxAstroSeconds),
                        labelOf = { "${it} с" },
                        onSelect = { value -> onUpdate { it.copy(astroExposureSec = value) } },
                    )
                }
                Text(
                    text = "Нужен штатив. Один длинный кадр, без стекинга GCam.",
                    color = Pc.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/**
 * Чистая шторка настроек в стиле GCam без лишнего шума.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: CamSettings,
    state: EngineState,
    onUpdate: ((CamSettings) -> CamSettings) -> Unit,
    onDismiss: () -> Unit,
    onCheckUpdates: () -> Unit,
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
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.titleLarge,
                color = Pc.Text,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            OutlinedButton(onClick = onCheckUpdates) {
                Text("Проверить обновления · v" + com.poco.camera.BuildConfig.VERSION_NAME)
            }

            ChipSettingRow(
                title = "Формат кадра",
                options = Aspect.entries,
                selected = settings.aspect,
                labelOf = { it.label },
                onSelect = { a -> onUpdate { it.copy(aspect = a) } },
            )
            ChipSettingRow(
                title = "Таймер",
                options = listOf(0, 3, 10),
                selected = settings.timer,
                labelOf = { if (it == 0) "Выкл" else "${it} с" },
                onSelect = { t -> onUpdate { it.copy(timer = t) } },
            )
            state.highResMp?.let { mp ->
                ToggleSettingRow(
                    title = "Полное разрешение (${mp} МП)",
                    checked = settings.highRes,
                    onCheckedChange = { v -> onUpdate { it.copy(highRes = v) } },
                )
            }
            ToggleSettingRow(
                title = "Следящий автофокус по объекту",
                checked = settings.autoTrack,
                onCheckedChange = { v -> onUpdate { it.copy(autoTrack = v) } },
            )
            ToggleSettingRow(
                title = "Автоспуск при наведении на объект",
                checked = settings.autoShoot,
                onCheckedChange = { v -> onUpdate { it.copy(autoShoot = v) } },
            )

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            val qualities = state.videoQualities.ifEmpty { VideoRes.entries }
            ChipSettingRow(
                title = "Качество видео",
                options = qualities,
                selected = settings.videoRes,
                labelOf = { it.label },
                onSelect = { q -> onUpdate { it.copy(videoRes = q) } },
            )
            if (state.fps60Supported) {
                ToggleSettingRow(
                    title = "60 кадров/с",
                    checked = settings.fps60,
                    onCheckedChange = { v -> onUpdate { it.copy(fps60 = v) } },
                )
            }
            if (state.stabilizationSupported) {
                ToggleSettingRow(
                    title = "Стабилизация видео",
                    checked = settings.stabilization,
                    onCheckedChange = { v -> onUpdate { it.copy(stabilization = v) } },
                )
            }

            HorizontalDivider(color = Pc.GlassStroke, modifier = Modifier.padding(vertical = 10.dp))

            ToggleSettingRow(
                title = "Сетка 3×3",
                checked = settings.grid,
                onCheckedChange = { v -> onUpdate { it.copy(grid = v) } },
            )
            ToggleSettingRow(
                title = "Уровень горизонта",
                checked = settings.level,
                onCheckedChange = { v -> onUpdate { it.copy(level = v) } },
            )
            ToggleSettingRow(
                title = "Сканер QR-кодов",
                checked = settings.qr,
                onCheckedChange = { v -> onUpdate { it.copy(qr = v) } },
            )
            ToggleSettingRow(
                title = "Зеркальное селфи",
                checked = settings.mirrorFront,
                onCheckedChange = { v -> onUpdate { it.copy(mirrorFront = v) } },
            )
            ChipSettingRow(
                title = "Кнопки громкости",
                options = VolumeAction.entries,
                selected = settings.volume,
                labelOf = { it.label },
                onSelect = { a -> onUpdate { it.copy(volume = a) } },
            )
            ToggleSettingRow(
                title = "Звуки камеры",
                checked = settings.shutterSound,
                onCheckedChange = { v -> onUpdate { it.copy(shutterSound = v) } },
            )
            ToggleSettingRow(
                title = "Виброотклик",
                checked = settings.haptics,
                onCheckedChange = { v -> onUpdate { it.copy(haptics = v) } },
            )
        }
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 9.dp),
    ) {
        Text(
            text = title,
            color = Pc.Text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
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
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
