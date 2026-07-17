package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `RecentStartFoldersTests`: record
/// (trim / case-insensitive dedupe / cap / order), prefix matching, and
/// persistence via a shared store. The throwaway `UserDefaults(suiteName:)` maps
/// to a fresh [InMemoryKeyValueStore] per test.
class RecentStartFoldersTest {

    private fun makeStore(kv: KeyValueStore = InMemoryKeyValueStore()) = RecentStartFolders(kv)

    @Test
    fun record_thenMatch_returnsRecordedFolder() {
        val store = makeStore()
        store.record("~/yearbook-app")
        assertEquals(listOf("~/yearbook-app"), store.matches("~/y"))
    }

    @Test
    fun record_trimsWhitespace_andIgnoresEmpty() {
        val store = makeStore()
        store.record("   ")
        store.record("\t\n")
        assertTrue(store.matches("").isEmpty())

        store.record("  ~/spaced  ")
        assertEquals(listOf("~/spaced"), store.matches(""))
    }

    @Test
    fun record_dedupesCaseInsensitively_movingToFront() {
        val store = makeStore()
        store.record("~/Alpha")
        store.record("~/beta")
        store.record("~/alpha") // case-insensitive dup of ~/Alpha

        assertEquals(listOf("~/alpha", "~/beta"), store.matches(""))
    }

    @Test
    fun record_capsAtFifteen_droppingOldest() {
        val store = makeStore()
        for (i in 1..20) store.record("~/dir$i")
        val all = store.matches("")
        assertEquals(15, all.size)
        assertEquals("~/dir20", all.first())
        assertEquals("~/dir6", all.last())
    }

    @Test
    fun matches_isCaseInsensitivePrefix() {
        val store = makeStore()
        store.record("~/Projects/App")
        assertEquals(listOf("~/Projects/App"), store.matches("~/pro"))
        assertTrue(store.matches("~/z").isEmpty())
    }

    @Test
    fun matches_emptyPrefix_returnsFullListMostRecentFirst() {
        val store = makeStore()
        store.record("~/one")
        store.record("~/two")
        store.record("~/three")
        assertEquals(listOf("~/three", "~/two", "~/one"), store.matches(""))
    }

    @Test
    fun persistsAcrossStoreInstances_viaSharedStore() {
        val kv = InMemoryKeyValueStore()
        makeStore(kv).record("~/persisted")
        val reopened = RecentStartFolders(kv)
        assertEquals(listOf("~/persisted"), reopened.matches("~/p"))
    }
}
