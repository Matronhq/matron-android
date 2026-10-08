package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/// Matron's product palette, ported from matron-web's bubble-layout theme
/// (`res/css/matron/_matron.pcss`):
///   - timeline background: vertical cream gradient `#f2f0ea → #e8e5dc`
///   - bot bubble: white
///   - own-message bubble: light cyan `#c4f5fb`
///   - bubble shadow: `rgb(18,16,14 / 0.08)`
///
/// The web app is light-only; the dark variants are warm-neutral equivalents
/// chosen to keep the same figure/ground relationship (bubbles slightly
/// lighter than the timeline behind them). Values are kept EXACT to the Swift
/// `MatronPalette` so the Android app reads like the iOS one.
private fun rgb(r: Int, g: Int, b: Int): Color = Color(r, g, b)

/// The Matron-specific colour extras that don't live in Material3's
/// `ColorScheme`. Resolved per appearance and published through
/// [LocalMatronColors]; read at a call site with [MatronTheme.colors].
data class MatronColors(
    val timelineTop: Color,
    val timelineBottom: Color,
    val bubbleBot: Color,
    val bubbleMe: Color,
    val bubbleShadow: Color,
    val accent: Color,
    /// Inline-code / fenced-code surface (matron-web `--matronInlineCodeBg`).
    val codeBg: Color,
    /// A card's inner code block, one step off the card's own `codeBg`.
    val cardInnerBg: Color,
)

/// Fixed status colours (iOS system green/orange/red). Scheme-independent on
/// purpose: they sit on fixed surfaces (bars, dark terminal) where a
/// scheme-adaptive colour would resolve wrong. Shared by `UsageMetersFormat`,
/// `ToolCallCard`, `DiffCard`, `SubtaskLinkCard`, `SendStateIndicator`.
val MatronGreen: Color = rgb(52, 199, 89)
val MatronOrange: Color = rgb(255, 149, 0)
val MatronRed: Color = rgb(255, 59, 48)

val MatronLightColors = MatronColors(
    timelineTop = rgb(242, 240, 234),
    timelineBottom = rgb(232, 229, 220),
    bubbleBot = rgb(255, 255, 255),
    bubbleMe = rgb(196, 245, 251),
    bubbleShadow = rgb(18, 16, 14).copy(alpha = 0.08f),
    accent = rgb(11, 110, 125),
    codeBg = rgb(242, 242, 247),
    cardInnerBg = rgb(255, 255, 255),
)

val MatronDarkColors = MatronColors(
    timelineTop = rgb(29, 27, 24),
    timelineBottom = rgb(23, 21, 18),
    bubbleBot = rgb(38, 36, 33),
    bubbleMe = rgb(18, 58, 65),
    bubbleShadow = rgb(18, 16, 14).copy(alpha = 0.08f),
    accent = rgb(110, 205, 220),
    codeBg = rgb(44, 42, 39),
    cardInnerBg = rgb(28, 28, 30),
)

private val LightColorScheme = lightColorScheme(
    primary = MatronLightColors.accent,
    onPrimary = Color.White,
    surface = rgb(255, 255, 255),
    onSurface = rgb(20, 18, 16),
    surfaceVariant = MatronLightColors.codeBg,
    onSurfaceVariant = rgb(110, 108, 104),
    background = MatronLightColors.timelineBottom,
    onBackground = rgb(20, 18, 16),
    error = MatronRed,
    outline = rgb(180, 178, 172),
    outlineVariant = rgb(210, 208, 202),
)

private val DarkColorScheme = darkColorScheme(
    primary = MatronDarkColors.accent,
    onPrimary = Color.Black,
    surface = MatronDarkColors.bubbleBot,
    onSurface = rgb(235, 234, 231),
    surfaceVariant = MatronDarkColors.codeBg,
    onSurfaceVariant = rgb(170, 168, 163),
    background = MatronDarkColors.timelineBottom,
    onBackground = rgb(235, 234, 231),
    error = MatronRed,
    outline = rgb(90, 88, 84),
    outlineVariant = rgb(70, 68, 64),
)

val LocalMatronColors = staticCompositionLocalOf { MatronDarkColors }

/// Accessors mirroring `MaterialTheme` so call sites read
/// `MatronThemeColors.current.bubbleMe` alongside `MaterialTheme.colorScheme.*`.
object MatronThemeColors {
    val current: MatronColors
        @Composable
        get() = LocalMatronColors.current
}

/// MaterialTheme wrapper applying the Matron colour scheme (dark-first
/// aesthetic) and publishing the Matron colour extras. [appearance] forces
/// light/dark; `System` follows the OS.
@Composable
fun MatronTheme(
    appearance: MatronAppearance = MatronAppearance.System,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        MatronAppearance.System -> isSystemInDarkTheme()
        MatronAppearance.Light -> false
        MatronAppearance.Dark -> true
    }
    val matronColors = if (dark) MatronDarkColors else MatronLightColors
    val colorScheme = if (dark) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalMatronColors provides matronColors) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/// The chat timeline's cream backdrop — matron-web's
/// `--matron-room-timeline-background` gradient. Drop behind the whole chat
/// column so bubbles and the composer both sit on the same warm ground.
@Composable
fun timelineBackgroundBrush(): Brush {
    val colors = LocalMatronColors.current
    return Brush.verticalGradient(listOf(colors.timelineTop, colors.timelineBottom))
}

/// Fills its box with the timeline gradient.
fun Modifier.matronTimelineBackground(colors: MatronColors): Modifier =
    this.background(Brush.verticalGradient(listOf(colors.timelineTop, colors.timelineBottom)))

@Composable
fun MatronTimelineBackground(modifier: Modifier = Modifier) {
    val colors = LocalMatronColors.current
    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .matronTimelineBackground(colors)
    )
}
