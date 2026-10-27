package com.vidflow.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary            = Accent,
    onPrimary          = TextPrimary,
    primaryContainer   = AccentDeep,
    onPrimaryContainer = TextPrimary,
    secondary          = AccentDeep,
    onSecondary        = Background,
    secondaryContainer = AccentDark,
    onSecondaryContainer = TextPrimary,
    tertiary           = AccentDeep,
    onTertiary         = Background,
    background         = Background,
    onBackground       = TextPrimary,
    surface            = Surface,
    onSurface          = TextPrimary,
    surfaceVariant     = SurfaceVariant,
    onSurfaceVariant   = TextSecondary,
    outline            = Outline,
    error              = Error,
    onError            = TextPrimary,
    errorContainer     = ErrorDim,
    onErrorContainer   = Error,
)

/** One rounded-corner language for the whole app: cards 20dp, sheets 16dp. */
private val VidFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun VidFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        shapes      = VidFlowShapes,
        content     = content,
    )
}
