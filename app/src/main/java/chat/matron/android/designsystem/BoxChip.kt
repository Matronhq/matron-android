package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// The chip must never grow a row: it renders on the title line, capped to one
/// line (matron-apple pins the same invariant in `BoxChipTests`).
const val BOX_CHIP_MAX_LINES = 1

/**
 * Deterministic per-box colour derivation, ported from matron-apple's
 * `BoxChip` statics (apple #136): the same box name yields the same colour on
 * iOS, Mac, and Android, every launch. Public and view-free on purpose —
 * follow-up features (sender avatars, session tags) reuse the palette.
 */
object BoxChipColors {
    /// Fixed hue palette. Order is frozen — reordering or inserting entries
    /// re-rolls every user's box colours (`paletteIndexIsPinned` pins it).
    /// Values are the iOS system colours (light variants), matching the
    /// repo's `MatronGreen`/`MatronOrange`/`MatronRed` precedent, so both
    /// apps paint the same box the same hue.
    val palette: List<Color> = listOf(
        Color(0, 122, 255), // blue
        Color(52, 199, 89), // green
        Color(255, 149, 0), // orange
        Color(175, 82, 222), // purple
        Color(48, 176, 199), // teal
        Color(255, 45, 85), // pink
        Color(88, 86, 214), // indigo
        Color(162, 132, 94), // brown
        Color(50, 173, 230), // cyan
        Color(0, 199, 190), // mint
    )

    /// FNV-1a (32-bit) over UTF-8, mod palette size. Explicitly not Kotlin's
    /// `hashCode` — the algorithm (and its fixture pins) must match the Swift
    /// implementation byte for byte, and stay stable across releases.
    fun paletteIndex(name: String): Int {
        var hash = 2_166_136_261u
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            hash = hash xor byte.toUByte().toUInt()
            hash *= 16_777_619u // wrapping multiply, like Swift's &*
        }
        return (hash % palette.size.toUInt()).toInt()
    }

    /// Deterministic colour for a box name: same name → same colour on every
    /// platform, every launch. Collisions between names are fine — the colour
    /// is an aid, the name is printed.
    fun tint(name: String): Color = palette[paletteIndex(name)]

    /// Alpha of the chip's fill (the tint washed over the surface). Shared
    /// between the composable and [textTint]'s contrast maths so the two can
    /// never disagree about what the text actually sits on.
    const val FILL_ALPHA = 0.18f

    /// WCAG AA for small text; `labelSmall` is well under the 18pt/14pt-bold
    /// large-text cutoff, so the chip label gets the strict floor.
    private const val MIN_TEXT_CONTRAST = 4.5

    /// WCAG contrast ratio between two opaque colours. `Color.luminance()`
    /// is the WCAG relative luminance for sRGB colours.
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /// Text colour for one palette entry: start from the Apple-parity mix
    /// (30% toward white in dark, 35% toward black in light), then deepen in
    /// 5% steps until the label clears [MIN_TEXT_CONTRAST] against the fill
    /// it actually renders on (tint at [FILL_ALPHA] composited over the
    /// chip's host surface — `colorScheme.surface`: white in light,
    /// `MatronDarkColors.bubbleBot` in dark, see MatronPalette.kt).
    ///
    /// Deepening the text shade does NOT break cross-app colour identity:
    /// parity lives in the palette hues + the FNV-1a index (pinned above),
    /// while Apple's own text mix goes through an OS-version-dependent mix
    /// API — so the exact text shade was never pixel-identical across apps.
    private fun readableTextTint(base: Color, darkTheme: Boolean): Color {
        val towards = if (darkTheme) Color.White else Color.Black
        val surface = if (darkTheme) MatronDarkColors.bubbleBot else Color.White
        val fill = base.copy(alpha = FILL_ALPHA).compositeOver(surface)
        var fraction = if (darkTheme) 0.3f else 0.35f
        var text = lerp(base, towards, fraction)
        while (contrastRatio(text, fill) < MIN_TEXT_CONTRAST && fraction < 1f) {
            fraction = minOf(1f, fraction + 0.05f)
            text = lerp(base, towards, fraction)
        }
        return text
    }

    /// Per-theme text tints, precomputed once per palette entry (the loop in
    /// [readableTextTint] shouldn't run on every recomposition).
    private val lightTextTints: List<Color> = palette.map { readableTextTint(it, darkTheme = false) }
    private val darkTextTints: List<Color> = palette.map { readableTextTint(it, darkTheme = true) }

    /// The raw hues are accent colours tuned for white text ON them, not for
    /// being text — teal/cyan/mint captions on the pale fill land around 2:1
    /// contrast. Pull the text toward the label colour (darker in light mode,
    /// lighter in dark) to clear readable contrast while keeping the hue;
    /// every entry is guaranteed ≥ 4.5:1 over its fill in both themes
    /// (`textTintMeetsWcagAAOnEveryPaletteEntry` pins it).
    fun textTint(name: String, darkTheme: Boolean): Color =
        (if (darkTheme) darkTextTints else lightTextTints)[paletteIndex(name)]
}

/**
 * The agent box that owns a conversation, as a small tinted capsule beside
 * the title — GitHub-label style, coloured per machine so eric is always the
 * same colour everywhere. Shown only when the user has two or more boxes —
 * the decision is made upstream in `JournalChatService`, so this composable
 * just renders whatever name it is handed. Ports MatronShared/Sources/
 * DesignSystem/BoxChip.swift (apple #131 + #136).
 *
 * Single-line and truncating by construction: chat rows keep a fixed height
 * and a wrapping chip would break it.
 */
@Composable
fun BoxChip(name: String, modifier: Modifier = Modifier) {
    // Surface luminance rather than isSystemInDarkTheme(): MatronTheme can
    // force an appearance that disagrees with the OS setting.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    Text(
        name,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        maxLines = BOX_CHIP_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        color = BoxChipColors.textTint(name, darkTheme),
        modifier = modifier
            .background(
                BoxChipColors.tint(name).copy(alpha = BoxChipColors.FILL_ALPHA),
                RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = "Agent box $name" },
    )
}
