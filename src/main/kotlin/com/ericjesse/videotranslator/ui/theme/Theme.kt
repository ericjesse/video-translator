package com.ericjesse.videotranslator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Primary Colors
val Primary = Color(0xFF6366F1)
val PrimaryDark = Color(0xFF4F46E5)
val PrimaryLight = Color(0xFF818CF8)
val OnPrimary = Color.White

// Surface Colors - Light
val BackgroundLight = Color(0xFFFAFAFA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceDimLight = Color(0xFFF4F4F5)
val OnSurfaceLight = Color(0xFF18181B)
val OnSurfaceVariantLight = Color(0xFF71717A)

// Surface Colors - Dark
val BackgroundDark = Color(0xFF09090B)
val SurfaceDark = Color(0xFF18181B)
val SurfaceDimDark = Color(0xFF27272A)
val OnSurfaceDark = Color(0xFFFAFAFA)
val OnSurfaceVariantDark = Color(0xFFA1A1AA)

// Semantic Colors
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val Error = Color(0xFFEF4444)
val Info = Color(0xFF3B82F6)

// Status Colors
val StatusPending = Color(0xFFA1A1AA)
val StatusInProgress = Primary
val StatusComplete = Success
val StatusFailed = Error

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Primary,
    onSecondary = OnPrimary,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceDimLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    error = Error,
    onError = OnPrimary,
    outline = Color(0xFFE4E4E7)
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = PrimaryLight,
    secondary = Primary,
    onSecondary = OnPrimary,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDimDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    error = Error,
    onError = OnPrimary,
    outline = Color(0xFF3F3F46)
)

@Composable
fun VideoTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

// Extension colors for custom semantic usage
object AppColors {
    val success = Success
    val warning = Warning
    val error = Error
    val info = Info
    
    val statusPending = StatusPending
    val statusInProgress = StatusInProgress
    val statusComplete = StatusComplete
    val statusFailed = StatusFailed
}
