package chat.matron.android.viewmodels

/// Per-room, in-memory history of the user's sent messages, powering
/// terminal-style Up/Down recall in the composer. `ComposerViewModel` owns one
/// instance; storage is keyed by room so a single instance serves several rooms
/// and stays isolated between them.
///
/// Survives within the session (as long as the owning view model is cached);
/// resets on app quit. bash / zsh / iMessage behave the same way — Up walks
/// backwards from the most recent, Down walks forward, and stepping past the
/// newest entry restores whatever half-typed draft the user stashed when they
/// began recalling.
///
/// Ported from matron-apple's `@MainActor final class SentMessageHistory`;
/// callers are UI-thread confined, so the per-room map needs no lock.
class SentMessageHistory {
    /// Sent messages per room, most-recent last (natural append order).
    private val messagesByRoom = mutableMapOf<String, MutableList<String>>()

    /// Recall walk state, live only while the user is navigating history.
    /// `recallIndex == null` means "not navigating"; it otherwise points at the
    /// currently-shown entry within the room's list. `stashedDraft` is the
    /// in-progress text captured when the walk began, restored on stepping past
    /// the newest entry.
    private var recallRoom: String? = null
    private var recallIndex: Int? = null
    private var stashedDraft: String? = null

    /// Records a just-sent message for [room]. Consecutive duplicates are
    /// collapsed (bash `ignoredups` style). Caps the per-room history at [CAP],
    /// dropping the oldest. Recording ends any in-progress recall walk.
    fun record(text: String, room: String) {
        endRecall()
        val messages = messagesByRoom.getOrPut(room) { mutableListOf() }
        if (messages.lastOrNull() == text) return
        messages.add(text)
        if (messages.size > CAP) {
            repeat(messages.size - CAP) { messages.removeAt(0) }
        }
    }

    /// Whether a recall walk is currently active.
    val isNavigating: Boolean get() = recallIndex != null

    /// Begins or continues walking backwards (Up). The first call stashes
    /// [currentDraft] so a later Down can restore it, and returns the most recent
    /// sent message. Subsequent calls walk toward older entries. Returns `null`
    /// when there's no history or the oldest entry is already shown.
    fun recallOlder(room: String, currentDraft: String): String? {
        val messages = messagesByRoom[room] ?: emptyList()
        if (messages.isEmpty()) return null
        if (recallRoom != room || recallIndex == null) {
            // Fresh walk for this room: stash the draft, start at the newest.
            recallRoom = room
            stashedDraft = currentDraft
            val newest = messages.size - 1
            recallIndex = newest
            return messages[newest]
        }
        val idx = recallIndex ?: return null
        if (idx <= 0) return null
        recallIndex = idx - 1
        return messages[idx - 1]
    }

    /// Walks forward (Down). Returns the next-newer entry, or the stashed draft —
    /// ending the walk — when stepping past the newest entry. Returns `null` when
    /// not currently navigating this room.
    fun recallNewer(room: String): String? {
        val idx = recallIndex ?: return null
        if (recallRoom != room) return null
        val messages = messagesByRoom[room] ?: emptyList()
        if (idx < messages.size - 1) {
            recallIndex = idx + 1
            return messages[idx + 1]
        }
        val draft = stashedDraft ?: ""
        endRecall()
        return draft
    }

    /// Abandons the current walk, handing back the stashed draft so the caller
    /// can restore the user's real in-progress draft rather than the recalled
    /// sent line the walk happened to be showing. Returns `null` when no walk is
    /// active.
    fun cancelRecall(): String? {
        if (recallIndex == null) return null
        val draft = stashedDraft ?: ""
        endRecall()
        return draft
    }

    /// Ends the current recall walk (e.g. the user edited the field or sent a
    /// message). Idempotent.
    fun endRecall() {
        recallRoom = null
        recallIndex = null
        stashedDraft = null
    }

    private companion object {
        /// Max entries retained per room. Older entries fall off the front.
        const val CAP = 50
    }
}
