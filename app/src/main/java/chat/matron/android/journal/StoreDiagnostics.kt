package chat.matron.android.journal

import android.text.format.DateUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// On-demand answers for Settings › Storage. Nothing here runs unless the
/// user opens that screen: the whole point of apple #212 was to stop doing
/// store-sized reads on the launch path.
///
/// Presentation lives in `StorageSettingsRows` (design system); this object
/// deliberately owns only the store reads plus the one formatter that needs
/// a clock, so the design system keeps no dependency on the journal.
object StoreDiagnostics {
    data class Sizes(
        /// `.sqlite` + `-wal` + `-shm` for the journal mirror.
        val journalBytes: Long,
        /// The same three files for the FTS index.
        val searchBytes: Long,
        val eventCount: Int,
        val conversationCount: Int,
        /// `meta.maintenance_last_run` (epoch ms), or `null` when no sweep has
        /// finished on this device yet.
        val lastMaintenance: Long?,
    )

    /// File stats and two `COUNT(*)`s, on IO — the caller is a Compose
    /// effect that must not block a frame. [journalFile] / [searchFile] are
    /// `null` for stores that have no file (in-memory, or the index failed
    /// to open), which reads as 0 bytes.
    suspend fun sizes(store: JournalStore, journalFile: File?, searchFile: File?): Sizes = withContext(Dispatchers.IO) {
        val counts = runCatching { store.rowCounts() }.getOrDefault(JournalStore.RowCounts(0, 0))
        Sizes(
            journalBytes = fileGroupSize(journalFile),
            searchBytes = fileGroupSize(searchFile),
            eventCount = counts.events,
            conversationCount = counts.conversations,
            lastMaintenance = runCatching { store.maintenanceLastRun() }.getOrNull(),
        )
    }

    /// A SQLite database is three files in WAL mode; reporting only the main
    /// one understates a busy store by the whole write-ahead log.
    fun fileGroupSize(file: File?): Long {
        if (file == null) return 0
        return listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
            .sumOf { if (it.isFile) it.length() else 0L }
    }

    /// "Never", or a relative time like "1 hour ago".
    fun lastMaintenanceText(lastMs: Long?, nowMs: Long): String {
        if (lastMs == null) return "Never"
        return DateUtils.getRelativeTimeSpanString(lastMs, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}
