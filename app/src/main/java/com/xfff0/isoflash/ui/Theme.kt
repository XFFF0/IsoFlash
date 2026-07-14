package com.xfff0.isoflash.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDeep         = Color(0xFF0B0F14)
val SurfaceCard    = Color(0xFF131A22)
val SurfaceCardAlt = Color(0xFF1A2330)
val BorderSubtle   = Color(0xFF24303D)
val AccentTeal     = Color(0xFF2DD4BF)
val AccentBlue     = Color(0xFF38BDF8)
val AccentTealDim  = Color(0xFF1B5E57)
val TextPrimary    = Color(0xFFE6EDF3)
val TextSecondary  = Color(0xFF8B98A5)
val StatusSuccess  = Color(0xFF22C55E)
val StatusError    = Color(0xFFEF4444)

@Composable
fun IsoFlashTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary          = AccentTeal,
            onPrimary        = BgDeep,
            secondary        = AccentBlue,
            background       = BgDeep,
            onBackground     = TextPrimary,
            surface          = SurfaceCard,
            onSurface        = TextPrimary,
            surfaceVariant   = SurfaceCardAlt,
            onSurfaceVariant = TextSecondary,
            error            = StatusError,
            outline          = BorderSubtle
        ),
        content = content
    )
}
