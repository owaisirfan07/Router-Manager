package com.veyronmonitor.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Energy palette (shared by dashboard, flow diagram and charts) ----
val SolarColor = Color(0xFFFBBF24)
val SolarDeep = Color(0xFFF59E0B)
val GridColor = Color(0xFF3B82F6)
val LoadColor = Color(0xFFA78BFA)
val BatteryColor = Color(0xFF22C55E)
val DischargeColor = Color(0xFFF97316)
val IdleColor = Color(0xFF64748B)

private val Navy950 = Color(0xFF0B1220)
private val Navy900 = Color(0xFF111A2E)
private val Navy800 = Color(0xFF1A2540)

private val DarkColors = darkColorScheme(
    primary = SolarColor,
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF3D2E05),
    onPrimaryContainer = Color(0xFFFFE08A),
    secondary = BatteryColor,
    tertiary = GridColor,
    tertiaryContainer = Color(0xFF3B2A0A),
    background = Navy950,
    onBackground = Color(0xFFE2E8F0),
    surface = Navy950,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Navy800,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy900,
    surfaceContainer = Navy900,
    surfaceContainerHigh = Navy800,
    surfaceContainerHighest = Color(0xFF243052),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDC2),
    onPrimaryContainer = Color(0xFF3D2500),
    secondary = Color(0xFF15803D),
    tertiary = GridColor,
    tertiaryContainer = Color(0xFFFFF4D6),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8ECF3),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F4F9),
    surfaceContainerHighest = Color(0xFFE8ECF3),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.3.sp)
    )
}

/** Small uppercase section caption used across screens. */
val SectionCaption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)

@Composable
fun VeyronMonitorTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
}
