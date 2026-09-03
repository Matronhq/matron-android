package chat.matron.android.chat

import chat.matron.android.viewmodels.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple's `BoxLetterOverridesTests` (apple #154). The
/// throwaway `UserDefaults(suiteName:)` becomes the InMemoryKeyValueStore
/// fake, and the change NotificationCenter post becomes a StateFlow emission.
class BoxLetterOverridesTest {

    private val store = InMemoryKeyValueStore()
    private val overrides = BoxLetterOverrides(store)

    /// Ports matron-apple's `testSetReadClearRoundTrip`.
    @Test
    fun setReadClearRoundTrip() {
        overrides.set("q", 7)
        assertEquals("q", overrides.letter(7))
        assertEquals(mapOf(7L to "q"), overrides.all())

        // Blank means "back to automatic": the override is removed, and the
        // last removal drops the whole store key.
        overrides.set("  ", 7)
        assertNull(overrides.letter(7))
        assertNull(store.getString(BoxLetterOverrides.STORE_KEY))
    }

    /// Ports matron-apple's `testSanitizeKeepsOneGraphemeAsTyped`: the
    /// character is the user's pick — case preserved, emoji fine, but only
    /// the FIRST grapheme survives.
    @Test
    fun sanitizeKeepsOneGraphemeAsTyped() {
        assertEquals("m", BoxLetterOverrides.sanitize(" mz "))
        assertEquals("🦊", BoxLetterOverrides.sanitize("🦊box"))
        assertNull(BoxLetterOverrides.sanitize("   "))
        assertNull(BoxLetterOverrides.sanitize(""))
    }

    /// Ports matron-apple's `testSetPostsTheChangeNotification`: a settings
    /// edit writes no journal record, so the change flow is the only thing
    /// that can wake the summaries stream.
    @Test
    fun setEmitsOnTheChangeFlow() {
        assertEquals(emptyMap<Long, String>(), overrides.flow.value)
        overrides.set("x", 1)
        assertEquals(mapOf(1L to "x"), overrides.flow.value)
    }

    /// A second instance over the same store reads what the first wrote —
    /// the persistence half of the Apple round-trip (UserDefaults persisted
    /// implicitly; the KeyValueStore port must prove it).
    @Test
    fun overridesPersistAcrossInstances() {
        overrides.set("q", 7)
        assertEquals(mapOf(7L to "q"), BoxLetterOverrides(store).all())
    }

    /// Ports matron-apple's
    /// `testOverrideReplacesTheDerivedLetterWithoutShiftingOthers`.
    @Test
    fun overrideReplacesTheDerivedLetterWithoutShiftingOthers() {
        val names = mapOf(1L to "dev-y", 2L to "dev-z")
        val letters = SessionTag.boxLetters(names, overrides = mapOf(1L to "🦊"))
        assertEquals("🦊", letters[1L])
        // The neighbour still gets its common-prefix-stripped letter — the
        // override applies AFTER derivation.
        assertEquals("Z", letters[2L])

        // An override for a box not in the roster changes nothing.
        assertEquals(
            mapOf(1L to "Y", 2L to "Z"),
            SessionTag.boxLetters(names, overrides = mapOf(99L to "Q")),
        )
    }
}
