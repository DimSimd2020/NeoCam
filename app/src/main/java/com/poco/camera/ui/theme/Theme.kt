package com.poco.camera.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.camera.R

object Pc {
    val Accent = Color(0xFF2589FF)
    val Accent2 = Color(0xFF1565F5)
    val AccentGlow = Color(0xFF319DFF)
    val Record = Color(0xFFFF2E54)
    val Glass = Color(0x8811151E)
    val GlassStrong = Color(0xDF131722)
    val GlassStroke = Color(0x40319DFF)
    val Bg = Color(0xFF0A0C10)
    val Surface = Color(0xFF13161F)
    val SurfaceHi = Color(0xFF1B202E)
    val Text = Color(0xFFF2F8FF)
    val TextDim = Color(0xFF9AB8E8)
    val OnAccent = Color(0xFFFFFFFF)

    val AccentBrush = Brush.linearGradient(listOf(Color(0xFF319DFF), Color(0xFF1565F5)))
    val RingBrush = Brush.sweepGradient(
        listOf(
            Color(0xFF319DFF),
            Color(0xFF1565F5),
            Color(0xFF28ECEE),
            Color(0xFF319DFF),
        )
    )
}

fun Modifier.glass(shape: Shape = CircleShape, strong: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (strong) Pc.GlassStrong else Pc.Glass)
    .border(1.dp, Pc.GlassStroke, shape)

@Composable
fun NeoCamLogo(
    size: Dp = 64.dp,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF191919), Color(0xFF131313)))),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "NeoCam",
            modifier = Modifier.fillMaxSize(),
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
