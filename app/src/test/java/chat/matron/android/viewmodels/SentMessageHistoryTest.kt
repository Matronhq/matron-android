package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from the model half of matron-apple's `SentMessageHistoryTests`:
/// record (+ cap + consecutive-dedupe), Up/Down walk, draft stash/restore,
/// per-room isolation, cancel/endRecall. The `ComposerViewModel`-wiring half of
/// the Swift file lives in `ComposerViewModelTest` (compile-order: the VM is
/// ported after this model).
class SentMessageHistoryTest {

    @Test
    fun record_appendsAndRecallsMostRecentFirst() {
        val history = SentMessageHistory()
        history.record("first", "!a")
        history.record("second", "!a")
        assertEquals("second", history.recallOlder("!a", ""))
        assertEquals("first", history.recallOlder("!a", ""))
        assertNull(history.recallOlder("!a", ""))
    }

    @Test
    fun record_dedupesConsecutiveDuplicates() {
        val history = SentMessageHistory()
        history.record("hi", "!a")
        history.record("hi", "!a") // consecutive dup — collapsed
        history.record("bye", "!a")
        history.record("hi", "!a") // non-consecutive — kept

        assertEquals("hi", history.recallOlder("!a", ""))
        assertEquals("bye", history.recallOlder("!a", ""))
        assertEquals("hi", history.recallOlder("!a", ""))
        assertNull(history.recallOlder("!a", ""))
    }

    @Test
    fun record_capsAtFiftyDroppingOldest() {
        val history = SentMessageHistory()
        for (i in 1..60) history.record("msg$i", "!a")
        val recalled = mutableListOf<String>()
        while (true) {
            val text = history.recallOlder("!a", "") ?: break
            recalled.add(text)
        }
        assertEquals(50, recalled.size)
        assertEquals("msg60", recalled.first())
        assertEquals("msg11", recalled.last())
    }

    @Test
    fun recallNewer_walksForwardThenRestoresStashedDraft() {
        val history = SentMessageHistory()
        history.record("one", "!a")
        history.record("two", "!a")

        assertEquals("two", history.recallOlder("!a", "draft"))
        assertEquals("one", history.recallOlder("!a", "draft"))
        assertTrue(history.isNavigating)

        assertEquals("two", history.recallNewer("!a"))
        assertEquals("draft", history.recallNewer("!a"))
        assertFalse(history.isNavigating)
        assertNull(history.recallNewer("!a"))
    }

    @Test
    fun recallOlder_returnsNull_forEmptyHistory() {
        val history = SentMessageHistory()
        assertNull(history.recallOlder("!a", "draft"))
        assertFalse(history.isNavigating)
    }

    @Test
    fun history_isIsolatedPerRoom() {
        val history = SentMessageHistory()
        history.record("A-only", "!a")
        history.record("B-only", "!b")

        assertEquals("A-only", history.recallOlder("!a", ""))
        assertNull(history.recallOlder("!a", ""))
        assertEquals("B-only", history.recallOlder("!b", ""))
    }

    @Test
    fun endRecall_isIdempotentAndResetsWalk() {
        val history = SentMessageHistory()
        history.record("x", "!a")
        history.recallOlder("!a", "d")
        assertTrue(history.isNavigating)
        history.endRecall()
        assertFalse(history.isNavigating)
        history.endRecall() // idempotent
        assertFalse(history.isNavigating)
    }

    @Test
    fun cancelRecall_returnsStashedDraft_andEndsWalk() {
        val history = SentMessageHistory()
        history.record("sent", "!a")
        assertEquals("sent", history.recallOlder("!a", "half-typed"))

        assertEquals("half-typed", history.cancelRecall())
        assertFalse(history.isNavigating)
        assertNull(history.cancelRecall())
    }
}
