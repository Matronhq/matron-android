package chat.matron.android.search

import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple's `SearchSchemaTests`, adapted to the Android
/// single-FTS4-table schema (no `messages` content table + triggers — see
/// [MessageFtsEntity]). Exercises table creation and FTS insert/query/delete.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SearchSchemaTest {
    private lateinit var db: SearchDatabase
    private lateinit var svc: SearchServiceLive

    @Before
    fun setUp() {
        db = SearchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        svc = SearchServiceLive(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun tableExists(name: String): Boolean {
        db.query("SELECT 1 FROM sqlite_master WHERE name = ?", arrayOf(name)).use { c ->
            return c.moveToFirst()
        }
    }

    @Test
    fun migrationCreatesTables() {
        assertEquals(true, tableExists("messages_fts"))
        assertEquals(true, tableExists("indexed_rooms"))
    }

    @Test
    fun canInsertAndQueryFTS() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.ofEpochSecond(1745000000), "the quick brown fox jumps over the lazy dog")
        assertEquals(1, svc.query("fox", 10).size)
    }

    @Test
    fun deleteRemovesFromFTS() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.ofEpochSecond(1745000000), "secret payload")
        svc.remove("e1")
        assertEquals(0, svc.query("secret", 10).size)
    }
}
