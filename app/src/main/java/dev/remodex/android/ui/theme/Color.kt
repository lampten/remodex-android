package dev.remodex.android.ui.theme

import androidx.compose.ui.graphics.Color

// ── iOS-aligned light palette ──

// Backgrounds
val LightBg = Color(0xFFFFFFFF)          // Main content background (white)
val SidebarBg = Color(0xFFF2F2F7)        // iOS secondarySystemBackground
val CardBg = Color(0xFFF5F5F5)           // Subtle card surface
val ComposerBg = Color(0xFFF8F8FA)       // Composer tray background

// Text
val Ink = Color(0xFF1A1A1A)              // Primary text
val InkSecondary = Color(0xFF6B6B6B)     // Secondary text
val InkTertiary = Color(0xFF999999)      // Tertiary / placeholder

// Accent
val Copper = Color(0xFFC56C2E)           // Warm accent (retained)
val CopperDeep = Color(0xFF8C4215)       // Button accent
val CopperLight = Color(0xFFFFF3EB)      // Copper tint for selections

// Semantic
val SignalGreen = Color(0xFF2C8A5A)      // Connected / running
val ErrorRed = Color(0xFFD94C4C)         // Error / delete
val WarningAmber = Color(0xFFE8A838)     // Busy / warning

// Borders & dividers
val Divider = Color(0xFFE5E5EA)          // iOS separator color
val BorderLight = Color(0xFFD1D1D6)      // Subtle borders

// Bubble colors
val UserBubble = Color(0xFF007AFF)       // iOS blue for user messages
val AssistantBubble = Color(0xFFF2F2F7)  // Light gray for assistant
val SystemBubble = Color(0xFFFFF8F0)     // Warm tint for system

// Legacy aliases (keep for any remaining refs during transition)
val Sand = Color(0xFFF6F1E8)
val Sandstone = Color(0xFFE7DECF)
val Slate = Color(0xFF596273)
val Storm = Color(0xFF202833)
val Mist = Color(0xFFB4BDC9)
val Night = Color(0xFF0F1217)
val NightSurface = Color(0xFF171C24)
val NightOutline = Color(0xFF3A4452)
