package chat.matron.android.viewmodels

/// Persistent, most-recent-first list of folder paths the user has started
/// sessions in (via `/start` or `/workdir`), powering recent-folder completion
/// in the composer's slash palette.
///
/// Unlike [ComposerDraftMemory] / [SentMessageHistory] — session-scoped and
/// in-memory — this store is persisted through an injected [KeyValueStore] (the
/// Android analogue of the Swift original's injected `UserDefaults`) so a folder
/// typed once is still suggested after an app relaunch. Tests point it at an
/// in-memory store.
///
/// The stored paths are the raw strings the user typed — they name folders on
/// the *bridge* machine, not the device, so there's nothing to expand or
/// validate locally. Keyed globally rather than per-room: the same bridge
/// machine's folders apply in every conversation.
class RecentStartFolders(
    private val store: KeyValueStore,
) {
    /// Records a folder path the user started a session in. Trims surrounding
    /// whitespace and ignores empty input. A case-insensitive duplicate is moved
    /// to the front (keeping the user's original casing for the moved entry)
    /// rather than added twice. Caps the list at [CAP], dropping the oldest.
    fun record(path: String) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        val folders = stored().toMutableList()
        folders.removeAll { it.equals(trimmed, ignoreCase = true) }
        folders.add(0, trimmed)
        if (folders.size > CAP) {
            repeat(folders.size - CAP) { folders.removeAt(folders.size - 1) }
        }
        store.setStringList(DEFAULTS_KEY, folders)
    }

    /// Recorded folders whose path has [prefix] as a case-insensitive prefix,
    /// preserving most-recent-first order. An empty prefix returns the full list.
    fun matches(prefix: String): List<String> {
        val folders = stored()
        if (prefix.isEmpty()) return folders
        val needle = prefix.lowercase()
        return folders.filter { it.lowercase().startsWith(needle) }
    }

    /// The persisted ordered list, most-recent-first.
    private fun stored(): List<String> = store.getStringList(DEFAULTS_KEY) ?: emptyList()

    private companion object {
        /// Max folders retained. Older entries fall off the end.
        const val CAP = 15

        /// Key for the ordered path list. App-global (not per-room).
        const val DEFAULTS_KEY = "composer.recentStartFolders"
    }
}
