package chat.matron.android.journal

import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.flow.Flow

/// The store reads the items panel and detail views need, as an interface so
/// tests fake the store (mirrors [MediaBrowserStoreReading]'s pattern; the
/// Swift original declares the conformance in the ViewModels module, which
/// Kotlin can't do retroactively, so [JournalStore] implements it directly).
interface ItemsStoreReading {
    fun itemsFlow(scope: ItemsScope): Flow<List<TrackerItem>>
    fun itemFlow(id: String): Flow<TrackerItem?>
    fun commentsFlow(itemID: String): Flow<List<TrackerComment>>
    /// One-shot read of [commentsFlow]'s current value — read by
    /// `ItemDetailViewModel` right after its opening refetch returns, so
    /// `loadedCommentCount` never flips ahead of the thread it vouches for.
    suspend fun comments(itemID: String): List<TrackerComment>
    fun itemOutboxFlow(itemID: String): Flow<List<ItemOutboxEntity>>
    /// Every queued "create" outbox row, feeding `ItemsPanelViewModel.pendingCreates`.
    fun itemOutboxCreatesFlow(): Flow<List<ItemOutboxEntity>>
    /// Every conversation's items regardless of the panel's scope — the
    /// source of `ItemsPanelViewModel.awaitingYou` (app shell, spec §1).
    /// Defaulted to `itemsFlow(All)` so `JournalStore` needs no new query;
    /// fakes override it to drive it separately.
    fun needsUserFlow(): Flow<List<TrackerItem>> = itemsFlow(ItemsScope.All)
}
