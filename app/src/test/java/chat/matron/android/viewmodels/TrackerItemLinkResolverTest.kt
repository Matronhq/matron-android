package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsRefreshOutcome
import chat.matron.android.journal.TrackerItemNumberReading
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The one place every `[#65](matron://item/65)` tap resolves (tracker item
/// #115; port of matron-apple's `TrackerItemLinkResolverTests`, #208). The
/// miss path matters as much as the hit: an unsynced number must come back
/// as `NotSynced` after EXACTLY one refresh, so the host can stay where it is
/// and say so, instead of popping the reader to a list.
class TrackerItemLinkResolverTest {
    /// Numbers present on this "device". Mutated by the refresh fake to
    /// simulate a sync landing the item.
    private class FakeNumberStore(present: Set<Int> = emptySet()) : TrackerItemNumberReading {
        val present = present.toMutableSet()
        var throwsOnRead = false
        val reads = mutableListOf<Int>()
        override suspend fun item(num: Int): TrackerItem? {
            reads += num
            if (throwsOnRead) throw IllegalStateException("store")
            if (num !in present) return null
            return TrackerItem(id = "id-$num", num = num, kind = ItemKind.TASK, title = "T$num", originConvoID = "c1")
        }
    }

    private class FakeRefreshSync : FakeItemsSync() {
        /// Runs on each `refresh` — how a test lands the item mid-resolve.
        var onRefresh: () -> Unit = {}
        var outcome: ItemsRefreshOutcome = ItemsRefreshOutcome.Succeeded
        override suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome {
            refreshed += scope; onRefresh(); return outcome
        }
    }

    @Test
    fun localHitOpensWithoutRefreshing() = runTest {
        val store = FakeNumberStore(setOf(65))
        val sync = FakeRefreshSync()
        val resolution = TrackerItemLinkResolver(store, sync).resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.Open("id-65"), resolution)
        assertTrue("a local hit must not fetch anything", sync.refreshed.isEmpty())
        assertEquals("one read, no retry", listOf(65), store.reads)
    }

    @Test
    fun missThenRefreshHitOpens() = runTest {
        val store = FakeNumberStore()
        val sync = FakeRefreshSync()
        // The refresh is what lands the item — the exact case the retry
        // exists for (an agent filed #65 seconds ago).
        sync.onRefresh = { store.present += 65 }
        val resolution = TrackerItemLinkResolver(store, sync).resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.Open("id-65"), resolution)
        assertEquals("the retry refreshes ALL scopes — the item may be another chat's", listOf<ItemsScope>(ItemsScope.All), sync.refreshed)
        assertEquals(listOf(65, 65), store.reads)
    }

    @Test
    fun missThenRefreshMissIsNotSyncedAndRefreshesExactlyOnce() = runTest {
        val store = FakeNumberStore()
        val sync = FakeRefreshSync()
        val resolution = TrackerItemLinkResolver(store, sync).resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.NotSynced, resolution)
        assertEquals("at most one refresh per resolve", 1, sync.refreshed.size)
        assertEquals(listOf(65, 65), store.reads)
        assertEquals("Item #65 isn't on this device yet.", resolution.alertMessage(65))
    }

    @Test
    fun throwingStoreReadFailsWithoutRefreshing() = runTest {
        val store = FakeNumberStore(setOf(65)).apply { throwsOnRead = true }
        val sync = FakeRefreshSync()
        val resolution = TrackerItemLinkResolver(store, sync).resolve(65)
        assertTrue(resolution is TrackerItemLinkResolver.Resolution.Failed)
        assertTrue(sync.refreshed.isEmpty())
        assertNotNull(resolution.alertMessage(65))
    }

    /// A transport fault must not be reported as "this item doesn't exist
    /// here" — the user would go looking for a missing item. `ItemsSync
    /// .refresh` swallows the throw internally; what it REPORTS decides.
    @Test
    fun failedRefreshIsFailedNotNotSynced() = runTest {
        val resolver = TrackerItemLinkResolver(lookup = { null }, refreshAll = { ItemsRefreshOutcome.Failed("the journal said no") })
        val resolution = resolver.resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.Failed("the journal said no"), resolution)
        assertEquals("Couldn't open item #65 — the journal said no", resolution.alertMessage(65))
    }

    /// The pair that gives the two messages their meaning: the SAME miss
    /// reports differently depending on whether the fetch happened.
    @Test
    fun refreshOutcomeDecidesWhichMissTheUserIsTold() = runTest {
        val store = FakeNumberStore()
        val offline = FakeRefreshSync().apply { outcome = ItemsRefreshOutcome.Failed("the journal said no") }
        val failed = TrackerItemLinkResolver(store, offline).resolve(65)
        assertEquals("Couldn't open item #65 — the journal said no", failed.alertMessage(65))
        // A failed refresh still earns one more local read, in case an
        // earlier page landed the item before a later page's error.
        assertEquals("the failure path re-checks the store before reporting", listOf(65, 65), store.reads)

        val missed = TrackerItemLinkResolver(store, FakeRefreshSync()).resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.NotSynced, missed)
        assertEquals("Item #65 isn't on this device yet.", missed.alertMessage(65))
    }

    /// `ItemsSync.refreshOnce` upserts each page before fetching the next,
    /// so a later-page transport error can still leave the tapped item
    /// already in the store from an earlier page.
    @Test
    fun failedRefreshButItemLandedFromAnEarlierPageStillOpens() = runTest {
        val store = FakeNumberStore()
        val sync = FakeRefreshSync().apply {
            onRefresh = { store.present += 65 }
            outcome = ItemsRefreshOutcome.Failed("boom")
        }
        val resolution = TrackerItemLinkResolver(store, sync).resolve(65)
        assertEquals(TrackerItemLinkResolver.Resolution.Open("id-65"), resolution)
        assertEquals("the failure path gets its own re-read, not a third one", listOf(65, 65), store.reads)
    }

    /// A journal with no tracker routes, and a sync stopped mid-tap by a
    /// sign-out, both leave a genuine local miss — not a failure to report.
    @Test
    fun unsupportedOrStoppedRefreshStillReadsAsNotSynced() = runTest {
        for (outcome in listOf(ItemsRefreshOutcome.Unsupported, ItemsRefreshOutcome.Stopped)) {
            val sync = FakeRefreshSync().apply { this.outcome = outcome }
            assertEquals("$outcome", TrackerItemLinkResolver.Resolution.NotSynced, TrackerItemLinkResolver(FakeNumberStore(), sync).resolve(65))
        }
    }

    @Test
    fun refreshIsCalledAtMostOncePerResolveEvenAcrossRepeatedTaps() = runTest {
        var refreshes = 0
        val resolver = TrackerItemLinkResolver(lookup = { null }, refreshAll = { refreshes++; ItemsRefreshOutcome.Succeeded })
        resolver.resolve(65)
        assertEquals(1, refreshes)
        // A second tap is a second resolve: one more refresh, not a runaway
        // loop off the back of the first.
        resolver.resolve(65)
        assertEquals(2, refreshes)
    }

    /// A lookup cut short by cancellation (the tap was superseded, or the
    /// host left the screen) aborts the resolve — it is never reported as
    /// `Failed`, which the gate could still apply as a bogus alert.
    @Test
    fun cancellationAbortsTheResolveInsteadOfFailingIt() = runTest {
        var refreshed = false
        val resolver = TrackerItemLinkResolver(
            lookup = { throw CancellationException("superseded") },
            refreshAll = { refreshed = true; ItemsRefreshOutcome.Succeeded },
        )
        val thrown = runCatching { resolver.resolve(65) }.exceptionOrNull()
        assertTrue("expected the cancellation to propagate, got $thrown", thrown is CancellationException)
        assertFalse("a cancelled read must not go on to refresh", refreshed)
    }

    @Test
    fun openResolutionHasNothingToSay() = runTest {
        val resolver = TrackerItemLinkResolver(
            lookup = { TrackerItem(id = "id-$it", num = it, kind = ItemKind.TASK, title = "t", originConvoID = "c1") },
            refreshAll = { throw AssertionError("no refresh on a hit") },
        )
        assertNull(resolver.resolve(9).alertMessage(9))
    }
}
