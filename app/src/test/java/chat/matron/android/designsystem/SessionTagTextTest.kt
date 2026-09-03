package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pins the `A:bc` tag composition — the Swift original's `SessionTagText`
/// returns SwiftUI `Text` runs no test can inspect, but the Android port
/// builds `AnnotatedString`s, so the composition and the tag/chip color
/// parity (the PR's whole point) are pinnable here.
class SessionTagTextTest {
    private val secondary = Color(0xFF666666)

    private fun spanColors(s: AnnotatedString): List<Color> =
        s.spanStyles.map { it.item.color }

    @Test
    fun runComposesLetterAndShortAndGatesOnBoth() {
        val full = SessionTagText.run("Y", "dev-y", "b5", darkTheme = false, secondary = secondary)!!
        assertEquals("Y:b5", full.text)

        // Letter only (a seed title with no session short)…
        assertEquals("Y", SessionTagText.run("Y", "dev-y", null, false, secondary)!!.text)
        // …short only (single-box user: no letter, but sessions still differ)…
        assertEquals("b5", SessionTagText.run(null, null, "b5", false, secondary)!!.text)
        // …and nothing at all renders no tag.
        assertNull(SessionTagText.run(null, null, null, false, secondary))
    }

    /// The letter must carry the SAME hue as the box's chip — tags and chips
    /// can never disagree on color (parity pin for apple #152's requirement
    /// that the tag reuse the BoxChip palette).
    @Test
    fun letterColorMatchesTheBoxChipTint() {
        val tag = SessionTagText.run("Y", "dev-y", "b5", darkTheme = false, secondary = secondary)!!
        assertEquals(BoxChipColors.textTint("dev-y", darkTheme = false), spanColors(tag).first())
        assertEquals(secondary, spanColors(tag).last())

        val dark = SessionTagText.run("Y", "dev-y", null, darkTheme = true, secondary = secondary)!!
        assertEquals(BoxChipColors.textTint("dev-y", darkTheme = true), spanColors(dark).first())
    }

    @Test
    fun roomComposesPairWithArrowAndTrioWithCommas() {
        val pair = SessionTagText.room(
            letters = listOf("Y", "Z"), names = listOf("dev-y", "dev-z"),
            sessionShort = "ab", darkTheme = false, secondary = secondary,
        )!!
        assertEquals("Y↔Z:ab", pair.text)
        // Each letter carries its OWN box's hue.
        assertEquals(BoxChipColors.textTint("dev-y", false), spanColors(pair)[0])
        assertEquals(BoxChipColors.textTint("dev-z", false), spanColors(pair)[2])

        val trio = SessionTagText.room(
            letters = listOf("A", "B", "C"), names = listOf("a", "b", "c"),
            sessionShort = null, darkTheme = false, secondary = secondary,
        )!!
        assertEquals("A,B,C", trio.text)
    }

    /// The gates upstream mean a non-room, a local room, or a single-box
    /// user all fall through to `run(...)` — mirrored here: fewer than two
    /// letters, or mismatched parallel arrays, render no room tag.
    @Test
    fun roomRequiresTwoParallelEntries() {
        assertNull(SessionTagText.room(listOf("Y"), listOf("dev-y"), "ab", false, secondary))
        assertNull(SessionTagText.room(listOf("Y", "Z"), listOf("dev-y"), "ab", false, secondary))
        assertNull(SessionTagText.room(emptyList(), emptyList(), "ab", false, secondary))
    }
}
