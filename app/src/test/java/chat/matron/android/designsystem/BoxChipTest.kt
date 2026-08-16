package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports matron-apple's `BoxChipTests` (the logic slices — the chip itself is
/// a composable, and Apple's colour snapshot baselines port as the pinned
/// palette-derivation assertions below).
class BoxChipTest {

    /// Ports `testChipIsSingleLineAndTruncates`: the chip must never grow a
    /// row — it renders on the title line, capped to one line. Rows keep a
    /// fixed height that a wrapping chip would break.
    @Test
    fun chipIsSingleLine() {
        assertEquals(1, BOX_CHIP_MAX_LINES)
    }

    /// Ports `testPaletteIndexIsPinned`: pins name → palette index for fixed
    /// fixtures, with the SAME values as the Swift suite. If this test
    /// breaks, the hash or palette changed and every user's colours
    /// re-shuffle — and the two apps stop agreeing on a box's colour. That
    /// must never happen silently.
    @Test
    fun paletteIndexIsPinned() {
        assertEquals(4, BoxChipColors.paletteIndex("eric"))
        assertEquals(4, BoxChipColors.paletteIndex("dan-mac"))
        assertEquals(9, BoxChipColors.paletteIndex("build-7"))
        assertEquals(1, BoxChipColors.paletteIndex("")) // FNV offset basis % 10
        assertEquals(1, BoxChipColors.paletteIndex("🦊 box")) // multi-byte UTF-8
    }

    /// Ports `testPaletteIndexIsDeterministicAndInRange`.
    @Test
    fun paletteIndexIsDeterministicAndInRange() {
        for (name in listOf("eric", "dan-mac", "build-7", "", "🦊 box", "a-very-long-box-name-that-will-not-fit")) {
            val first = BoxChipColors.paletteIndex(name)
            assertEquals(first, BoxChipColors.paletteIndex(name))
            assertTrue(first in 0 until BoxChipColors.palette.size)
        }
        // Distinct fixtures observed to land on distinct hues.
        assertNotEquals(BoxChipColors.paletteIndex("eric"), BoxChipColors.paletteIndex("build-7"))
    }

    /// Ports `testChipFullPaletteSnapshots`' fixture pinning (the visual
    /// baseline itself is an Apple snapshot test; the load-bearing half is
    /// that these ten names cover palette indices 0…9 in order, which also
    /// proves every hue is reachable).
    @Test
    fun fullPaletteFixturesPinEveryIndexInOrder() {
        val names = listOf(
            "dev-7", "romeo", "india", "charlie", "quebec",
            "delta", "lima", "alpha", "echo", "foxtrot",
        )
        for ((index, name) in names.withIndex()) {
            assertEquals("$name must pin palette index $index", index, BoxChipColors.paletteIndex(name))
        }
    }

    /// Ports the `testChipColorSnapshots` fixture assumptions ("eric" and
    /// "greg" land on different hues) plus the tint accessor contract.
    @Test
    fun tintResolvesThroughThePalette() {
        assertEquals(BoxChipColors.palette[4], BoxChipColors.tint("eric"))
        assertEquals(BoxChipColors.palette[2], BoxChipColors.tint("greg"))
        assertNotEquals(BoxChipColors.tint("eric"), BoxChipColors.tint("greg"))
        assertEquals(10, BoxChipColors.palette.size)
    }

    /// WCAG contrast ratio between two opaque colours; `Color.luminance()`
    /// is the WCAG relative luminance for sRGB colours.
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /// Every palette entry's text must clear WCAG AA small-text contrast
    /// (4.5:1) over the fill it actually renders on — the tint at
    /// `FILL_ALPHA` composited over the chip's host surface
    /// (`colorScheme.surface`: white in light, `MatronDarkColors.bubbleBot`
    /// in dark; see MatronPalette.kt). Pure maths over the same Color values
    /// the composable uses. The ten fixture names cover palette indices 0…9
    /// (pinned by `fullPaletteFixturesPinEveryIndexInOrder`).
    @Test
    fun textTintMeetsWcagAAOnEveryPaletteEntry() {
        val fixtures = listOf(
            "dev-7", "romeo", "india", "charlie", "quebec",
            "delta", "lima", "alpha", "echo", "foxtrot",
        )
        for (darkTheme in listOf(false, true)) {
            val surface = if (darkTheme) MatronDarkColors.bubbleBot else Color.White
            for (name in fixtures) {
                val fill = BoxChipColors.tint(name)
                    .copy(alpha = BoxChipColors.FILL_ALPHA)
                    .compositeOver(surface)
                val ratio = contrastRatio(BoxChipColors.textTint(name, darkTheme), fill)
                assertTrue(
                    "palette index ${BoxChipColors.paletteIndex(name)} " +
                        "(${if (darkTheme) "dark" else "light"}) contrast $ratio < 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }
}
