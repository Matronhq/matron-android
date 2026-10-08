package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/// Ported from matron-apple's `ChatScrollPositionMemoryTests`: per-room
/// scroll-position cache — store, retrieve, forget, multi-room isolation,
/// nil/transient clearing.
class ChatScrollPositionMemoryTest {

    @Before
    fun setUp() {
        ChatScrollPositionMemory.resetForTesting()
    }

    @Test
    fun retrieve_returnsNull_whenNothingStored() {
        assertNull(ChatScrollPositionMemory.retrieve("!unknown:s"))
    }

    @Test
    fun store_thenRetrieve_returnsExactValue() {
        ChatScrollPositionMemory.store("!a:s", "\$ev1")
        assertEquals("\$ev1", ChatScrollPositionMemory.retrieve("!a:s"))
    }

    @Test
    fun store_null_clearsEntry() {
        ChatScrollPositionMemory.store("!a:s", "\$ev1")
        ChatScrollPositionMemory.store("!a:s", null)
        assertNull(ChatScrollPositionMemory.retrieve("!a:s"))
    }

    @Test
    fun store_transientId_clearsEntry() {
        // A transient anchor (echo/activity/eph) means the user was at the live
        // tail; storing it would pin the next open to a vanished row.
        ChatScrollPositionMemory.store("!a:s", "\$ev1")
        ChatScrollPositionMemory.store("!a:s", "echo:123")
        assertNull(ChatScrollPositionMemory.retrieve("!a:s"))
    }

    @Test
    fun forget_dropsSingleRoom() {
        ChatScrollPositionMemory.store("!a:s", "\$ev1")
        ChatScrollPositionMemory.store("!b:s", "\$ev2")
        ChatScrollPositionMemory.forget("!a:s")
        assertNull(ChatScrollPositionMemory.retrieve("!a:s"))
        assertEquals("\$ev2", ChatScrollPositionMemory.retrieve("!b:s"))
    }

    @Test
    fun positions_areIsolatedPerRoom() {
        ChatScrollPositionMemory.store("!a:s", "\$ev-a")
        ChatScrollPositionMemory.store("!b:s", "\$ev-b")
        assertEquals("\$ev-a", ChatScrollPositionMemory.retrieve("!a:s"))
        assertEquals("\$ev-b", ChatScrollPositionMemory.retrieve("!b:s"))
    }

    @Test
    fun store_overwritesPriorValue() {
        ChatScrollPositionMemory.store("!a:s", "\$first")
        ChatScrollPositionMemory.store("!a:s", "\$second")
        assertEquals("\$second", ChatScrollPositionMemory.retrieve("!a:s"))
    }

    @Test
    fun forget_isIdempotent_whenRoomAbsent() {
        ChatScrollPositionMemory.forget("!unknown:s")
        assertNull(ChatScrollPositionMemory.retrieve("!unknown:s"))
    }
}
