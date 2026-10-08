package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `BoxCapacityCacheTests` (#164).
class BoxCapacityCacheTest {
    private val store = InMemoryKeyValueStore()
    private val capturedAt = 1_754_900_000_000L

    private fun makeCache(userID: String = "@bob:matron.chat") = KeyValueBoxCapacityCache(userID, store)

    private fun fullCapacity() = BoxCapacity(
        liveSessions = 2,
        limitLines = listOf(
            LimitLine("session", "Current session", 39, 1_754_936_000_000L),
            LimitLine("week", "Current week (all models)", 85, null),
        ),
        accountEmail = "bob@example.com",
    )

    @Test fun loadAll_isEmptyBeforeAnythingIsSaved() = assertTrue(makeCache().loadAll().isEmpty())

    @Test
    fun save_roundTripsEveryFieldIncludingCaptureTime() {
        val cache = makeCache()
        cache.save(fullCapacity(), 7, capturedAt)
        val entry = cache.loadAll()[7]
        assertEquals(fullCapacity(), entry?.capacity)
        assertEquals(capturedAt, entry?.capturedAtMs)
    }

    @Test
    fun save_keysPerBox_andOverwritesTheSameBox() {
        val cache = makeCache()
        cache.save(fullCapacity(), 7, capturedAt)
        cache.save(BoxCapacity(0, emptyList(), "b@x.com"), 8, capturedAt)
        cache.save(BoxCapacity(5, emptyList(), null), 7, capturedAt + 60_000)
        assertEquals(2, cache.loadAll().size)
        assertEquals(5, cache.loadAll()[7]?.capacity?.liveSessions)
        assertEquals(capturedAt + 60_000, cache.loadAll()[7]?.capturedAtMs)
        assertEquals("b@x.com", cache.loadAll()[8]?.capacity?.accountEmail)
    }

    @Test
    fun prune_dropsBoxesOutsideTheGivenRoster() {
        val cache = makeCache()
        for (id in listOf(1L, 2L, 3L)) cache.save(BoxCapacity(id.toInt(), emptyList(), null), id, capturedAt)
        cache.prune(keeping = setOf(1, 3))
        assertEquals(setOf(1L, 3L), cache.loadAll().keys)
    }

    @Test
    fun persistsAcrossCacheInstances_viaSharedStore() {
        makeCache().save(fullCapacity(), 7, capturedAt)
        assertEquals(fullCapacity(), KeyValueBoxCapacityCache("@bob:matron.chat", store).loadAll()[7]?.capacity)
    }

    @Test
    fun entriesAreScopedToTheirUser() {
        val bob = makeCache("@bob:matron.chat")
        val carol = makeCache("@carol:matron.chat")
        bob.save(fullCapacity(), 1, capturedAt)
        assertNull("another account's box 1 is not this account's box 1", carol.loadAll()[1])
        carol.save(BoxCapacity(0, emptyList(), "carol@example.com"), 1, capturedAt)
        assertEquals("bob@example.com", bob.loadAll()[1]?.capacity?.accountEmail)
        assertEquals("carol@example.com", carol.loadAll()[1]?.capacity?.accountEmail)
    }

    @Test
    fun removeAll_dropsOnlyThatUsersEntries() {
        val bob = makeCache("@bob:matron.chat")
        val carol = makeCache("@carol:matron.chat")
        bob.save(fullCapacity(), 1, capturedAt)
        carol.save(fullCapacity(), 1, capturedAt)
        KeyValueBoxCapacityCache.removeAll("@bob:matron.chat", store)
        assertTrue(bob.loadAll().isEmpty())
        assertNotNull(carol.loadAll()[1])
    }

    @Test
    fun loadAll_degradesToEmptyOnUnreadablePayload() {
        val cache = makeCache()
        cache.save(fullCapacity(), 7, capturedAt)
        store.setString(KeyValueBoxCapacityCache.defaultsKey("@bob:matron.chat"), "not json")
        assertTrue(cache.loadAll().isEmpty())
        cache.save(fullCapacity(), 7, capturedAt)
        assertEquals(1, cache.loadAll().size)
    }
}
