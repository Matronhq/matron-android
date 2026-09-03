package chat.matron.android.chat

import chat.matron.android.viewmodels.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
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

    // MARK: - legacy claim + migration (apple #158)

    @Test
    fun claim_firstAccountOwnsTheRelics_untilTheLastIsRemoved() {
        assertFalse("nothing to claim", overrides.claim("@pat:s"))
        overrides.set("Q", 1)
        assertTrue(overrides.claim("@pat:s"))
        assertFalse("another account on the same install must not touch them", overrides.claim("@sam:s"))
        assertTrue(overrides.claim("@pat:s"))
        overrides.remove(1)
        assertNull(store.getString(BoxLetterOverrides.OWNER_KEY))
        assertFalse(overrides.claim("@pat:s"))
    }

    @Test
    fun migration_pushesOnlyWhereTheJournalHasNoTag() = runBlocking {
        overrides.set("Q", 1) // journal has none → push
        overrides.set("Z", 2) // journal already tagged → drop without pushing
        overrides.set("N", 9) // revoked box → drop
        val pushed = mutableListOf<Pair<Long, String>>()
        val result = BoxLetterMigration.run(overrides, mapOf(1L to null, 2L to "X")) { id, letter -> pushed.add(id to letter) }
        assertEquals(listOf(1L to "Q"), pushed)
        assertEquals(mapOf(1L to "Q"), result)
        assertTrue("every entry is consumed", overrides.all().isEmpty())
    }

    @Test
    fun migration_keepsAnEntryWhosePushFailed() = runBlocking {
        overrides.set("Q", 1)
        BoxLetterMigration.run(overrides, mapOf(1L to null)) { _, _ -> throw RuntimeException("offline") }
        assertEquals("the next launch retries", mapOf(1L to "Q"), overrides.all())
    }
}
