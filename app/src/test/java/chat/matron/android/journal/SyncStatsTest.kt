package chat.matron.android.journal

import chat.matron.android.viewmodels.InMemoryKeyValueStore
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatsTest {
    private val day = 86_400_000L
    private var now = 20_000 * day + 3_600_000 // 1970-01-01 + 20000 days, 01:00 UTC

    private fun recorder(store: InMemoryKeyValueStore) =
        SyncStatsRecorder(store, zone = { ZoneOffset.UTC }, nowMs = { now }, flushIntervalMs = 5_000)

    @Test
    fun summaryShowsWakesEvenAtZero() {
        assertEquals("0 events · 0 background wakes", SyncDayStats("2026-11-22").summary(Locale.US))
        assertEquals(
            "1,204 events · 3 catch-ups, 0.4 s avg · 2 opened · 1 background wake",
            SyncDayStats("2026-11-22", 1204, 3, 1.2, 2, 1).summary(Locale.US),
        )
        assertEquals("5 events · 1 catch-up, 2.0 s avg · 2 background wakes",
            SyncDayStats("d", 5, 1, 2.0, 0, 2).summary(Locale.US))
    }

    @Test
    fun aBackgroundWakeIsWrittenAtOnce() {
        val store = InMemoryKeyValueStore()
        val stats = recorder(store)
        stats.noteEvents(1) // first note flushes (nothing written before)
        now += 1_000
        stats.noteBackgroundWake()
        val reread = recorder(store).snapshot().first
        assertEquals(1, reread.backgroundWakes)
    }

    @Test
    fun eventsAreBatchedUntilTheIntervalOrAFlush() {
        val store = InMemoryKeyValueStore()
        val stats = recorder(store)
        stats.noteEvents(1)
        now += 1_000
        stats.noteEvents(10)
        assertEquals(1L, recorder(store).snapshot().first.events)
        assertEquals(11L, stats.snapshot().first.events)
        stats.flush()
        assertEquals(11L, recorder(store).snapshot().first.events)
        now += 6_000
        stats.noteEvents(1)
        assertEquals(12L, recorder(store).snapshot().first.events)
    }

    @Test
    fun onlyTodayAndYesterdayAreKept() {
        val store = InMemoryKeyValueStore()
        val stats = recorder(store)
        stats.noteConversationFetch()
        now += day
        stats.noteCatchUp(1.5)
        val (today, yesterday) = stats.snapshot()
        assertEquals(1, today.catchUps)
        assertEquals(1, yesterday!!.conversationFetches)
        now += 2 * day
        val (later, none) = stats.snapshot()
        assertEquals(0, later.catchUps)
        assertNull(none)
        stats.noteEvents(2)
        stats.flush()
        assertTrue(store.getString(SyncStatsRecorder.KEY)!!.split("\"day\"").size == 2)
    }

    @Test
    fun aStoredDayWithoutNewerFieldsStillLoads() {
        val store = InMemoryKeyValueStore()
        val today = recorder(store).snapshot().first.day
        store.setString(SyncStatsRecorder.KEY, """[{"day":"$today","events":7}]""")
        val loaded = recorder(store).snapshot().first
        assertEquals(7L, loaded.events)
        assertEquals(0, loaded.backgroundWakes)
    }

    /// Notes from several threads at once: the last write holds every count.
    @Test
    fun concurrentNotesAreNeverLostFromTheStore() {
        val store = InMemoryKeyValueStore()
        val stats = SyncStatsRecorder(store, zone = { ZoneOffset.UTC }, nowMs = { now }, flushIntervalMs = 0)
        val threads = (1..8).map { Thread { repeat(200) { stats.noteBackgroundWake(); stats.noteEvents(1) } } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        stats.flush()
        val reread = recorder(store).snapshot().first
        assertEquals(1600, reread.backgroundWakes)
        assertEquals(1600L, reread.events)
    }
}
