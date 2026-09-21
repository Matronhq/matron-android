package chat.matron.android.viewmodels

/// Persists whether the reader had scrolled to the bottom of a tracker
/// item's comment thread, so reopening the item resumes there instead of
/// always landing at the top — the tracker-item equivalent of
/// `ChatScrollPositionMemory`'s "open where you left off" for chats.
///
/// Backed by the app's [KeyValueStore] (the Swift original's
/// `UserDefaults`) rather than an in-memory map: items are revisited across
/// app launches far more often than a single chat session, and losing the
/// position on every relaunch would defeat the point. Ported from
/// matron-apple's `Models/ItemReadMemory.swift`.
class ItemReadMemory(private val store: KeyValueStore) {
    private fun key(itemID: String) = "items.readToEnd.$itemID"

    /// Whether the reader was at the bottom of [itemID]'s comment thread the
    /// last time they viewed it. `false` for an item that's never been stored
    /// — an item nobody has read yet (or that was explicitly forgotten) opens
    /// at the top, not the tail.
    fun wasAtBottom(itemID: String): Boolean = store.getBoolean(key(itemID), default = false)

    /// Records whether the reader is currently at the bottom of [itemID]'s thread.
    fun store(itemID: String, atBottom: Boolean) = store.setBoolean(key(itemID), atBottom)

    /// Drops the stored position for a single item.
    fun forget(itemID: String) = store.remove(key(itemID))
}
