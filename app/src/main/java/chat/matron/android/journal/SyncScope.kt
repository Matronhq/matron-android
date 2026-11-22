package chat.matron.android.journal

import chat.matron.android.viewmodels.KeyValueStore

/// How much of the journal this device keeps (protocol "Sync scope").
///
/// [EVERYTHING] mirrors every event of every conversation, which is what
/// every device did before this setting existed. [FOR_YOU] keeps the
/// tracker, missions, the Coordinator conversation and conversation titles
/// and states current, and fetches any other conversation when it is
/// opened: for a phone that mostly reads For you, most of the journal's
/// traffic then never reaches it.
enum class SyncScope(val wire: String, val title: String) {
    EVERYTHING("everything", "Everything"),
    FOR_YOU("for_you", "For you only"),
    ;

    /// Settings › Sync on this device: what the choice means, under it.
    val footer: String
        get() = when (this) {
            EVERYTHING ->
                "Every conversation is kept on this device as it happens, so any of them opens at once, even offline."
            FOR_YOU ->
                "For you, missions and the Coordinator stay up to date. Other conversations are fetched when you " +
                    "open them, so this device does much less work in the background. Offline, only conversations " +
                    "you have opened are here. Switching back to Everything reloads this device's copy."
        }

    companion object {
        /// Per device, not per user or account: one phone can follow For you
        /// while a tablet keeps everything.
        const val KEY = "sync.scope"

        fun fromWire(value: String?): SyncScope? = entries.firstOrNull { it.wire == value }

        fun stored(store: KeyValueStore): SyncScope = fromWire(store.getString(KEY)) ?: EVERYTHING

        fun store(scope: SyncScope, store: KeyValueStore) = store.setString(KEY, scope.wire)
    }
}

/// Reads one conversation's events a page at a time, newest first
/// (`GET /convo/:id/messages`). The engine fetches an opened conversation
/// through it on the [SyncScope.FOR_YOU] scope.
fun interface ConversationPageSource {
    /// Up to [limit] events of [convoID] older than [beforeSeq] (the newest
    /// when `null`).
    suspend fun messages(convoID: String, beforeSeq: Long?, limit: Int): List<JournalEvent>
}
