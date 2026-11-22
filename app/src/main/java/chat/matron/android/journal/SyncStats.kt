package chat.matron.android.journal

import chat.matron.android.viewmodels.KeyValueStore
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/// One day's sync work on this device, shown under Settings › Sync on this
/// device so the two sync scopes can be compared on a real phone.
@Serializable
data class SyncDayStats(
    /// The local date, `yyyy-MM-dd`.
    val day: String,
    /// Journal events this device received and stored.
    val events: Long = 0,
    /// Reconnects that had a backlog to replay, and how long they took.
    val catchUps: Int = 0,
    val catchUpSeconds: Double = 0.0,
    /// Chats fetched because they were opened ("For you only").
    val conversationFetches: Int = 0,
    /// Times the system woke the app in the background to catch up.
    val backgroundWakes: Int = 0,
) {
    /// One line: "1,204 events · 3 catch-ups, 0.4 s avg · 2 opened · 5
    /// background wakes". The wakes are shown at zero too: none at all is
    /// the thing that count is there to reveal.
    fun summary(locale: Locale = Locale.getDefault()): String {
        val parts = mutableListOf("${NumberFormat.getIntegerInstance(locale).format(events)} events")
        if (catchUps > 0) {
            val average = String.format(locale, "%.1f", catchUpSeconds / catchUps)
            parts += "$catchUps catch-up${if (catchUps == 1) "" else "s"}, $average s avg"
        }
        if (conversationFetches > 0) parts += "$conversationFetches opened"
        parts += if (backgroundWakes == 1) "1 background wake" else "$backgroundWakes background wakes"
        return parts.joinToString(" · ")
    }
}

/// Counts this device's sync work per day (today and yesterday are kept).
///
/// Counts are held in memory and written at most every [flushIntervalMs],
/// so a busy socket does not write the preferences file per frame. A
/// background wake is written at once, because the system may stop the
/// process seconds later; [flush] writes whatever is pending.
class SyncStatsRecorder(
    private val store: KeyValueStore,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val flushIntervalMs: Long = 5_000,
) {
    private val lock = Any()
    private var days: List<SyncDayStats> = load()
    private var dirty = false
    private var lastFlushAt = 0L

    fun noteEvents(count: Int) {
        if (count <= 0) return
        update(immediate = false) { it.copy(events = it.events + count) }
    }

    fun noteCatchUp(seconds: Double) =
        update(immediate = false) { it.copy(catchUps = it.catchUps + 1, catchUpSeconds = it.catchUpSeconds + seconds) }

    fun noteConversationFetch() =
        update(immediate = false) { it.copy(conversationFetches = it.conversationFetches + 1) }

    fun noteBackgroundWake() =
        update(immediate = true) { it.copy(backgroundWakes = it.backgroundWakes + 1) }

    /// Today (zero when nothing happened yet) and yesterday (`null` when
    /// nothing was recorded that day).
    fun snapshot(): Pair<SyncDayStats, SyncDayStats?> = synchronized(lock) {
        val today = today()
        val yesterday = LocalDate.parse(today).minusDays(1).toString()
        (days.firstOrNull { it.day == today } ?: SyncDayStats(today)) to days.firstOrNull { it.day == yesterday }
    }

    fun flush() {
        val toWrite = synchronized(lock) {
            if (!dirty) return
            dirty = false
            lastFlushAt = nowMs()
            days
        }
        store.setString(KEY, MatronJson.encodeToString(ListSerializer(SyncDayStats.serializer()), toWrite))
    }

    private fun update(immediate: Boolean, change: (SyncDayStats) -> SyncDayStats) {
        val due = synchronized(lock) {
            val today = today()
            val yesterday = LocalDate.parse(today).minusDays(1).toString()
            val current = days.firstOrNull { it.day == today } ?: SyncDayStats(today)
            days = listOf(change(current)) + days.filter { it.day == yesterday }
            dirty = true
            immediate || nowMs() - lastFlushAt >= flushIntervalMs
        }
        if (due) flush()
    }

    private fun today(): String = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs()), zone()).toString()

    private fun load(): List<SyncDayStats> = store.getString(KEY)?.let { raw ->
        runCatching { MatronJson.decodeFromString(ListSerializer(SyncDayStats.serializer()), raw) }.getOrNull()
    } ?: emptyList()

    companion object {
        /// Per device, like the scope itself.
        const val KEY = "sync.stats"
    }
}
