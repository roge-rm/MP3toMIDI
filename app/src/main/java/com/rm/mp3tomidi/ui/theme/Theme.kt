package com.rm.mp3tomidi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = BrandNavy,
    onPrimary = SurfaceCard,
    secondary = BrandTeal,
    onSecondary = BrandNavy,
    tertiary = BrandPink,
    onTertiary = SurfaceCard,
    background = BackgroundLavender,
    onBackground = BrandNavy,
    surface = SurfaceCard,
    onSurface = BrandNavy,
    surfaceVariant = BackgroundLavender,
    onSurfaceVariant = BrandNavy,
    outline = OutlineLavender,
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/**
 * Deliberately a single fixed light theme, no dark-theme/dynamic-color branching -- the brand
 * palette (see Color.kt) comes straight from the app icon, and a wallpaper-driven Material You
 * scheme would just erase that identity on Android 12+.
 */
@Composable
fun MP3toMIDITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
