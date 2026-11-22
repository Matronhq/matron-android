package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import java.io.File
import java.io.IOException

/**
 * Moves an old journal database (`<user>.sqlite`, from before databases were
 * named per sign-in) to its new name, without ever splitting the database
 * from its write-ahead log.
 *
 * Committed changes can sit only in the `-wal` file, and the offline outbox
 * lives in the same database, so a database opened without its `-wal` can
 * lose unsent messages. The move therefore:
 *
 * - moves the side files (`-wal`, `-shm`, `-journal`) first and the main
 *   file last, so the old main file stays put until everything else has
 *   moved;
 * - finishes an interrupted move on the next launch: while the old main file
 *   is still there, any side file already at the new name counts as moved;
 * - stops on the first error, puts every side file at the new name back
 *   beside the old main file, and tells the caller to open the old file for
 *   now, so the next launch tries again;
 * - if a side file cannot be put back either, opens neither file: the caller
 *   runs on an in-memory database for that launch and leaves both files
 *   alone, and the next launch finishes the move.
 */
object LegacyStoreFileMove {
    val sideSuffixes = listOf("-wal", "-shm", "-journal")

    /**
     * Returns the database file to open: [target] once the old file has
     * moved (or there was none), [legacy] if the move could not finish, or
     * `null` (open in memory) if the old file could not be made whole.
     */
    fun adopt(
        legacy: File,
        target: File,
        fileExists: (File) -> Boolean = { it.exists() },
        move: (File, File) -> Unit = ::rename,
    ): File? {
        // No old database: nothing to move.
        if (!fileExists(legacy)) return target
        // A database already sits at the new name. The main file moves last,
        // so this move never leaves one behind; leave both alone rather than
        // overwrite a database that is in use.
        if (fileExists(target)) return target

        val steps = sideSuffixes.map { side(legacy, it) to side(target, it) } + (legacy to target)
        for ((from, to) in steps) {
            if (!fileExists(from)) continue
            try {
                move(from, to)
            } catch (e: Exception) {
                MatronDebug.breadcrumb("could not move the old journal database: $e")
                // Put back every side file that has moved, in this launch or
                // an interrupted one, so the old database opens whole.
                for (suffix in sideSuffixes) {
                    val moved = side(target, suffix)
                    val home = side(legacy, suffix)
                    if (!fileExists(moved) || fileExists(home)) continue
                    try {
                        move(moved, home)
                    } catch (e: Exception) {
                        MatronDebug.breadcrumb("could not put back the old journal database's $suffix: $e")
                        return null
                    }
                }
                return legacy
            }
        }
        return target
    }

    private fun side(file: File, suffix: String) = File(file.path + suffix)

    private fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw IOException("rename failed: ${from.name} → ${to.name}")
    }
}
