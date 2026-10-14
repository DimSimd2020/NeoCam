package com.poco.camera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.camera.camera.AimPhase
import com.poco.camera.camera.AimUi
import com.poco.camera.camera.QrHit
import com.poco.camera.camera.TiltState
import com.poco.camera.ui.theme.Pc
import com.poco.camera.ui.theme.glass
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class FocusTapState(
    val id: Long,
    val point: PointF, // нормированные координаты PreviewView (0..1)
    val locked: Boolean = false,
)

/**
 * Сетка третей 3x3 поверх видоискателя.
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val shadow = Color(0x55000510)
        val line = Color(0x4500E5FF)
        val strokeShadow = 2.dp.toPx()
        val strokeLine = 0.9.dp.toPx()

        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            drawLine(shadow, Offset(x, 0f), Offset(x, h), strokeWidth = strokeShadow)
            drawLine(line, Offset(x, 0f), Offset(x, h), strokeWidth = strokeLine)
            drawLine(shadow, Offset(0f, y), Offset(w, y), strokeWidth = strokeShadow)
            drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = strokeLine)
        }
    }
}

/**
 * Электронный уровень (горизонт) с тактильным откликом при выравнивании в 0°.
 */
@Composable
fun LevelOverlay(
    tilt: TiltState,
    onLevelReached: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var wasLevel by remember { mutableStateOf(true) }
    var hideLevel by remember { mutableStateOf(false) }

    LaunchedEffect(tilt.isLevel) {
        if (tilt.isLevel) {
            if (!wasLevel) onLevelReached()
            wasLevel = true
            delay(1400)
            hideLevel = true
        } else {
            wasLevel = false
            hideLevel = false
        }
    }

    val visible = !hideLevel && abs(tilt.pitchDeg) < 75f
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(250),
        label = "levelAlpha",
    )
    if (alpha <= 0.01f) return

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(160.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfSpan = 48.dp.toPx()
            val gap = 14.dp.toPx()
            val tickLen = 10.dp.toPx()
            val color = if (tilt.isLevel) Pc.Accent.copy(alpha = alpha) else Color.White.copy(alpha = 0.72f * alpha)
            val refColor = Color.White.copy(alpha = 0.38f * alpha)
            val sw = if (tilt.isLevel) 2.4.dp.toPx() else 1.6.dp.toPx()

            // Неподвижные боковые риски
            drawLine(
                color = refColor,
                start = Offset(cx - halfSpan - tickLen, cy),
                end = Offset(cx - halfSpan, cy),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = refColor,
                start = Offset(cx + halfSpan, cy),
                end = Offset(cx + halfSpan + tickLen, cy),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )

            // Вращающаяся линия горизонта
            rotate(degrees = -tilt.rollDeg, pivot = Offset(cx, cy)) {
                drawLine(
                    color = color,
                    start = Offset(cx - halfSpan + 4.dp.toPx(), cy),
                    end = Offset(cx - gap, cy),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(cx + gap, cy),
                    end = Offset(cx + halfSpan - 4.dp.toPx(), cy),
                    strokeWidth = sw,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = color,
                    radius = if (tilt.isLevel) 3.5.dp.toPx() else 2.2.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }

        if (!tilt.isLevel && abs(tilt.rollDeg) >= 1f) {
            Text(
                text = "${abs(tilt.rollDeg).roundToInt()}°",
                color = Color.White.copy(alpha = 0.75f * alpha),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(top = 42.dp)
                    .background(Color(0x66000000), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Кольцо фокусировки и вертикальная шкала EV рядом с точкой касания.
 */
@Composable
fun FocusRingOverlay(
    focusTap: FocusTapState?,
    evIndex: Int,
    evRange: IntRange,
    evStep: Float,
    modifier: Modifier = Modifier,
) {
    val tap = focusTap ?: return
    val scale = remember(tap.id) { Animatable(1.45f) }
    LaunchedEffect(tap.id) {
        scale.animateTo(1f, animationSpec = tween(220, easing = FastOutSlowInEasing))
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val px = (tap.point.x * size.width).coerceIn(48.dp.toPx(), size.width - 48.dp.toPx())
            val py = (tap.point.y * size.height).coerceIn(56.dp.toPx(), size.height - 56.dp.toPx())
            val radius = 34.dp.toPx() * scale.value
            val ringColor = if (tap.locked) Pc.Accent else Pc.AccentGlow

            // Внешнее неоновое свечение кольца фокуса
            drawCircle(
                color = Pc.Accent2.copy(alpha = 0.35f),
                radius = radius + 3.dp.toPx(),
                center = Offset(px, py),
                style = Stroke(width = 4.dp.toPx()),
            )
            drawCircle(
                color = ringColor,
                radius = radius,
                center = Offset(px, py),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.85f),
                radius = 3.dp.toPx(),
                center = Offset(px, py),
            )
            val tick = 6.dp.toPx()
            drawLine(ringColor, Offset(px, py - radius), Offset(px, py - radius + tick), 1.8.dp.toPx())
            drawLine(ringColor, Offset(px, py + radius - tick), Offset(px, py + radius), 1.8.dp.toPx())
            drawLine(ringColor, Offset(px - radius, py), Offset(px - radius + tick, py), 1.8.dp.toPx())
            drawLine(ringColor, Offset(px + radius - tick, py), Offset(px + radius, py), 1.8.dp.toPx())

            // Вертикальная шкала EV справа (или слева, если тап у правого края)
            if (evRange.first < evRange.last) {
                val barX = if (px + radius + 28.dp.toPx() < size.width) {
                    px + radius + 18.dp.toPx()
                } else {
                    px - radius - 18.dp.toPx()
                }
                val halfBar = 44.dp.toPx()
                val topY = py - halfBar
                val botY = py + halfBar
                val frac = ((evIndex - evRange.first).toFloat() / (evRange.last - evRange.first).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
                val sunY = botY - frac * (botY - topY)

                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(barX, topY),
                    end = Offset(barX, botY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ringColor,
                    start = Offset(barX, sunY),
                    end = Offset(barX, botY),
                    strokeWidth = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = ringColor,
                    radius = 5.5.dp.toPx(),
                    center = Offset(barX, sunY),
                )
            }
        }
    }
}

/**
 * Преобразование координат объекта с учётом цифрового супер-зума (`extraZoom`).
 */
fun RectF.withExtraZoom(extraZoom: Float): RectF {
    if (extraZoom <= 1.001f) return this
    return RectF(
        0.5f + (left - 0.5f) * extraZoom,
        0.5f + (top - 0.5f) * extraZoom,
        0.5f + (right - 0.5f) * extraZoom,
        0.5f + (bottom - 0.5f) * extraZoom,
    )
}

/**
 * Обратное преобразование точки касания экрана в координаты кадра анализа без `extraZoom`.
 */
fun unzoomPoint(nx: Float, ny: Float, extraZoom: Float): PointF {
    if (extraZoom <= 1.001f) return PointF(nx, ny)
    return PointF(
        0.5f + (nx - 0.5f) / extraZoom,
        0.5f + (ny - 0.5f) / extraZoom,
    )
}

/**
 * HUD «Аимбота»: рамки обнаруженных объектов, захват цели, статус фокусировки и обратный отсчёт.
 */
@Composable
fun AimOverlay(
    aim: AimUi,
    extraZoom: Float,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "aimPulse").animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val targetRect = aim.target?.rect?.withExtraZoom(extraZoom)
    val phaseColor = when (aim.phase) {
        AimPhase.SEARCHING -> Color.White.copy(alpha = 0.65f)
        AimPhase.FOCUSING -> Pc.Accent2
        AimPhase.READY -> Pc.Accent
        AimPhase.COUNTDOWN -> Pc.Accent
        AimPhase.SHOT -> Color.White
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Второстепенные объекты (по ним можно тапнуть для захвата)
            for (other in aim.others) {
                val r = other.rect.withExtraZoom(extraZoom)
                if (r.right <= 0f || r.left >= 1f || r.bottom <= 0f || r.top >= 1f) continue
                drawCornerBox(
                    rect = r,
                    color = Color.White.copy(alpha = 0.42f),
                    strokePx = 1.6.dp.toPx(),
                    cornerFrac = 0.18f,
                )
            }

            // Главная цель
            if (targetRect != null) {
                val cx = (targetRect.left + targetRect.right) * 0.5f
                val cy = (targetRect.top + targetRect.bottom) * 0.5f
                val s = if (aim.phase == AimPhase.COUNTDOWN || aim.phase == AimPhase.FOCUSING) pulse else 1f
                val hw = targetRect.width() * 0.5f * s
                val hh = targetRect.height() * 0.5f * s
                val animatedRect = RectF(cx - hw, cy - hh, cx + hw, cy + hh)

                drawCornerBox(
                    rect = animatedRect,
                    color = phaseColor,
                    strokePx = 2.6.dp.toPx(),
                    cornerFrac = 0.24f,
                )

                // Центральное перекрестие на цели
                val px = cx * size.width
                val py = cy * size.height
                val cross = 7.dp.toPx()
                drawLine(phaseColor.copy(alpha = 0.8f), Offset(px - cross, py), Offset(px + cross, py), 1.5.dp.toPx())
                drawLine(phaseColor.copy(alpha = 0.8f), Offset(px, py - cross), Offset(px, py + cross), 1.5.dp.toPx())
            }
        }

        // Верхняя плашка статуса аимбота
        val statusText = when {
            aim.target == null -> "АИМБОТ: ПОИСК ОБЪЕКТА…"
            aim.phase == AimPhase.FOCUSING -> buildString {
                append(aim.target.label ?: "ЦЕЛЬ")
                if (aim.userLocked) append(" [ЗАХВАТ]")
                append(" • ФОКУС…")
            }
            aim.phase == AimPhase.READY -> buildString {
                append(aim.target.label ?: "ЦЕЛЬ")
                if (aim.userLocked) append(" [ЗАХВАТ]")
                append(" • ДЕРЖИТЕ РОВНО")
            }
            aim.phase == AimPhase.COUNTDOWN -> "СЪЁМКА ЧЕРЕЗ ${aim.countdown}…"
            aim.phase == AimPhase.SHOT -> "СНИМОК СДЕЛАН!"
            else -> "АИМБОТ АКТИВЕН"
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .glass(CircleShape, strong = true)
                .border(1.dp, phaseColor.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(phaseColor),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = statusText,
                color = Pc.Text,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        // Крупный обратный отсчёт в центре при автоспуске
        AnimatedVisibility(
            visible = aim.phase == AimPhase.COUNTDOWN && aim.countdown > 0,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .glass(CircleShape, strong = true)
                    .border(2.dp, Pc.Accent, CircleShape),
            ) {
                Text(
                    text = aim.countdown.toString(),
                    color = Pc.Accent,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun DrawScope.drawCornerBox(
    rect: RectF,
    color: Color,
    strokePx: Float,
    cornerFrac: Float,
) {
    val l = (rect.left * size.width).coerceIn(4f, size.width - 4f)
    val t = (rect.top * size.height).coerceIn(4f, size.height - 4f)
    val r = (rect.right * size.width).coerceIn(l + 16f, size.width - 4f)
    val b = (rect.bottom * size.height).coerceIn(t + 16f, size.height - 4f)
    val w = r - l
    val h = b - t
    val cl = (minOf(w, h) * cornerFrac).coerceIn(10.dp.toPx(), 28.dp.toPx())

    // Мягкая подложка
    drawRoundRect(
        color = color.copy(alpha = 0.08f),
        topLeft = Offset(l, t),
        size = Size(w, h),
        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
    )

    // Уголки
    val cap = StrokeCap.Round
    // Левый верхний
    drawLine(color, Offset(l, t), Offset(l + cl, t), strokePx, cap)
    drawLine(color, Offset(l, t), Offset(l, t + cl), strokePx, cap)
    // Правый верхний
    drawLine(color, Offset(r - cl, t), Offset(r, t), strokePx, cap)
    drawLine(color, Offset(r, t), Offset(r, t + cl), strokePx, cap)
    // Левый нижний
    drawLine(color, Offset(l, b - cl), Offset(l, b), strokePx, cap)
    drawLine(color, Offset(l, b), Offset(l + cl, b), strokePx, cap)
    // Правый нижний
    drawLine(color, Offset(r - cl, b), Offset(r, b), strokePx, cap)
    drawLine(color, Offset(r, b - cl), Offset(r, b), strokePx, cap)
}

/**
 * Бейдж блокировки AE/AF и текущей компенсации экспозиции.
 */
@Composable
fun ExposureLockBadge(
    aeAfLocked: Boolean,
    evIndex: Int,
    evStep: Float,
    onUnlock: () -> Unit,
    onResetEv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val evVal = evIndex * evStep
    val showEv = abs(evVal) >= 0.05f
    if (!aeAfLocked && !showEv) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (aeAfLocked) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .glass(CircleShape, strong = true)
                    .border(1.dp, Pc.Accent.copy(alpha = 0.6f), CircleShape)
                    .clickable(onClick = onUnlock)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Pc.Accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "AE/AF БЛОК",
                    color = Pc.Accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (showEv) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .glass(CircleShape, strong = true)
                    .clickable(onClick = onResetEv)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = null,
                    tint = Pc.Accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "%+.1f EV", evVal),
                    color = Pc.Text,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Карточка распознанного QR-кода.
 */
@Composable
fun QrBanner(
    hit: QrHit?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AnimatedVisibility(
        visible = hit != null,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
        modifier = modifier,
    ) {
        val qr = hit ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(18.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .glass(shape, strong = true)
                .border(1.dp, Pc.Accent.copy(alpha = 0.55f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = Pc.Accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (qr.isUrl) "Ссылка QR" else "QR-код",
                    color = Pc.Accent,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = qr.text,
                    color = Pc.Text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (qr.isUrl) {
                IconButton(
                    onClick = {
                        runCatching {
                            val uri = Uri.parse(
                                if (qr.text.startsWith("http://", true) || qr.text.startsWith("https://", true)) {
                                    qr.text
                                } else {
                                    "https://${qr.text}"
                                }
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            Toast.makeText(context, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Открыть", tint = Pc.Accent, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("QR", qr.text))
                    Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать", tint = Pc.Text, modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Закрыть", tint = Pc.TextDim, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/**
 * Радар-карта 20x–100x Space Zoom (как на Samsung Galaxy S25 Ultra):
 * показывает положение вырезанного супер-зумом участка внутри полного кадра и статус Zoom Lock.
 */
@Composable
fun SpaceZoomMapOverlay(
    zoom: Float,
    extraZoom: Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = zoom >= 12f,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        val lockColor = if (zoom >= 30f) Pc.Accent else Pc.AccentGlow
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .glass(RoundedCornerShape(14.dp), strong = true)
                .border(1.5.dp, lockColor.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                .padding(6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 64.dp, height = 82.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x88000000)),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    // Внешняя сетка радара
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(w / 2f, 0f), Offset(w / 2f, h), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, h / 2f), Offset(w, h / 2f), 1.dp.toPx())

                    // Рамка текущего окна супер-зума
                    val frac = (1f / extraZoom.coerceAtLeast(1.2f)).coerceIn(0.14f, 0.85f)
                    val bw = w * frac
                    val bh = h * frac
                    val left = (w - bw) / 2f
                    val top = (h - bh) / 2f
                    drawRoundRect(
                        color = lockColor,
                        topLeft = Offset(left, top),
                        size = Size(bw, bh),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = Stroke(width = 1.8.dp.toPx()),
                    )
                    drawCircle(lockColor, radius = 2.dp.toPx(), center = Offset(w / 2f, h / 2f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "SPACE %.0fx", zoom),
                color = lockColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Индикатор автоматического макро-режима (Focus Enhancer на сверхширике S25 Ultra).
 */
@Composable
fun MacroBadge(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = active,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .glass(CircleShape, strong = true)
                .border(1.dp, Pc.Accent.copy(alpha = 0.75f), CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterVintage,
                contentDescription = null,
                tint = Pc.Accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "АВТО-МАКРО",
                color = Pc.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Мгновенная всплывающая карточка только что сделанного снимка («Нажал — сфоткало — сразу посмотрел»).
 * Появляется за 0 мс прямо из Bitmap в RAM, не дожидаясь сохранения файла на диск.
 */
@Composable
fun InstantShotPopup(
    bitmap: Bitmap?,
    visible: Boolean,
    onOpenNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && bitmap != null,
        enter = scaleIn(initialScale = 0.75f, animationSpec = tween(140)) + fadeIn(tween(100)),
        exit = scaleOut(targetScale = 0.85f, animationSpec = tween(160)) + fadeOut(tween(140)),
        modifier = modifier,
    ) {
        val bmp = bitmap ?: return@AnimatedVisibility
        val imgBmp = remember(bmp) { bmp.asImageBitmap() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .glass(RoundedCornerShape(18.dp), strong = true)
                .border(1.5.dp, Pc.Accent, RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenNow)
                .padding(6.dp),
        ) {
            Image(
                bitmap = imgBmp,
                contentDescription = "Мгновенный снимок",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 48.dp, height = 60.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.padding(end = 8.dp)) {
                Text(
                    text = "Снято мгновенно",
                    color = Pc.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Открыть сразу →",
                    color = Pc.Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

