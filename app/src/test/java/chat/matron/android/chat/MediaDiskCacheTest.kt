package chat.matron.android.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaDiskCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000

    /// A clock the test moves; starts well past the epoch so file times are real.
    private var clock = 1_800_000_000_000L

    private fun cache(dir: File, byteLimit: Long = 1_000, maxEntryBytes: Long = 500) =
        MediaDiskCache(dir, byteLimit = byteLimit, maxEntryBytes = maxEntryBytes, now = { clock })

    private fun bytes(size: Int, fill: Int = 1) = ByteArray(size) { fill.toByte() }

    @Test fun readsBackAcrossInstancesUnderHashedNames() = runBlocking {
        val dir = temp.newFolder()
        cache(dir).store("hello".toByteArray(), "blob-1")
        assertArrayEquals("hello".toByteArray(), cache(dir).data("blob-1"))
        assertNull(cache(dir).data("blob-2"))
        val names = dir.list()!!.toList()
        assertEquals(listOf(MediaDiskCache.sha256("blob-1")), names)
        assertFalse(names.any { it.contains("blob") })
    }

    @Test fun anEntryExpiresSevenDaysAfterItWasLastUsed() = runBlocking {
        val dir = temp.newFolder()
        val c = cache(dir)
        c.store(bytes(10), "a")
        c.store(bytes(10), "b")
        clock += 6 * day
        // Reading renews "a"; "b" is left to age.
        assertTrue(c.data("a") != null)
        clock += 2 * day
        assertTrue("read 2 days ago", c.data("a") != null)
        assertNull("last used 8 days ago", c.data("b"))
        assertFalse(File(dir, MediaDiskCache.sha256("b")).exists())
    }

    @Test fun expiredEntriesAreSweptWhenTheCacheIsNextWritten() = runBlocking {
        val dir = temp.newFolder()
        cache(dir).store(bytes(10), "old")
        clock += 8 * day
        cache(dir).store(bytes(10), "new")
        assertEquals(listOf(MediaDiskCache.sha256("new")), dir.list()!!.toList())
    }

    @Test fun overTheLimitTheLeastRecentlyUsedGoFirst() = runBlocking {
        val dir = temp.newFolder()
        val c = cache(dir, byteLimit = 1_000)
        for (name in listOf("a", "b", "c", "d")) {
            c.store(bytes(300), name)
            clock += 1_000
        }
        // a, b, c fit (900); d pushes it to 1200, so eviction drops to <= 900.
        assertNull(c.data("a"))
        assertTrue(c.data("b") != null)
        assertTrue(c.data("d") != null)
        clock += 1_000
        c.data("b") // b is now the most recently used
        c.store(bytes(300), "e")
        assertNull("c was the least recently used", c.data("c"))
        assertTrue(c.data("b") != null)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 1_000)
    }

    @Test fun emptyAndOversizedBlobsAreNotStored() = runBlocking {
        val dir = temp.newFolder()
        val c = cache(dir, maxEntryBytes = 500)
        c.store(ByteArray(0), "empty")
        c.store(bytes(501), "big")
        assertNull(c.data("empty"))
        assertNull(c.data("big"))
    }

    @Test fun removeAllDeletesAndStopsLaterWrites() = runBlocking {
        val dir = temp.newFolder()
        val c = cache(dir)
        c.store(bytes(10), "a")
        c.removeAll()
        assertFalse(dir.exists())
        c.store(bytes(10), "b")
        assertFalse("a closed cache never writes again", dir.exists())
        assertNull(c.data("a"))
    }

    @Test fun eachAccountHasItsOwnHashedDirectory() {
        val root = temp.newFolder("media")
        val alice = MediaDiskCache.directory(root, "alice")
        val bob = MediaDiskCache.directory(root, "bob")
        assertNotEquals(alice, bob)
        assertEquals(root, alice.parentFile)
        assertFalse(alice.name.contains("alice"))
        alice.mkdirs(); bob.mkdirs()
        MediaDiskCache.removeAllOnDisk(root)
        assertFalse(root.exists())
    }
}
