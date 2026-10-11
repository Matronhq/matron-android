package chat.matron.android.models

import chat.matron.android.journal.db.storedItemWorking
import chat.matron.android.journal.db.storedItemWorkingJson
import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// An item's `working` state: whether the session that owns it is busy, as
/// the journal reports it on the item.
class ItemWorkingTest {
    private fun item(working: String?): TrackerItem? {
        val field = working?.let { ""","working":$it""" } ?: ""
        return TrackerItem.fromJson(
            parseJsonObjectOrNull(
                """{"id":"it_1","num":1,"kind":"question","state":"open","title":"A question","origin_convo_id":"c1",
                    "created_at":1000,"updated_at":2000$field}""",
            )!!,
        )
    }

    @Test
    fun aRunningStateParsesWithItsStart() {
        val item = item("""{"state":"running","since":5000,"until":null}""")
        assertEquals(ItemWorking(ItemWorking.State.RUNNING, since = Instant.ofEpochMilli(5_000)), item?.working)
    }

    @Test
    fun aQueuedStateParsesWithWhenItLapses() {
        val item = item("""{"state":"queued","since":5000,"until":605000}""")
        assertEquals(
            ItemWorking(ItemWorking.State.QUEUED, Instant.ofEpochMilli(5_000), Instant.ofEpochMilli(605_000)),
            item?.working,
        )
    }

    @Test
    fun nothingToShowIsNull() {
        assertNotNull(item("null")); assertNull(item("null")?.working)
        assertNotNull(item(null)); assertNull("a journal that predates the field", item(null)?.working)
        assertNull("a state this build does not know draws nothing", item("""{"state":"paused"}""")?.working)
    }

    @Test
    fun aQueuedStateStopsBeingCurrentAtItsDeadline() {
        val until = Instant.ofEpochMilli(600_000)
        val queued = ItemWorking(ItemWorking.State.QUEUED, Instant.EPOCH, until)
        assertTrue(queued.isCurrent(until.minusMillis(1)))
        assertFalse(queued.isCurrent(until))
        assertTrue("a running state has no deadline", ItemWorking(ItemWorking.State.RUNNING).isCurrent(Instant.MAX))
    }

    @Test
    fun theStoredFormRoundTrips() {
        val queued = ItemWorking(ItemWorking.State.QUEUED, Instant.ofEpochMilli(5_000), Instant.ofEpochMilli(605_000))
        assertEquals(queued, ItemWorking.fromJson(queued.toJson()))
        val running = ItemWorking(ItemWorking.State.RUNNING)
        assertEquals(running, ItemWorking.fromJson(running.toJson()))
    }

    /// A state fetched by an earlier launch says nothing about now.
    @Test
    fun aStateLeftByAnEarlierLaunchReadsAsNone() {
        val running = ItemWorking(ItemWorking.State.RUNNING, since = Instant.ofEpochMilli(5_000))
        assertEquals(running, storedItemWorking(storedItemWorkingJson(running)))
        assertNull(storedItemWorking(storedItemWorkingJson(running, launch = "an-earlier-launch")))
        assertNull(storedItemWorking("not json"))
    }

    @Test
    fun theIndicatorNamesTheTwoStates() {
        assertEquals("Starting…", ItemWorkingIndicator.STARTING.label)
        assertEquals("Working…", ItemWorkingIndicator.WORKING.label)
    }
}
