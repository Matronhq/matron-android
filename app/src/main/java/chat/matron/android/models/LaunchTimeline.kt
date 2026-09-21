package chat.matron.android.models

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import chat.matron.android.journal.MatronJson
import chat.matron.android.viewmodels.KeyValueStore
import java.util.Locale
import kotlinx.serialization.Serializable

/// What this launch cost, in milliseconds. [storeOpenMs] and [migrationMs]
/// are durations; [firstListPaintMs] and [catchUpCompleteMs] are measured
/// from the kernel's process start, so they are what the user actually
/// waited. Port of matron-apple's `LaunchRecord` (#212).
@Serializable
data class LaunchRecord(
    val storeOpenMs: Long? = null,
    val migrationMs: Long? = null,
    val firstListPaintMs: Long? = null,
    val catchUpCompleteMs: Long? = null,
    /// Wall-clock epoch ms at which this record's process started.
    val recordedAt: Long,
)

/// Process-wide launch recorder: `android.os.Trace` sections for Perfetto /
/// systrace, one breadcrumb per mark so `logcat` gives the numbers on a
/// phone without a trace, and the whole record persisted to the preferences
/// store on every mark (not at process exit), so Settings › Storage can show
/// THIS launch — the one the user is in when they open Settings. Port of
/// matron-apple's `LaunchTimeline` (`OSSignposter` + `UserDefaults`).
///
/// Marks are first-wins: the chat list re-appears on every navigation back,
/// a reconnect re-reaches the live cursor, and a sign-out → sign-in opens a
/// second store — none of those is "the launch".
///
/// Android-specific shape of the store-open interval: Room opens the
/// database lazily on its first access, off the main thread, so
/// [beginStoreOpen] is called where the composition root builds the handle
/// and [endStoreOpen] from Room's `onOpen` callback — the interval therefore
/// spans the lazy open including any migration, which is the number that
/// matters. The migration's own duration is measured by the database layer
/// (`MatronDatabase.open`'s `onOpened`) and merely reported here.
class LaunchTimeline(
    /// Monotonic ms clock; `SystemClock.elapsedRealtime` in production.
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /// Process start on the same clock, so every mark is launch-relative
    /// rather than relative to whenever the first Kotlin code ran.
    private val processStart: Long = Process.getStartElapsedRealtime(),
    store: KeyValueStore? = null,
    wallClock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Mark { FIRST_LIST_PAINT, CATCH_UP_COMPLETE }

    private val lock = Any()
    private var store: KeyValueStore? = store
    private var _record = LaunchRecord(recordedAt = wallClock())
    private var storeOpenBegan: Long? = null

    val record: LaunchRecord get() = synchronized(lock) { _record }

    /// The composition root attaches the preferences store once it exists;
    /// whatever was recorded before that is persisted on attach.
    fun attachStore(store: KeyValueStore) {
        synchronized(lock) {
            this.store = store
            persist()
        }
    }

    fun beginStoreOpen() {
        synchronized(lock) {
            if (_record.storeOpenMs != null || storeOpenBegan != null) return
            storeOpenBegan = clock()
        }
        traceAsyncBegin(SECTION_STORE_OPEN)
    }

    fun endStoreOpen() {
        val elapsed: Long
        synchronized(lock) {
            val began = storeOpenBegan ?: return
            elapsed = clock() - began
            _record = _record.copy(storeOpenMs = elapsed)
            storeOpenBegan = null
            // Persisted while still holding the lock so the write stays
            // ordered with the mutation: a mark landing concurrently on
            // another thread cannot finish its own read-mutate-write in the
            // gap and have this snapshot overwrite it.
            persist()
        }
        traceAsyncEnd(SECTION_STORE_OPEN)
        MatronDebug.breadcrumb("launch storeOpen ${seconds(elapsed)}")
    }

    /// Records the schema migration that ran inside this launch's store
    /// open. First-wins like everything else here.
    fun recordMigration(millis: Long) {
        synchronized(lock) {
            if (_record.migrationMs != null) return
            _record = _record.copy(migrationMs = millis)
            persist()
        }
        traceInstant(SECTION_MIGRATION)
        MatronDebug.breadcrumb("launch migration ${seconds(millis)}")
    }

    /// Records a point mark, launch-relative. First one wins.
    fun mark(mark: Mark) {
        val elapsed: Long
        synchronized(lock) {
            elapsed = clock() - processStart
            _record = when (mark) {
                Mark.FIRST_LIST_PAINT -> {
                    if (_record.firstListPaintMs != null) return
                    _record.copy(firstListPaintMs = elapsed)
                }
                Mark.CATCH_UP_COMPLETE -> {
                    if (_record.catchUpCompleteMs != null) return
                    _record.copy(catchUpCompleteMs = elapsed)
                }
            }
            persist()
        }
        traceInstant(if (mark == Mark.FIRST_LIST_PAINT) SECTION_FIRST_LIST_PAINT else SECTION_CATCH_UP_COMPLETE)
        MatronDebug.breadcrumb("launch ${mark.name.lowercase()} ${seconds(elapsed)}")
    }

    /// Every call site holds [lock] for the duration — a preferences write is
    /// a handful of bytes, cheap enough that serialising it with the mutation
    /// is the simplest way to guarantee that after any interleaving of marks
    /// the persisted record equals the in-memory one.
    private fun persist() {
        val store = store ?: return
        runCatching { store.setString(KEY, MatronJson.encodeToString(LaunchRecord.serializer(), _record)) }
    }

    companion object {
        /// The preferences key the Settings row reads.
        const val KEY = "launch.last"

        val shared: LaunchTimeline by lazy { LaunchTimeline() }

        private const val SECTION_STORE_OPEN = "matron:launch:storeOpen"
        private const val SECTION_MIGRATION = "matron:launch:migration"
        private const val SECTION_FIRST_LIST_PAINT = "matron:launch:firstListPaint"
        private const val SECTION_CATCH_UP_COMPLETE = "matron:launch:catchUpComplete"

        /// The persisted record. Named for what it actually holds: `persist()`
        /// runs on every mark rather than at process exit, so by the time the
        /// user can open Settings this describes the launch they are IN —
        /// which is the useful one, and why the row says "This launch".
        fun currentLaunch(store: KeyValueStore): LaunchRecord? {
            val raw = store.getString(KEY) ?: return null
            return runCatching { MatronJson.decodeFromString(LaunchRecord.serializer(), raw) }.getOrNull()
        }

        /// The "This launch" row's copy: `store 1.9 s · first list 2.4 s ·
        /// catch-up 6.1 s`, with `· migration 3.2 s` appended on the one
        /// launch that ran one. Pure, so it is testable without a launch.
        fun summary(record: LaunchRecord?): String {
            if (record == null) return "—"
            val parts = mutableListOf<String>()
            record.storeOpenMs?.let { parts += "store ${seconds(it)}" }
            record.firstListPaintMs?.let { parts += "first list ${seconds(it)}" }
            record.catchUpCompleteMs?.let { parts += "catch-up ${seconds(it)}" }
            record.migrationMs?.let { parts += "migration ${seconds(it)}" }
            return if (parts.isEmpty()) "—" else parts.joinToString(" · ")
        }

        private fun seconds(millis: Long): String = String.format(Locale.US, "%.1f s", millis / 1000.0)

        // Async sections because begin (composition root, main thread) and
        // end (Room's onOpen, an IO thread) run on different threads; API 29+.
        private fun traceAsyncBegin(name: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { Trace.beginAsyncSection(name, 0) }
        }

        private fun traceAsyncEnd(name: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) runCatching { Trace.endAsyncSection(name, 0) }
        }

        /// A zero-length section: shows as an instant on the calling thread.
        private fun traceInstant(name: String) {
            runCatching {
                Trace.beginSection(name)
                Trace.endSection()
            }
        }
    }
}
