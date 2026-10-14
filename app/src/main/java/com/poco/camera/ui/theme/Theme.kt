package com.poco.camera.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Pc {
    /** Электро-синий неон (главный акцент NeoCam). */
    val Accent = Color(0xFF00E5FF)
    /** Глубокий кобальтовый синий неон (второй акцент). */
    val Accent2 = Color(0xFF0055FF)
    /** Ледяной голубой неон для свечений и бликов. */
    val AccentGlow = Color(0xFF38BDF8)
    val Record = Color(0xFFFF2E54)
    val Glass = Color(0x88040812)
    val GlassStrong = Color(0xDF050A16)
    val GlassStroke = Color(0x4500C8FF)
    val Bg = Color(0xFF020408)
    val Surface = Color(0xFF070C16)
    val SurfaceHi = Color(0xFF0D172A)
    val Text = Color(0xFFF2F8FF)
    val TextDim = Color(0xFF8AB4F8)
    val OnAccent = Color(0xFF010912)

    val AccentBrush = Brush.linearGradient(listOf(Color(0xFF00F0FF), Color(0xFF0055FF)))
    val DeckBrush = Brush.verticalGradient(listOf(Color(0xD9060C18), Color(0xFA020408)))
    val RingBrush = Brush.sweepGradient(
        listOf(
            Color(0xFF00F0FF),
            Color(0xFF0055FF),
            Color(0xFF38BDF8),
            Color(0xFF0044FF),
            Color(0xFF00F0FF),
        )
    )
}

fun Modifier.glass(shape: Shape = CircleShape, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (strong) Pc.GlassStrong else Pc.Glass)
    .border(1.dp, Pc.GlassStroke, shape)

/**
 * Фирменный векторный логотип NeoCam (Чёрный обсидиан + Синий неон).
 */
@Composable
fun NeoCamLogo(
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w * 0.5f
        val cy = h * 0.53f

        val neonBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF00F5FF), Color(0xFF0055FF), Color(0xFF00E5FF)),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        )

        // Круглая обсидиановая подложка с неоновым ободком
        drawCircle(
            color = Color(0xFF030509),
            radius = w * 0.49f,
            center = Offset(w * 0.5f, h * 0.5f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0D2B5E), Color(0xFF030509)),
                center = Offset(cx, cy),
                radius = w * 0.46f,
            ),
            radius = w * 0.46f,
            center = Offset(w * 0.5f, h * 0.5f),
        )
        drawCircle(
            brush = neonBrush,
            radius = w * 0.48f,
            center = Offset(w * 0.5f, h * 0.5f),
            style = Stroke(width = w * 0.032f),
        )

        // Корпус камеры
        val bodyLeft = w * 0.20f
        val bodyTop = h * 0.31f
        val bodyW = w * 0.60f
        val bodyH = h * 0.44f
        drawRoundRect(
            color = Color(0xFF050811),
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
        )
        drawRoundRect(
            brush = neonBrush,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
            style = Stroke(width = w * 0.035f),
        )

        // Верхняя неоновая кнопка спуска
        drawRoundRect(
            color = Pc.Accent,
            topLeft = Offset(w * 0.27f, h * 0.24f),
            size = Size(w * 0.13f, h * 0.045f),
            cornerRadius = CornerRadius(w * 0.02f, w * 0.02f),
        )

        // Лазерный индикатор справа сверху
        drawCircle(
            color = Pc.Accent,
            radius = w * 0.026f,
            center = Offset(w * 0.70f, h * 0.39f),
        )

        // Внешнее неоновое кольцо объектива
        val outerR = w * 0.185f
        drawCircle(
            brush = neonBrush,
            radius = outerR,
            center = Offset(cx, cy),
            style = Stroke(width = w * 0.042f),
        )

        // Внутренняя сапфировая линза
        val innerR = w * 0.115f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF103870), Color(0xFF04070E)),
                center = Offset(cx - innerR * 0.2f, cy - innerR * 0.2f),
                radius = innerR * 1.3f,
            ),
            radius = innerR,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = Pc.Accent.copy(alpha = 0.85f),
            radius = innerR,
            center = Offset(cx, cy),
            style = Stroke(width = w * 0.018f),
        )

        // Засечки телеметрии
        val tickIn = outerR * 0.74f
        val tickOut = outerR * 0.92f
        val sw = w * 0.022f
        drawLine(Pc.Accent, Offset(cx, cy - tickOut), Offset(cx, cy - tickIn), sw, StrokeCap.Round)
        drawLine(Pc.Accent, Offset(cx, cy + tickIn), Offset(cx, cy + tickOut), sw, StrokeCap.Round)
        drawLine(Pc.Accent, Offset(cx - tickOut, cy), Offset(cx - tickIn, cy), sw, StrokeCap.Round)
        drawLine(Pc.Accent, Offset(cx + tickIn, cy), Offset(cx + tickOut, cy), sw, StrokeCap.Round)

        // Неоновый блик на линзе
        drawCircle(
            color = Color(0xE600F5FF),
            radius = w * 0.032f,
            center = Offset(cx - innerR * 0.38f, cy - innerR * 0.38f),
        )
        drawCircle(
            color = Color.White,
            radius = w * 0.015f,
            center = Offset(cx - innerR * 0.42f, cy - innerR * 0.42f),
        )
    }
}

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun PocoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Pc.Accent,
            onPrimary = Pc.OnAccent,
            secondary = Pc.Accent2,
            background = Pc.Bg,
            onBackground = Pc.Text,
            surface = Pc.Surface,
            onSurface = Pc.Text,
            surfaceVariant = Pc.SurfaceHi,
            onSurfaceVariant = Pc.TextDim,
            surfaceContainer = Pc.Surface,
            surfaceContainerHigh = Pc.SurfaceHi,
            surfaceContainerLow = Pc.Surface,
        ),
        typography = typography,
        content = content,
    )
}
