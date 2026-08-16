package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
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

    /// Ports `testContrastingForegroundClearsWCAG_AA_forEveryPaletteEntry`
    /// (apple #141): `contrastingForeground` (used by `SenderAvatar`'s
    /// initials, drawn on the RAW full-opacity fill — unlike this chip's own
    /// `textTint`, which is for text beside a pale ~18%-opacity capsule)
    /// must clear WCAG AA (4.5:1) against every palette entry's raw hue.
    /// Fixture names chosen so each pins a distinct index (mirrors
    /// `fullPaletteFixturesPinEveryIndexInOrder`). Contrast ratios use
    /// Compose's WCAG relative luminance (`Color.luminance()`), the same
    /// function the implementation reads — Apple carries its own
    /// `relativeLuminance` because SwiftUI has none.
    @Test
    fun contrastingForegroundClearsWCAG_AA_forEveryPaletteEntry() {
        val names = listOf(
            "dev-7", "romeo", "india", "charlie", "quebec",
            "delta", "lima", "alpha", "echo", "foxtrot",
        )
        for ((index, name) in names.withIndex()) {
            assertEquals("$name must pin palette index $index", index, BoxChipColors.paletteIndex(name))
            val luminance = BoxChipColors.tint(name).luminance()
            val contrastWithWhite = 1.05f / (luminance + 0.05f)
            val contrastWithBlack = (luminance + 0.05f) / 0.05f
            val bestRatio = maxOf(contrastWithWhite, contrastWithBlack)
            assertTrue(
                "palette index $index can't clear WCAG AA (4.5:1) with either white or black text — best available is $bestRatio",
                bestRatio >= 4.5f,
            )

            val expected = if (contrastWithBlack > contrastWithWhite) Color.Black else Color.White
            assertEquals(
                "index $index must pick the higher-contrast option",
                expected,
                BoxChipColors.contrastingForeground(name),
            )
        }
    }

    /// Ports `testContrastingForegroundIsDeterministic`: deterministic
    /// per-name, matching `paletteIndex`'s own contract — same name always
    /// resolves to the same foreground choice.
    @Test
    fun contrastingForegroundIsDeterministic() {
        for (name in listOf("eric", "dan-mac", "build-7", "", "🦊 box")) {
            assertEquals(
                BoxChipColors.contrastingForeground(name),
                BoxChipColors.contrastingForeground(name),
            )
        }
    }

    /// Ports `testContrastingForeground_cyanAndMint_resolveToBlack`: pins
    /// the two hues the Apple review explicitly flagged as failing WCAG with
    /// white text (≈2.5:1 cyan, ≈2.1:1 mint) — both must resolve to black.
    @Test
    fun contrastingForeground_cyanAndMint_resolveToBlack() {
        // "echo" pins palette index 8 (cyan), "foxtrot" index 9 (mint) — per
        // the full-palette fixture list's mapping.
        assertEquals(8, BoxChipColors.paletteIndex("echo"))
        assertEquals(9, BoxChipColors.paletteIndex("foxtrot"))
        assertEquals(Color.Black, BoxChipColors.contrastingForeground("echo"))
        assertEquals(Color.Black, BoxChipColors.contrastingForeground("foxtrot"))
    }
}
