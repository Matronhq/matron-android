package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports matron-apple's `SenderAvatarInitialsTests` plus the logic slices of
/// `SenderAvatarSnapshotTests` (the visual baselines are Apple snapshot
/// tests; the load-bearing halves — fixture hue distinctness and initials
/// bounds across the full palette — port as the assertions below).
class SenderAvatarTest {

    /// Ports `testInitials_digitsCountAsSegments`: collision cases from the
    /// spec — distinct box names with a shared letter prefix must still land
    /// on distinct initials so dev-2 / dev-3 / dev-6's avatars read apart at
    /// a glance.
    @Test
    fun initials_digitsCountAsSegments() {
        assertEquals("D2", senderAvatarInitials("dev-2"))
        assertEquals("D3", senderAvatarInitials("dev-3"))
        assertEquals("D6", senderAvatarInitials("dev-6"))
    }

    /// Ports `testInitials_twoWordName`.
    @Test
    fun initials_twoWordName() {
        assertEquals("DM", senderAvatarInitials("dan-mac"))
    }

    /// Ports `testInitials_singleWordName_yieldsOneLetter`: no second
    /// segment to draw from — must not pad or repeat.
    @Test
    fun initials_singleWordName_yieldsOneLetter() {
        assertEquals("M", senderAvatarInitials("mavis"))
    }

    /// Ports `testInitials_emptyString_doesNotCrash`.
    @Test
    fun initials_emptyString_doesNotCrash() {
        assertEquals("", senderAvatarInitials(""))
    }

    /// Ports `testInitials_capsAtTwoCharacters`.
    @Test
    fun initials_capsAtTwoCharacters() {
        assertEquals("AB", senderAvatarInitials("a-b-c"))
    }

    /// Ports `testInitials_lowercaseInput_isUppercased`.
    @Test
    fun initials_lowercaseInput_isUppercased() {
        assertEquals("E", senderAvatarInitials("eric"))
    }

    /// Ports `testInitials_ignoresEmptySegments`: leading/duplicate
    /// separators must not produce empty segments.
    @Test
    fun initials_ignoresEmptySegments() {
        assertEquals("D2", senderAvatarInitials("--dev--2"))
    }

    /// Ports `testInitials_expandingUppercase_staysCappedAtTwoCharacters`:
    /// `uppercase()` can EXPAND a character — German `ß` becomes `"SS"` — so
    /// capping at 2 characters BEFORE uppercasing doesn't bound the final
    /// string length. `"ß-a"` caps to the two letters "ß"/"a" pre-uppercase,
    /// which then uppercase to 3 displayed characters ("SSA") unless the cap
    /// is re-applied after (CodeRabbit, apple #141).
    @Test
    fun initials_expandingUppercase_staysCappedAtTwoCharacters() {
        assertEquals("SS", senderAvatarInitials("ß-a"))
        assertEquals(2, senderAvatarInitials("ß-a").length)
    }

    /// Pins the palette indices behind `testAvatarRow`'s fixtures (a
    /// snapshot test on Apple), so the two apps can never silently disagree
    /// on these avatars' colours. Note "dev-2" and "mavis" genuinely collide
    /// on index 3 — collisions are fine by design (the colour is an aid, the
    /// initials disambiguate: "D2" vs "M"), and Apple's baseline images
    /// captured exactly that.
    @Test
    fun avatarRowFixtures_pinTheirPaletteIndices() {
        assertEquals(3, BoxChipColors.paletteIndex("dev-2"))
        assertEquals(4, BoxChipColors.paletteIndex("dan-mac"))
        assertEquals(3, BoxChipColors.paletteIndex("mavis"))
        assertNotEquals(senderAvatarInitials("dev-2"), senderAvatarInitials("mavis"))
    }

    /// Ports the logic slice of `testAvatarFullPaletteSnapshots` (Apple's
    /// visual contrast review across every hue): the same ten fixture names
    /// (pinned to palette indices 0…9 in `BoxChipTest`) must all derive
    /// non-empty initials within the two-character cap, and the avatar's
    /// colour pair must be deterministic per name.
    @Test
    fun fullPaletteFixtures_deriveBoundedInitials_andDeterministicColours() {
        val names = listOf(
            "dev-7", "romeo", "india", "charlie", "quebec",
            "delta", "lima", "alpha", "echo", "foxtrot",
        )
        for (name in names) {
            val initials = senderAvatarInitials(name)
            assertTrue("$name must derive non-empty initials", initials.isNotEmpty())
            assertTrue("$name initials must cap at two characters", initials.length <= 2)
            assertEquals(BoxChipColors.tint(name), BoxChipColors.tint(name))
            assertEquals(
                BoxChipColors.contrastingForeground(name),
                BoxChipColors.contrastingForeground(name),
            )
            // The initials must never be drawn in the circle's own fill.
            assertNotEquals(BoxChipColors.tint(name), BoxChipColors.contrastingForeground(name))
        }
    }
}
