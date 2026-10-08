package chat.matron.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple LRUCacheTests: eviction + recency invariants.
class LRUCacheTest {

    @Test
    fun positiveLimitConstructsCleanly() {
        val cache = LRUCache<String, Int>(limit = 1)
        assertEquals(0, cache.count)
        assertFalse(cache.contains("anything"))
    }

    @Test
    fun setNewKeyGrowsCountUntilLimit() {
        val cache = LRUCache<String, Int>(limit = 3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        assertEquals(3, cache.count)
        assertTrue(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
    }

    @Test
    fun setBeyondLimitEvictsLeastRecentlyUsed() {
        val cache = LRUCache<String, Int>(limit = 3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        cache["d"] = 4
        assertEquals(3, cache.count)
        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
        assertTrue(cache.contains("d"))
    }

    @Test
    fun getDoesNotTouchRecency() {
        val cache = LRUCache<String, Int>(limit = 3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        // Reads must NOT promote "a".
        cache["a"]
        cache["d"] = 4
        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
        assertTrue(cache.contains("d"))
    }

    @Test
    fun setExistingKeyUpdatesValueAndTouchesRecency() {
        val cache = LRUCache<String, Int>(limit = 3)
        cache["a"] = 1
        cache["b"] = 2
        cache["c"] = 3
        cache["a"] = 99
        assertEquals(99, cache["a"])
        cache["d"] = 4
        assertTrue(cache.contains("a"))
        assertFalse(cache.contains("b"))
    }

    @Test
    fun setNullRemovesKeyAndShrinksCount() {
        val cache = LRUCache<String, Int>(limit = 3)
        cache["a"] = 1
        cache["b"] = 2
        cache["a"] = null
        assertEquals(1, cache.count)
        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        cache["a"] = 10
        assertEquals(2, cache.count)
        assertEquals(10, cache["a"])
    }

    @Test
    fun getMissingKeyReturnsNullAndDoesNotMutateRecency() {
        val cache = LRUCache<String, Int>(limit = 2)
        cache["a"] = 1
        cache["b"] = 2
        assertNull(cache["missing"])
        cache["c"] = 3
        assertFalse(cache.contains("a"))
        assertTrue(cache.contains("b"))
        assertTrue(cache.contains("c"))
    }

    @Test
    fun timelineCacheKeyEqualityAndHashing() {
        val a = TimelineCacheKey(userID = "@u:s", roomID = "!r1:s")
        val b = TimelineCacheKey(userID = "@u:s", roomID = "!r1:s")
        val c = TimelineCacheKey(userID = "@u:s", roomID = "!r2:s")
        assertEquals(a, b)
        assertFalse(a == c)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
