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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FocusTapState(
    val id: Long,
    val point: PointF,
    val locked: Boolean = false,
)

/**
 * Подсветка резких контуров объекта в фокусе (Focus Peaking).
 */
@Composable
fun FocusPeakingOverlay(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val bmp = bitmap ?: return
    val img = remember(bmp) { bmp.asImageBitmap() }
    Image(
        bitmap = img,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.Medium,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Сетка третей 3x3 в стиле GCam.
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val line = Color(0x38FFFFFF)
        val sw = 0.8.dp.toPx()
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            drawLine(line, Offset(x, 0f), Offset(x, h), strokeWidth = sw)
            drawLine(line, Offset(0f, y), Offset(w, y), strokeWidth = sw)
        }
    }
}

/**
 * Электронный уровень горизонта GCam.
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
            delay(1200)
            hideLevel = true
        } else {
            wasLevel = false
            hideLevel = false
        }
    }

    val visible = !hideLevel && abs(tilt.pitchDeg) < 75f && abs(tilt.rollDeg) < 25f
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
        label = "levelAlpha",
    )
    if (alpha <= 0.01f) return

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(130.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfSpan = 38.dp.toPx()
            val gap = 10.dp.toPx()
            val tickLen = 8.dp.toPx()
            val color = if (tilt.isLevel) Pc.Accent.copy(alpha = alpha) else Color.White.copy(alpha = 0.65f * alpha)
            val refColor = Color.White.copy(alpha = 0.32f * alpha)
            val sw = if (tilt.isLevel) 2.dp.toPx() else 1.4.dp.toPx()

            drawLine(refColor, Offset(cx - halfSpan - tickLen, cy), Offset(cx - halfSpan, cy), 1.4.dp.toPx(), StrokeCap.Round)
            drawLine(refColor, Offset(cx + halfSpan, cy), Offset(cx + halfSpan + tickLen, cy), 1.4.dp.toPx(), StrokeCap.Round)

            rotate(degrees = -tilt.rollDeg, pivot = Offset(cx, cy)) {
                drawLine(color, Offset(cx - halfSpan + 3.dp.toPx(), cy), Offset(cx - gap, cy), sw, StrokeCap.Round)
                drawLine(color, Offset(cx + gap, cy), Offset(cx + halfSpan - 3.dp.toPx(), cy), sw, StrokeCap.Round)
            }
        }
    }
}

/**
 * Кольцо фокусировки GCam и вертикальный ползунок яркости (EV).
 */
@Composable
fun FocusRingOverlay(
    focusTap: FocusTapState?,
    evIndex: Int,
    evRange: IntRange,
    modifier: Modifier = Modifier,
) {
    val tap = focusTap ?: return
    val scale = remember(tap.id) { Animatable(1.35f) }
    LaunchedEffect(tap.id) {
        scale.animateTo(1f, animationSpec = tween(180, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width < 120f || size.height < 120f) return@Canvas
        val padX = min(48.dp.toPx(), size.width * 0.25f)
        val padY = min(56.dp.toPx(), size.height * 0.25f)
        val px = (tap.point.x * size.width).coerceIn(padX, max(padX, size.width - padX))
        val py = (tap.point.y * size.height).coerceIn(padY, max(padY, size.height - padY))
        val radius = 32.dp.toPx() * scale.value
        val ringColor = if (tap.locked) Pc.Accent else Color.White

        drawCircle(
            color = Color(0x40000000),
            radius = radius,
            center = Offset(px, py),
            style = Stroke(width = 2.8.dp.toPx()),
        )
        drawCircle(
            color = ringColor,
            radius = radius,
            center = Offset(px, py),
            style = Stroke(width = 1.8.dp.toPx()),
        )
        drawCircle(
            color = ringColor.copy(alpha = 0.22f),
            radius = radius * 0.32f,
            center = Offset(px, py),
        )

        if (evRange.first < evRange.last) {
            val barX = if (px + radius + 26.dp.toPx() < size.width) {
                px + radius + 16.dp.toPx()
            } else {
                px - radius - 16.dp.toPx()
            }
            val halfBar = 38.dp.toPx()
            val topY = py - halfBar
            val botY = py + halfBar
            val frac = ((evIndex - evRange.first).toFloat() / (evRange.last - evRange.first).coerceAtLeast(1))
                .coerceIn(0f, 1f)
            val sunY = botY - frac * (botY - topY)

            drawLine(
                color = Color.White.copy(alpha = 0.32f),
                start = Offset(barX, topY),
                end = Offset(barX, botY),
                strokeWidth = 1.8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = ringColor,
                start = Offset(barX, sunY),
                end = Offset(barX, botY),
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = ringColor,
                radius = 5.dp.toPx(),
                center = Offset(barX, sunY),
            )
        }
    }
}

fun RectF.withExtraZoom(extraZoom: Float): RectF {
    if (extraZoom <= 1.001f) return this
    return RectF(
        0.5f + (left - 0.5f) * extraZoom,
        0.5f + (top - 0.5f) * extraZoom,
        0.5f + (right - 0.5f) * extraZoom,
        0.5f + (bottom - 0.5f) * extraZoom,
    )
}

fun unzoomPoint(nx: Float, ny: Float, extraZoom: Float): PointF {
    if (extraZoom <= 1.001f) return PointF(nx, ny)
    return PointF(
        0.5f + (nx - 0.5f) / extraZoom,
        0.5f + (ny - 0.5f) / extraZoom,
    )
}

/**
 * Трекинг-фокус объекта в стиле GCam (чистое кольцо слежения без текстового мусора).
 */
@Composable
fun AimOverlay(
    aim: AimUi,
    extraZoom: Float,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "aimPulse").animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    val targetRect = aim.target?.rect?.withExtraZoom(extraZoom)
    val ringColor = when (aim.phase) {
        AimPhase.READY, AimPhase.COUNTDOWN -> Pc.Accent
        AimPhase.FOCUSING -> Color.White.copy(alpha = 0.9f)
        AimPhase.SHOT -> Pc.AccentGlow
        AimPhase.SEARCHING -> Color.White.copy(alpha = 0.5f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.width < 32f || size.height < 32f) return@Canvas
            val tr = targetRect ?: return@Canvas
            val cx = (tr.centerX() * size.width).coerceIn(28f, max(28f, size.width - 28f))
            val cy = (tr.centerY() * size.height).coerceIn(28f, max(28f, size.height - 28f))
            val baseR = 30.dp.toPx() * (if (aim.phase == AimPhase.FOCUSING) pulse else 1f)

            // Двойное кольцо слежения GCam
            drawCircle(
                color = Color(0x45000000),
                radius = baseR,
                center = Offset(cx, cy),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(
                color = ringColor,
                radius = baseR,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )
            drawCircle(
                color = ringColor.copy(alpha = 0.55f),
                radius = baseR * 0.68f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawCircle(
                color = ringColor,
                radius = 3.dp.toPx(),
                center = Offset(cx, cy),
            )
        }

        // Обратный отсчёт при включённом автоспуске
        AnimatedVisibility(
            visible = aim.phase == AimPhase.COUNTDOWN && aim.countdown > 0,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .glass(CircleShape, strong = true)
                    .border(2.dp, Pc.Accent, CircleShape),
            ) {
                Text(
                    text = aim.countdown.toString(),
                    color = Pc.Accent,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Компактные пилюли блокировки AE/AF и поправки экспозиции.
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
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Pc.Accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "AE/AF",
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
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.WbSunny,
                    contentDescription = null,
                    tint = Pc.Accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = String.format(Locale.US, "%+.1f", evVal),
                    color = Pc.Text,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Компактная плашка QR-кода (как в GCam).
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
        val shape = RoundedCornerShape(24.dp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .glass(shape, strong = true)
                .border(1.dp, Pc.Accent.copy(alpha = 0.55f), shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.QrCodeScanner,
                contentDescription = null,
                tint = Pc.Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = qr.text,
                color = Pc.Text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Открыть", tint = Pc.Accent, modifier = Modifier.size(17.dp))
                }
            }
            IconButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("QR", qr.text))
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать", tint = Pc.Text, modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Закрыть", tint = Pc.TextDim, modifier = Modifier.size(15.dp))
            }
        }
    }
}
