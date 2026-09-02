package com.rm.mp3tomidi.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette lifted directly from the app icon (branding/app-icon.svg), so the icon and the
// UI read as the same product rather than a launcher icon bolted onto a generic Material theme.
val BrandNavy = Color(0xFF10102B)
val BrandTeal = Color(0xFF00E5C7)
val BrandPink = Color(0xFFFF4D9D)
val BrandYellow = Color(0xFFFFC93C)
val DangerRed = Color(0xFFE5484D)

// Bright brand colors are for things they sit *behind* (gradients, filled chips, progress
// fill) -- as text/icon color on a light background they're too low-contrast to read
// comfortably, so headings and body accents use BrandNavy instead.
val BackgroundLavender = Color(0xFFF5F4FB)
val SurfaceCard = Color(0xFFFFFFFF)
val OutlineLavender = Color(0xFFDAD7EA)
