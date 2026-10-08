package chat.matron.android.designsystem

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Tracker-link taps resolve asynchronously and finish OUT OF ORDER: a miss
/// suspends inside a full `refresh(All)` while a tap made a moment later hits
/// the local store and returns at once. The gate is what stops the slow one
/// coming back afterwards to navigate somewhere the user has already left, or
/// to post an alert about a number they have moved on from. Port of
/// matron-apple's `TrackerItemLinkTapGateTests` (#208).
@OptIn(ExperimentalCoroutinesApi::class)
class TrackerItemLinkTapGateTest {

    /// Tap #65 (slow, resolves to a miss), then tap #9 (fast, resolves to a
    /// real item). Only #9 may act, and #65's alert must never appear — not
    /// even after it finally returns.
    @Test
    fun aSlowTapCannotActOnceALaterTapHasArrived() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        val release = CompletableDeferred<Unit>()
        var slowSawCancellation = false

        gate.begin(65, { _ ->
            try {
                release.await()
            } catch (e: CancellationException) {
                slowSawCancellation = true
                throw e
            }
            TrackerItemLinkOutcome.Explain("Item #65 isn't on this device yet.")
        }) { applied += it }
        advanceUntilIdle()
        assertTrue("the slow resolve is suspended", applied.isEmpty())

        gate.begin(9, { _ -> TrackerItemLinkOutcome.Open("id-9") }) { applied += it }
        advanceUntilIdle()

        // Now let the superseded resolve finish, if it is still around.
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("only the latest tap may navigate, and the stale miss must never reach the alert", listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-9")), applied)
        assertTrue("a superseded resolve is cancelled as well as ignored", slowSawCancellation)
    }

    /// Even a resolve that ignores cancellation (a store read runs to
    /// completion regardless) is dropped on the way out.
    @Test
    fun aSupersededOutcomeIsDroppedEvenIfTheResolveIgnoresCancellation() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        val release = CompletableDeferred<Unit>()

        gate.begin(65, { _ ->
            // Swallows the cancellation and returns anyway.
            runCatching { release.await() }
            TrackerItemLinkOutcome.Open("id-65")
        }) { applied += it }
        advanceUntilIdle()
        gate.begin(9, { _ -> TrackerItemLinkOutcome.Open("id-9") }) { applied += it }
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-9")), applied)
    }

    /// The gate is not a latch: once a tap has finished, the next one acts
    /// normally.
    @Test
    fun aTapAfterTheLastOneFinishedStillActs() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        for ((num, id) in listOf(65 to "id-65", 9 to "id-9")) {
            gate.begin(num, { _ -> TrackerItemLinkOutcome.Open(id) }) { applied += it }
            advanceUntilIdle()
        }
        assertEquals(listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-65"), TrackerItemLinkOutcome.Open("id-9")), applied)
    }

    /// Two taps on the SAME number are two taps, so the second supersedes
    /// the first rather than being mistaken for it — one navigation, from
    /// the tap the user made last.
    @Test
    fun twoTapsOnTheSameNumberResolveToOneNavigation() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        val release = CompletableDeferred<Unit>()

        gate.begin(65, { _ -> release.await(); TrackerItemLinkOutcome.Open("id-65") }) { applied += it }
        advanceUntilIdle()
        gate.begin(65, { _ -> TrackerItemLinkOutcome.Open("id-65") }) { applied += it }
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals("one tap, one push — not two", listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-65")), applied)
    }

    /// An inline item card needs no resolving, but its tap is still a tap:
    /// a miss-path link resolve still refreshing when the card was tapped
    /// must not finish afterwards and push the OLDER item on top of the one
    /// the user just opened (Bugbot on matron-android #78).
    @Test
    fun aCardTapSupersedesALinkResolveStillInFlight() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        val release = CompletableDeferred<Unit>()
        var slowSawCancellation = false

        gate.begin(65, { _ ->
            try {
                release.await()
            } catch (e: CancellationException) {
                slowSawCancellation = true
                throw e
            }
            TrackerItemLinkOutcome.Open("id-65")
        }) { applied += it }
        advanceUntilIdle()

        gate.openDirectly("id-card") { applied += it }
        assertEquals("the card opens at once, no resolve", listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-card")), applied)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("the stale link resolve never navigates", listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-card")), applied)
        assertTrue("and it was cancelled, not just ignored", slowSawCancellation)
    }

    /// …and a resolve that ignores cancellation is still dropped on the way
    /// out, because the card tap became the current one.
    @Test
    fun aCardTapDropsAnUncancellableResolveToo() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        val release = CompletableDeferred<Unit>()
        gate.begin(65, { _ -> runCatching { release.await() }; TrackerItemLinkOutcome.Open("id-65") }) { applied += it }
        advanceUntilIdle()
        gate.openDirectly("id-card") { applied += it }
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-card")), applied)
        // A link tap after the card still acts normally — the gate is not a latch.
        gate.begin(9, { _ -> TrackerItemLinkOutcome.Open("id-9") }) { applied += it }
        advanceUntilIdle()
        assertEquals(listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Open("id-card"), TrackerItemLinkOutcome.Open("id-9")), applied)
    }

    /// `Ignore` is the host saying "I couldn't even try" (no session yet, a
    /// link to the item already on screen): the gate just applies it and
    /// the host does nothing.
    @Test
    fun ignoreIsDeliveredLikeAnyOtherOutcome() = runTest {
        val gate = TrackerItemLinkTapGate(this)
        val applied = mutableListOf<TrackerItemLinkOutcome>()
        gate.begin(65, { _ -> TrackerItemLinkOutcome.Ignore }) { applied += it }
        advanceUntilIdle()
        assertEquals(listOf<TrackerItemLinkOutcome>(TrackerItemLinkOutcome.Ignore), applied)
    }
}
