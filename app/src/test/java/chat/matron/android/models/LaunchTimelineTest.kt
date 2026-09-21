package chat.matron.android.models

import chat.matron.android.viewmodels.InMemoryKeyValueStore
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ordering, durations and persistence. Deliberately no trace assertions:
/// the durations these tests pin are the same numbers the trace sections
/// carry. Ported from matron-apple's `LaunchTimelineTests`. Robolectric only
/// because the marks emit `android.os.Trace` sections.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LaunchTimelineTest {
    private val start = 100_000L

    /// A timeline whose clock is a script: each read returns the next value,
    /// and the last value repeats.
    private fun makeTimeline(offsetsMs: List<Long>): Pair<LaunchTimeline, InMemoryKeyValueStore> {
        val store = InMemoryKeyValueStore()
        val values = ArrayDeque(offsetsMs.map { start + it })
        val clock = { if (values.size > 1) values.removeFirst() else values.first() }
        return LaunchTimeline(clock = clock, processStart = start, store = store, wallClock = { 1_700_000_000_000 }) to store
    }

    @Test
    fun durationsAreStoreOpenElapsedAndTheRestLaunchRelative() {
        val (timeline, _) = makeTimeline(listOf(200, 2100, 2400, 6100))
        timeline.beginStoreOpen()                          // t = 0.2
        timeline.endStoreOpen()                            // t = 2.1 → storeOpen 1.9
        timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT) // t = 2.4 since process start
        timeline.mark(LaunchTimeline.Mark.CATCH_UP_COMPLETE) // t = 6.1
        val record = timeline.record
        assertEquals(1900L, record.storeOpenMs)
        assertEquals(2400L, record.firstListPaintMs)
        assertEquals(6100L, record.catchUpCompleteMs)
        assertNull("no migration ran", record.migrationMs)
    }

    /// The database layer measures its own migration and hands back the
    /// millis; the composition root records it inside the store-open pair.
    @Test
    fun migrationIsRecordedInsideStoreOpen() {
        val (timeline, _) = makeTimeline(listOf(0, 3500))
        timeline.beginStoreOpen()
        timeline.endStoreOpen()
        timeline.recordMigration(3200)
        assertEquals(3200L, timeline.record.migrationMs)
        assertEquals(3500L, timeline.record.storeOpenMs)
    }

    @Test
    fun theFirstMarkWins() {
        val (timeline, _) = makeTimeline(listOf(2000, 9000))
        timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT)
        timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT)
        assertEquals("a re-appearing list must not overwrite the launch number", 2000L, timeline.record.firstListPaintMs)
    }

    @Test
    fun anUnmatchedEndIsIgnored() {
        val (timeline, _) = makeTimeline(listOf(1000))
        timeline.endStoreOpen()
        assertNull(timeline.record.storeOpenMs)
        assertNull(timeline.record.migrationMs)
    }

    @Test
    fun theRecordRoundTripsThroughTheStore() {
        val (timeline, store) = makeTimeline(listOf(0, 1900, 2400, 6100))
        timeline.beginStoreOpen()
        timeline.endStoreOpen()
        timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT)
        timeline.mark(LaunchTimeline.Mark.CATCH_UP_COMPLETE)
        val restored = LaunchTimeline.currentLaunch(store)
        assertNotNull(restored)
        assertEquals(1900L, restored!!.storeOpenMs)
        assertEquals(6100L, restored.catchUpCompleteMs)
        assertNotNull("the key the Settings row reads", store.getString("launch.last"))
        assertEquals(timeline.record, restored)
    }

    @Test
    fun attachingAStoreLaterPersistsWhatWasAlreadyRecorded() {
        val values = ArrayDeque(listOf(start + 500, start + 500))
        val timeline = LaunchTimeline(clock = { values.first() }, processStart = start, store = null, wallClock = { 1 })
        timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT)
        val store = InMemoryKeyValueStore()
        assertNull(LaunchTimeline.currentLaunch(store))
        timeline.attachStore(store)
        assertEquals(500L, LaunchTimeline.currentLaunch(store)?.firstListPaintMs)
    }

    @Test
    fun summaryReadsLikeTheSpecExample() {
        val record = LaunchRecord(storeOpenMs = 1900, firstListPaintMs = 2400, catchUpCompleteMs = 6100, recordedAt = 0)
        assertEquals("store 1.9 s · first list 2.4 s · catch-up 6.1 s", LaunchTimeline.summary(record))
    }

    @Test
    fun summaryAppendsMigrationWhenOneRan() {
        val record = LaunchRecord(
            storeOpenMs = 4000, migrationMs = 3200, firstListPaintMs = 4400, catchUpCompleteMs = 8000, recordedAt = 0,
        )
        assertEquals("store 4.0 s · first list 4.4 s · catch-up 8.0 s · migration 3.2 s", LaunchTimeline.summary(record))
    }

    @Test
    fun summaryOmitsMarksThatNeverLandedAndHandlesNoRecord() {
        assertEquals("store 0.4 s", LaunchTimeline.summary(LaunchRecord(storeOpenMs = 400, recordedAt = 0)))
        assertEquals("—", LaunchTimeline.summary(null))
        assertEquals("—", LaunchTimeline.summary(LaunchRecord(recordedAt = 0)))
    }

    /// Persisting while still holding the lock makes the write atomic with
    /// the mutation, so two concurrent marks can never leave the persisted
    /// record missing a field that already landed in memory.
    @Test
    fun concurrentMarksNeverDropAFieldFromThePersistedRecord() {
        repeat(100) { iteration ->
            val store = InMemoryKeyValueStore()
            val timeline = LaunchTimeline(clock = { 5 }, processStart = 0, store = store, wallClock = { 0 })
            val go = CountDownLatch(1)
            val t1 = thread { go.await(); timeline.mark(LaunchTimeline.Mark.FIRST_LIST_PAINT) }
            val t2 = thread { go.await(); timeline.mark(LaunchTimeline.Mark.CATCH_UP_COMPLETE) }
            go.countDown()
            t1.join(); t2.join()
            val persisted = LaunchTimeline.currentLaunch(store)
            assertNotNull("iteration $iteration: nothing was persisted", persisted)
            assertNotNull("iteration $iteration: firstListPaint dropped", persisted!!.firstListPaintMs)
            assertNotNull("iteration $iteration: catchUpComplete dropped", persisted.catchUpCompleteMs)
            assertEquals(timeline.record, persisted)
        }
    }

    /// A second core build in one process (sign-out → sign-in) must not
    /// replace this launch's store-open timing with the second session's.
    @Test
    fun aSecondStoreOpenCycleDoesNotOverwriteTheFirst() {
        val (timeline, _) = makeTimeline(listOf(0, 2000, 5000, 9000))
        timeline.beginStoreOpen()
        timeline.endStoreOpen()
        timeline.beginStoreOpen() // must no-op: storeOpen already recorded
        timeline.endStoreOpen()   // must no-op: storeOpenBegan never set
        assertEquals(2000L, timeline.record.storeOpenMs)
    }

    @Test
    fun recordMigrationFirstWins() {
        val (timeline, _) = makeTimeline(listOf(0))
        timeline.recordMigration(1000)
        timeline.recordMigration(5000)
        assertEquals(1000L, timeline.record.migrationMs)
    }
}
