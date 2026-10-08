package chat.matron.android.viewmodels

/// In-memory cache of in-flight composer text per room, so navigating back to a
/// room restores whatever the user had typed instead of silently dropping it.
/// Survives navigation within the session; resets on app quit.
///
/// Ported from matron-apple's `@MainActor enum ComposerDraftMemory`; a Kotlin
/// `object` mirrors the Swift static-state enum. Callers are UI-thread confined,
/// so the map needs no lock.
object ComposerDraftMemory {
    private val drafts = mutableMapOf<String, String>()

    /// Captures the user's current composer text for [roomID]. Stores the raw
    /// value (no trimming) — collapsing trailing whitespace would clobber the
    /// slash-palette's `"/start "` post-selection state. Empty strings clear the
    /// entry so a sent-then-empty composer doesn't ghost text into the next
    /// visit.
    fun store(roomID: String, text: String) {
        if (text.isEmpty()) {
            drafts.remove(roomID)
        } else {
            drafts[roomID] = text
        }
    }

    /// The previously-stored draft for [roomID], or `null` if the user hasn't
    /// typed in this room this session.
    fun retrieve(roomID: String): String? = drafts[roomID]

    /// Drops the saved draft for a single room. Called on a successful send so
    /// the next open lands on an empty composer.
    fun forget(roomID: String) {
        drafts.remove(roomID)
    }

    /// Test seam: clear all stored drafts.
    fun resetForTesting() {
        drafts.clear()
    }
}
