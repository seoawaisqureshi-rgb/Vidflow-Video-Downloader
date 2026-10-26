package com.vidflow.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary            = Violet,
    onPrimary          = TextPrimary,
    primaryContainer   = VioletDim,
    onPrimaryContainer = TextPrimary,
    secondary          = Cyan,
    onSecondary        = Background,
    secondaryContainer = CyanDim,
    onSecondaryContainer = TextPrimary,
    tertiary           = Cyan,
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

@Composable
fun VidFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = AppTypography,
        content     = content,
    )
}
