package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/// Ported from matron-apple's `ComposerDraftMemoryTests`: per-room composer
/// draft cache — store, retrieve, forget, multi-room isolation, trailing
/// whitespace preservation, empty-clears.
class ComposerDraftMemoryTest {

    @Before
    fun setUp() {
        ComposerDraftMemory.resetForTesting()
    }

    @Test
    fun retrieve_returnsNull_whenNothingStored() {
        assertNull(ComposerDraftMemory.retrieve("!unknown:s"))
    }

    @Test
    fun store_thenRetrieve_returnsExactValue() {
        ComposerDraftMemory.store("!a:s", "half-typed message")
        assertEquals("half-typed message", ComposerDraftMemory.retrieve("!a:s"))
    }

    @Test
    fun store_preservesTrailingWhitespace() {
        ComposerDraftMemory.store("!a:s", "/start ")
        assertEquals("/start ", ComposerDraftMemory.retrieve("!a:s"))
    }

    @Test
    fun store_emptyString_clearsEntry() {
        ComposerDraftMemory.store("!a:s", "draft")
        ComposerDraftMemory.store("!a:s", "")
        assertNull(ComposerDraftMemory.retrieve("!a:s"))
    }

    @Test
    fun forget_dropsSingleRoom() {
        ComposerDraftMemory.store("!a:s", "draft A")
        ComposerDraftMemory.store("!b:s", "draft B")
        ComposerDraftMemory.forget("!a:s")
        assertNull(ComposerDraftMemory.retrieve("!a:s"))
        assertEquals("draft B", ComposerDraftMemory.retrieve("!b:s"))
    }

    @Test
    fun drafts_areIsolatedPerRoom() {
        ComposerDraftMemory.store("!a:s", "AAA")
        ComposerDraftMemory.store("!b:s", "BBB")
        assertEquals("AAA", ComposerDraftMemory.retrieve("!a:s"))
        assertEquals("BBB", ComposerDraftMemory.retrieve("!b:s"))
    }

    @Test
    fun store_overwritesPriorValue() {
        ComposerDraftMemory.store("!a:s", "first")
        ComposerDraftMemory.store("!a:s", "second")
        assertEquals("second", ComposerDraftMemory.retrieve("!a:s"))
    }
}
