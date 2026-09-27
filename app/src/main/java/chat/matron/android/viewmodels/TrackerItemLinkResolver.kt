package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsRefreshOutcome
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.TrackerItemNumberReading
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerItem

/// Resolves a tapped `matron://item/<n>` link to a local item id (tracker
/// item #115; port of matron-apple's `TrackerItemLinkResolver`, #208).
///
/// Every install site — the chat, the item detail — routes through this one
/// type so the miss path cannot drift between them. The rule:
///
/// 1. Look the number up locally.
/// 2. On a miss, run **exactly one** `refresh(All)` through the existing
///    items-sync path and look again — the number is very often a real item
///    this device simply hasn't synced yet (an agent filed it seconds ago,
///    or it belongs to another conversation whose items were never fetched).
/// 3. Still missing → [Resolution.NotSynced]. A throwing store read →
///    [Resolution.Failed]. A refresh that reported `Failed` gets ONE more
///    local lookup before reporting the failure: `ItemsSync.refreshOnce`
///    upserts each page as it fetches, so a later-page error can still leave
///    an earlier page's item — including the one tapped — already in the
///    store. Only a miss on THAT lookup too becomes `Failed`.
///
/// What the caller must do with `NotSynced` / `Failed` is as important as
/// the lookup: **stay exactly where you are** and show [alertMessage].
/// Popping or replacing navigation with the tracker list would destroy the
/// reader's place in a conversation to show them a list that, by
/// definition, does not contain the item they asked for.
class TrackerItemLinkResolver(
    private val lookup: suspend (Int) -> TrackerItem?,
    private val refreshAll: suspend () -> ItemsRefreshOutcome,
) {
    /// Production wiring: the session's `JournalStore` and its `ItemsSync`.
    constructor(store: TrackerItemNumberReading, sync: ItemsSyncing) : this(
        lookup = { store.item(num = it) },
        refreshAll = { sync.refresh(ItemsScope.All) },
    )

    sealed interface Resolution {
        /// The item is on this device — its id, ready to navigate to.
        data class Open(val itemID: String) : Resolution

        /// A well-formed number that isn't in the local store, even after a
        /// refresh. Either it doesn't exist or it belongs to a journal this
        /// device isn't signed in to.
        data object NotSynced : Resolution

        /// The store read threw, or the refresh came back `Failed` — i.e.
        /// we genuinely do not know whether this item exists.
        data class Failed(val message: String) : Resolution

        /// What to put in the host's tracker alert, or `null` when the item
        /// opened and there is nothing to say.
        fun alertMessage(num: Int): String? = when (this) {
            is Open -> null
            NotSynced -> "Item #$num isn't on this device yet."
            is Failed -> "Couldn't open item #$num — $message"
        }
    }

    suspend fun resolve(num: Int): Resolution {
        read(num)?.let { return it }
        // One retry, never more: a number that is still missing after a full
        // refresh is not going to appear on a second one, and a link tap
        // must not be able to queue an unbounded run of fetches.
        //
        // The OUTCOME of that refresh decides what a second miss means. A
        // refresh that failed (offline, 500) leaves the store exactly as
        // stale as it was, so "isn't on this device yet" would be a
        // fabrication — report the failure instead. `Unsupported` (this
        // journal has no tracker) and `Stopped` (sign-out mid-tap) both
        // leave a genuine local miss, so they fall through to `NotSynced`.
        return when (val outcome = refreshAll()) {
            is ItemsRefreshOutcome.Failed -> read(num) ?: Resolution.Failed(outcome.message)
            ItemsRefreshOutcome.Succeeded, ItemsRefreshOutcome.Unsupported, ItemsRefreshOutcome.Stopped ->
                read(num) ?: Resolution.NotSynced
        }
    }

    /// `Open` on a hit, `Failed` on a throwing read, `null` on a clean miss.
    private suspend fun read(num: Int): Resolution? = try {
        lookup(num)?.let { Resolution.Open(it.id) }
    } catch (e: Exception) {
        Resolution.Failed(e.message ?: e.javaClass.simpleName)
    }
}
