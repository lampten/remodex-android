package dev.remodex.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = LightBg,
    secondary = SignalGreen,
    onSecondary = LightBg,
    tertiary = InkSecondary,
    onTertiary = LightBg,
    background = LightBg,
    onBackground = Ink,
    surface = LightBg,
    onSurface = Ink,
    surfaceVariant = CardBg,
    onSurfaceVariant = InkSecondary,
    outline = BorderLight,
    outlineVariant = Divider,
    error = ErrorRed,
    onError = LightBg,
    errorContainer = ErrorRed.copy(alpha = 0.1f),
    onErrorContainer = ErrorRed,
)

@Composable
fun RemodexTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = RemodexTypography,
        content = content,
    )
}
