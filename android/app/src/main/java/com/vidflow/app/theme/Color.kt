package com.vidflow.app.theme

import androidx.compose.ui.graphics.Color

// ── Brand Palette: Neumorphic Black + Green ─────────────────────────────────
// Final direction (user-approved icon match): near-black neumorphic surfaces
// with soft light edge top-left / soft dark shadow bottom-right, Spotify-green
// accent sampled from the launcher icon disc.

val Background       = Color(0xFF0B0E11)   // near-black
val Surface          = Color(0xFF15151B)   // slightly lighter black surface
val SurfaceVariant   = Color(0xFF1D1D26)   // lighter black surface variant
val NeuLight         = Color(0x1AFFFFFF)   // white 10% — neumorphic top-left light
val NeuDark          = Color(0x99000000)   // black 60% — neumorphic bottom-right shadow
val Outline          = Color(0x1AFFFFFF)   // white 10% — subtle border

val Accent           = Color(0xFF1ED760)   // Spotify green, primary accent
val AccentLight      = Color(0xFF3CE87F)   // light green, gradient start
val AccentDeep       = Color(0xFF0E9A48)   // deep green, gradient end
val AccentDark       = Color(0xFF0B3D22)   // dark green, container tone
val AccentGlow       = Color(0x331ED760)   // green 20% for glow effects

val TextPrimary      = Color(0xFFF5F1E8)   // warm white
val TextSecondary    = Color(0xFFC9CEDB)   // warm light grey
val TextMuted        = Color(0xFF9AA3B2)   // muted grey

val Success          = Color(0xFF10B981)
val Warning          = Color(0xFFF59E0B)
val Error            = Color(0xFFEF4444)
val ErrorDim         = Color(0x33EF4444)

// Gradient endpoints (used in Brush.linearGradient): light green → deep green
val GradientStart    = AccentLight
val GradientEnd      = AccentDeep
