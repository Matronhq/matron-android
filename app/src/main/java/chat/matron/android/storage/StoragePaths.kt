package chat.matron.android.storage

import android.content.Context
import java.io.File

/// Filesystem locations for the on-device stores. The Apple original branches
/// on iOS app-group containers vs. macOS Application Support; on Android every
/// store lives under the app's private `filesDir`, which is sandbox-private and
/// (on modern devices) encrypted at rest via file-based encryption.
object StoragePaths {

    /// Base private directory. All matron stores hang off this.
    fun appSupport(context: Context): File = context.filesDir

    /// The journal mirror SQLite file.
    fun journalDb(context: Context): File = File(context.filesDir, JOURNAL_DB_NAME)

    // Pure path helpers (no Context) so tests can assert derivations and
    // callers can compose against an arbitrary base directory.
    fun cryptoStore(base: File): File = File(base, "crypto-store")
    fun searchDb(base: File): File = File(base, "matron-search.sqlite")
    fun journalDb(base: File): File = File(base, JOURNAL_DB_NAME)

    const val JOURNAL_DB_NAME = "journal.sqlite"
}
