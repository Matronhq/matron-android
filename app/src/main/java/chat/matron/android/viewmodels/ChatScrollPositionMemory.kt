package chat.matron.android.viewmodels

/// In-memory cache of "last viewed item id" per room, so reopening a chat
/// returns to where the user left off instead of always jumping to the latest
/// message. Survives navigation within the session; resets on app quit.
///
/// Ported from matron-apple's `@MainActor enum ChatScrollPositionMemory` — a
/// Kotlin `object` is the direct analogue of the Swift static-state enum. Callers
/// are UI-thread confined (like the Swift main actor), so the map needs no lock.
object ChatScrollPositionMemory {
    private val positions = mutableMapOf<String, String>()

    /// Captures the bottom-anchored item id the user was last looking at in
    /// [roomID]. Pass `null` (or call [forget]) to drop the entry, which falls
    /// back to "open at tail" next time.
    ///
    /// Transient ids (send echoes, the activity indicator, in-flight streaming
    /// rows) are treated as `null`: they name rows guaranteed to be gone by the
    /// next open, and restoring one pins the viewport to nothing. A user anchored
    /// to a transient row was at the live tail, so dropping the entry is exactly
    /// the "open at tail" behaviour they expect.
    fun store(roomID: String, itemID: String?) {
        if (itemID != null && !isTransient(itemID)) {
            positions[roomID] = itemID
        } else {
            positions.remove(roomID)
        }
    }

    /// Row ids that never survive to the next open of a room: send echoes, the
    /// activity indicator, and in-flight streaming rows (`eph:`).
    private fun isTransient(id: String): Boolean =
        id == "activity" || id.startsWith("echo:") || id.startsWith("eph:")

    /// The previously-stored item id for [roomID], or `null` if the user hasn't
    /// viewed this room in this session.
    fun retrieve(roomID: String): String? = positions[roomID]

    /// Drops the saved position for a single room. Called on a successful "jump
    /// to bottom" so a subsequent re-open lands at the tail.
    fun forget(roomID: String) {
        positions.remove(roomID)
    }

    /// Test seam: clear all stored positions.
    fun resetForTesting() {
        positions.clear()
    }
}
