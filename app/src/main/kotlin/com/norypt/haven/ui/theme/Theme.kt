package com.norypt.haven.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Semantic colours not covered by Material's scheme. */
data class HavenExtraColors(val success: androidx.compose.ui.graphics.Color, val warning: androidx.compose.ui.graphics.Color, val danger: androidx.compose.ui.graphics.Color)

val LocalHavenColors = staticCompositionLocalOf { HavenExtraColors(NoryptColors.Success, NoryptColors.Warning, NoryptColors.Danger) }

/** Light: white / near-white surfaces, navy text, electric-blue (dark variant) actions. */
val LightScheme: ColorScheme = lightColorScheme(
    primary = NoryptColors.BlueDark,
    onPrimary = NoryptColors.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE8FF),
    onPrimaryContainer = NoryptColors.Navy,
    secondary = NoryptColors.Navy,
    onSecondary = NoryptColors.White,
    secondaryContainer = NoryptColors.Slate100,
    onSecondaryContainer = NoryptColors.Navy,
    tertiary = NoryptColors.Blue,
    onTertiary = NoryptColors.White,
    background = NoryptColors.Cloud,
    onBackground = NoryptColors.Navy,
    surface = NoryptColors.White,
    onSurface = NoryptColors.Navy,
    surfaceVariant = NoryptColors.Slate100,
    onSurfaceVariant = NoryptColors.Slate700,
    surfaceContainer = NoryptColors.White,
    surfaceContainerHigh = NoryptColors.Slate100,
    surfaceContainerHighest = NoryptColors.Slate200,
    surfaceContainerLow = NoryptColors.White,
    surfaceContainerLowest = NoryptColors.White,
    outline = NoryptColors.Slate300,
    outlineVariant = NoryptColors.Slate200,
    error = NoryptColors.Danger,
    onError = NoryptColors.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFFCE4E1),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF6B1A12),
)

/** Dark: navy surfaces from the brand, light-blue actions, high-contrast text. */
val DarkScheme: ColorScheme = darkColorScheme(
    primary = NoryptColors.BlueLight,
    onPrimary = NoryptColors.Navy,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF1F3F7A),
    onPrimaryContainer = NoryptColors.DarkText,
    secondary = NoryptColors.BlueLight,
    onSecondary = NoryptColors.Navy,
    secondaryContainer = NoryptColors.NavyLight,
    onSecondaryContainer = NoryptColors.DarkText,
    tertiary = NoryptColors.Blue,
    onTertiary = NoryptColors.White,
    background = NoryptColors.NavyDeep,
    onBackground = NoryptColors.DarkText,
    surface = NoryptColors.Navy,
    onSurface = NoryptColors.DarkText,
    surfaceVariant = NoryptColors.NavyLight,
    onSurfaceVariant = NoryptColors.DarkTextMuted,
    surfaceContainer = NoryptColors.Navy,
    surfaceContainerHigh = NoryptColors.NavyLight,
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF243052),
    surfaceContainerLow = NoryptColors.NavyDeep,
    surfaceContainerLowest = NoryptColors.NavyDeep,
    outline = NoryptColors.DarkOutline,
    outlineVariant = NoryptColors.NavyLight,
    error = NoryptColors.DangerDark,
    onError = NoryptColors.Navy,
    errorContainer = androidx.compose.ui.graphics.Color(0xFF5A1F18),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFFFD9D4),
)

val HavenShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** System font (bundled with Android; nothing remote). Sizes in sp so user font scaling applies. */
val HavenTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun HavenTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val extra = if (dark) HavenExtraColors(NoryptColors.SuccessDark, NoryptColors.WarningDark, NoryptColors.DangerDark)
    else HavenExtraColors(NoryptColors.Success, NoryptColors.Warning, NoryptColors.Danger)
    CompositionLocalProvider(LocalHavenColors provides extra) {
        // Dynamic (wallpaper) colours are intentionally not used: the Norypt palette is the identity.
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = HavenTypography, shapes = HavenShapes, content = content)
    }
}
