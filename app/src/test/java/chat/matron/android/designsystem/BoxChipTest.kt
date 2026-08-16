package chat.matron.android.designsystem

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
}
