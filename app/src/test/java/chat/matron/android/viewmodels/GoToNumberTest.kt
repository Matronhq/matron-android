package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.NumberLookup
import chat.matron.android.models.ItemKind
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// "Go to #N": the parser, the two-step resolver and the dialog's state.
/// The misses matter as much as the hits — each must leave the dialog open
/// with one inline answer saying what the number is, or why it will not open.
@OptIn(ExperimentalCoroutinesApi::class)
class GoToNumberTest {
    /// One fake "device + journal": what is local, what the journal says,
    /// and what each sync does. Every call is recorded so a test can prove
    /// what was NOT asked.
    private class World {
        val localItems = mutableMapOf<Int, TrackerItem>()
        val localMissions = mutableMapOf<Int, Mission>()
        val cachedMissionIDs = mutableSetOf<String>()
        var lookupAnswer: NumberLookup? = null
        var lookupError: Throwable = JournalApiError.NotFound
        var localReadError: Throwable? = null
        var itemResolution: TrackerItemLinkResolver.Resolution = TrackerItemLinkResolver.Resolution.NotSynced
        var refreshOutcome: MissionsRefreshOutcome = MissionsRefreshOutcome.Succeeded
        /// Whether a successful refresh lands the mission in the store.
        var refreshLandsMission = true
        var missionsSupported = true
        var lookupGate: CompletableDeferred<Unit>? = null

        val lookups = mutableListOf<Int>()
        val itemResolves = mutableListOf<Int>()
        val refreshes = mutableListOf<String>()
        val navigated = mutableListOf<GoToTarget>()

        fun item(num: Int, title: String = "Fix the login form") {
            localItems[num] = TrackerItem(id = "i$num", num = num, kind = ItemKind.TASK, title = title, originConvoID = "c1")
        }

        fun mission(num: Int, title: String = "Launch the beta") {
            localMissions[num] = Mission(id = "m$num", num = num, title = title, originConvoID = "c1")
            cachedMissionIDs += "m$num"
        }

        val resolver = GoToNumberResolver(
            localItem = { num -> localReadError?.let { throw it }; localItems[num] },
            localMission = { num -> localReadError?.let { throw it }; localMissions[num] },
            lookup = { num ->
                lookups += num
                lookupGate?.await()
                lookupAnswer ?: throw lookupError
            },
            resolveItem = { num -> itemResolves += num; itemResolution },
            missionCached = { it in cachedMissionIDs },
            refreshMission = { id ->
                refreshes += id
                if (refreshOutcome == MissionsRefreshOutcome.Succeeded && refreshLandsMission) cachedMissionIDs += id
                refreshOutcome
            },
            missionsSupported = { missionsSupported },
        )

        val vm = GoToNumberViewModel(resolver) { navigated += it }

        suspend fun go(text: String): Boolean {
            vm.setText(text)
            return vm.go()
        }

        val hit: NumberHit? get() = (vm.result.value as? GoToNumberResult.Hit)?.hit
    }

    /// Types [text] the way the dialog does: each edit cancels the lookup
    /// before it and starts its own.
    private class Typist(private val scope: TestScope, private val w: World) {
        private var lookup: Job? = null

        fun type(text: String) {
            w.vm.setText(text)
            lookup?.cancel()
            lookup = scope.launch { w.vm.lookUp() }
            scope.runCurrent()
        }

        /// Lets the debounce run out and everything it started finish.
        fun settle() {
            scope.advanceTimeBy(GoToNumberViewModel.DEBOUNCE_MS)
            scope.runCurrent()
        }
    }

    // MARK: Parsing

    @Test
    fun parsesABareOrHashedNumber() {
        assertEquals(412, GoToNumberParser.parse("412"))
        assertEquals(412, GoToNumberParser.parse("#412"))
        assertEquals(412, GoToNumberParser.parse("  #412\n"))
        assertEquals(7, GoToNumberParser.parse(" 7 "))
        assertEquals(Int.MAX_VALUE, GoToNumberParser.parse("2147483647"))
    }

    @Test
    fun rejectsEverythingElse() {
        for (text in listOf(
            "", "   ", "#", "abc", "412a", "a412", "4 12", "# 412", "##412", "-412", "+412", "4.5",
            "0", "#0", "2147483648", "99999999999999999999", "number 412", "#412!",
        )) {
            assertNull("\"$text\"", GoToNumberParser.parse(text))
        }
    }

    // MARK: Find

    @Test
    fun aLocalItemIsFoundWithoutAskingTheJournal() = runTest {
        val w = World().apply { item(412) }
        assertEquals(
            GoToNumberResolver.Found.Hit(NumberHit(NumberKind.ITEM, 412, "Fix the login form", "i412")),
            w.resolver.find(412),
        )
        assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun aLocalMissionIsFoundWithoutAskingTheJournal() = runTest {
        val w = World().apply { mission(7) }
        assertEquals(
            GoToNumberResolver.Found.Hit(NumberHit(NumberKind.MISSION, 7, "Launch the beta", "m7")),
            w.resolver.find(7),
        )
        assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun aLocalMissAsksTheJournalOnce() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.MILESTONE, id = "ms1", num = 412, title = "Shipped", missionID = "m7", missionNum = 7)
        }
        assertEquals(
            GoToNumberResolver.Found.Hit(NumberHit(NumberKind.MILESTONE, 412, "Shipped", "ms1", missionID = "m7")),
            w.resolver.find(412),
        )
        assertEquals(listOf(412), w.lookups)
    }

    /// An older journal omits `num`; the number asked for stands in.
    @Test
    fun aLookupWithoutANumberKeepsTheOneAskedFor() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i1") }
        assertEquals(GoToNumberResolver.Found.Hit(NumberHit(NumberKind.ITEM, 412, null, "i1")), w.resolver.find(412))
    }

    @Test
    fun a404IsMissingAndAnyOtherFailureIsFailed() = runTest {
        val w = World()
        assertEquals(GoToNumberResolver.Found.Missing, w.resolver.find(412))
        w.lookupError = JournalApiError.Transport("offline")
        val failed = w.resolver.find(412) as GoToNumberResolver.Found.Failed
        assertEquals("Couldn't look up #412. Try again in a moment.", failed.message)
        w.lookupError = IllegalStateException("")
        assertEquals(failed, w.resolver.find(412))
    }

    /// A broken local read must not hide a number the journal knows.
    @Test
    fun aThrowingLocalReadFallsThroughToTheJournal() = runTest {
        val w = World().apply {
            localReadError = IllegalStateException("store")
            lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i1", num = 412)
        }
        assertTrue(w.resolver.find(412) is GoToNumberResolver.Found.Hit)
    }

    @Test
    fun cancellationIsNeverReportedAsAResult() = runTest {
        val w = World().apply { lookupError = CancellationException("dismissed") }
        try {
            w.resolver.find(412); fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        w.localReadError = CancellationException("dismissed")
        try {
            w.resolver.find(412); fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
    }

    // MARK: Go (find + open)

    @Test
    fun anItemIsSyncedByNumberThenOpened() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i412", num = 412)
            itemResolution = TrackerItemLinkResolver.Resolution.Open("i412")
        }
        assertTrue(w.go("#412"))
        assertEquals(listOf(412), w.itemResolves)
        assertEquals(listOf<GoToTarget>(GoToTarget.Item("i412")), w.navigated)
        assertNull(w.vm.message.value); assertFalse(w.vm.isBusy.value)
    }

    @Test
    fun anItemThatWillNotSyncExplainsItselfInline() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i412", num = 412) }
        assertFalse(w.go("412"))
        assertEquals("Item #${412} isn't on this device yet.", w.vm.message.value)
        w.itemResolution = TrackerItemLinkResolver.Resolution.Failed("offline")
        assertFalse(w.go("412"))
        assertEquals("Couldn't open item #${412} — offline", w.vm.message.value)
        assertTrue(w.navigated.isEmpty())
    }

    @Test
    fun aCachedMissionOpensWithoutARefresh() = runTest {
        val w = World().apply { mission(7) }
        assertTrue(w.go("7"))
        assertEquals(listOf<GoToTarget>(GoToTarget.Mission("m7")), w.navigated)
        assertTrue(w.refreshes.isEmpty()); assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun anUncachedMissionIsFetchedFirst() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.MISSION, id = "m7", num = 7) }
        assertTrue(w.go("7"))
        assertEquals(listOf("m7"), w.refreshes)
        assertEquals(listOf<GoToTarget>(GoToTarget.Mission("m7")), w.navigated)
    }

    @Test
    fun aMissionThatCannotBeFetchedExplainsItselfInline() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.MISSION, id = "m7", num = 7)
            refreshOutcome = MissionsRefreshOutcome.Failed("offline")
        }
        assertFalse(w.go("7"))
        assertEquals("Couldn't open #7 — offline", w.vm.message.value)
        w.refreshOutcome = MissionsRefreshOutcome.Stopped
        assertFalse(w.go("7"))
        assertEquals("Couldn't open #7", w.vm.message.value)
        w.refreshOutcome = MissionsRefreshOutcome.Succeeded
        w.refreshLandsMission = false
        assertFalse(w.go("7"))
        assertEquals("a refresh that left nothing to show", "Couldn't open #7", w.vm.message.value)
        w.refreshOutcome = MissionsRefreshOutcome.Unsupported
        assertFalse(w.go("7"))
        assertEquals(GoToNumberCopy.missionsUnsupported(7), w.vm.message.value)
        assertTrue(w.navigated.isEmpty())
    }

    @Test
    fun aMissionOnAJournalWithoutMissionsSaysSo() = runTest {
        val w = World().apply { mission(7); missionsSupported = false }
        assertFalse(w.go("7"))
        assertEquals("#7 is a mission. Missions aren't available on this server.", w.vm.message.value)
        assertTrue(w.navigated.isEmpty()); assertTrue(w.refreshes.isEmpty())
    }

    @Test
    fun aMilestoneOpensItsMission() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.MILESTONE, id = "ms1", num = 412, missionID = "m7", missionNum = 7)
        }
        assertTrue(w.go("412"))
        assertEquals("the mission is fetched, not the milestone", listOf("m7"), w.refreshes)
        assertEquals(listOf<GoToTarget>(GoToTarget.Mission("m7")), w.navigated)
    }

    @Test
    fun aMilestoneWithoutAMissionCannotOpen() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.MILESTONE, id = "ms1", num = 412) }
        assertFalse(w.go("412"))
        assertEquals("Couldn't open #412", w.vm.message.value)
        assertTrue(w.refreshes.isEmpty())
    }

    /// Reached by pressing Go before the lookup answered: the row and its
    /// note appear, nothing opens, and Go has nothing left to do.
    @Test
    fun aProjectIsShownButNeverOpened() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.PROJECT, id = "p1", num = 412, title = "Beta") }
        assertFalse(w.go("412"))
        assertEquals(NumberHit(NumberKind.PROJECT, 412, "Beta", "p1"), w.hit)
        assertNull(w.vm.message.value)
        assertFalse(w.vm.canGo)
        assertFalse(w.vm.go())
        assertEquals("asked once, not again by the second Go", listOf(412), w.lookups)
        assertTrue(w.navigated.isEmpty())
    }

    @Test
    fun noSuchNumberAndAFailedLookupAreDifferentAnswers() = runTest {
        val w = World()
        assertFalse(w.go("412"))
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
        w.lookupError = JournalApiError.Transport("")
        assertFalse("Go asks again after a miss", w.go("412"))
        assertEquals(GoToNumberResult.Failed(412, "Couldn't look up #412. Try again in a moment."), w.vm.result.value)
        assertEquals(listOf(412, 412), w.lookups)
        assertNull(w.vm.message.value)
    }

    @Test
    fun invalidTextNeverReachesTheResolverAndShowsNothing() = runTest {
        val w = World()
        val typist = Typist(this, w)
        for (text in listOf("four", "4 12", "#", "")) {
            typist.type(text)
            typist.settle()
            assertFalse("\"$text\"", w.vm.canGo)
            assertFalse(w.vm.go())
            assertNull(w.vm.result.value); assertNull(w.vm.message.value); assertFalse(w.vm.isLooking.value)
        }
        assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun editingClearsTheResultAndTheMessage() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i412", num = 412) }
        assertFalse(w.go("412"))
        assertEquals("Item #${412} isn't on this device yet.", w.vm.message.value)
        w.vm.setText("412")
        assertEquals("the same text keeps its message", "Item #${412} isn't on this device yet.", w.vm.message.value)
        w.vm.setText("#412")
        assertNull(w.vm.message.value)
        assertEquals("the same number keeps its row", 412, w.hit?.num)
        w.vm.setText("413")
        assertNull(w.vm.result.value)
    }

    @Test
    fun busyWhileGoIsOutAndASecondGoIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val w = World().apply { lookupGate = gate }
        w.vm.setText("412")
        val first = launch { w.vm.go() }
        yield()
        assertTrue(w.vm.isBusy.value)
        assertFalse("a second Go while busy does nothing", w.vm.go())
        assertEquals(listOf(412), w.lookups)
        gate.complete(Unit)
        first.join()
        assertFalse(w.vm.isBusy.value)
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
    }

    /// Dismissing the dialog cancels its scope: no navigation, not left busy.
    @Test
    fun aCancelledGoNeitherNavigatesNorStaysBusy() = runTest {
        val w = World().apply {
            lookupGate = CompletableDeferred()
            lookupAnswer = NumberLookup(NumberKind.MISSION, id = "m7", num = 7)
        }
        w.vm.setText("7")
        val going = launch { w.vm.go() }
        yield()
        going.cancel(); going.join()
        assertFalse(w.vm.isBusy.value)
        assertTrue(w.navigated.isEmpty()); assertNull(w.vm.message.value)
    }

    // MARK: Look up as you type

    @Test
    fun aSettledNumberShowsItsRowWithoutGo() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i412", num = 412, title = "Fix the login form") }
        val typist = Typist(this, w)
        typist.type("412")
        assertTrue(w.vm.isLooking.value)
        advanceTimeBy(GoToNumberViewModel.DEBOUNCE_MS - 1)
        assertTrue("nothing is asked until the text settles", w.lookups.isEmpty())
        assertNull(w.vm.result.value)
        typist.settle()
        assertEquals(GoToNumberResult.Hit(412, NumberHit(NumberKind.ITEM, 412, "Fix the login form", "i412")), w.vm.result.value)
        assertFalse(w.vm.isLooking.value)
        assertTrue(w.vm.canGo)
        assertTrue("showing is not opening", w.navigated.isEmpty() && w.itemResolves.isEmpty())
    }

    @Test
    fun aLocalNumberShowsItsRowWithoutAskingTheJournal() = runTest {
        val w = World().apply { mission(7) }
        val typist = Typist(this, w)
        typist.type("#7")
        typist.settle()
        assertEquals(NumberHit(NumberKind.MISSION, 7, "Launch the beta", "m7"), w.hit)
        assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun aNumberNothingCarriesSaysSoQuietly() = runTest {
        val w = World()
        val typist = Typist(this, w)
        typist.type("412")
        typist.settle()
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
        assertNull("not an error", w.vm.message.value)
        assertTrue("Go may ask again", w.vm.canGo)
    }

    @Test
    fun aTypedProjectShowsItsRowAndDisablesGo() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.PROJECT, id = "p1", num = 412, title = "Beta") }
        val typist = Typist(this, w)
        typist.type("412")
        assertTrue("Go is offered until the answer says otherwise", w.vm.canGo)
        typist.settle()
        val result = w.vm.result.value as GoToNumberResult.Hit
        assertEquals(NumberHit(NumberKind.PROJECT, 412, "Beta", "p1"), result.hit)
        assertFalse(result.canOpen)
        assertFalse(w.vm.canGo)
        assertFalse(w.vm.go())
        assertTrue(w.navigated.isEmpty())
    }

    @Test
    fun aFailedLookupIsAnErrorThatGoRetries() = runTest {
        val w = World().apply { lookupError = JournalApiError.Transport("offline") }
        val typist = Typist(this, w)
        typist.type("412")
        typist.settle()
        assertEquals(GoToNumberResult.Failed(412, GoToNumberCopy.lookupFailed(412)), w.vm.result.value)
        assertTrue(w.vm.canGo)
        w.lookupAnswer = NumberLookup(NumberKind.MISSION, id = "m7", num = 412)
        assertTrue(w.vm.go())
        assertEquals(listOf<GoToTarget>(GoToTarget.Mission("m7")), w.navigated)
    }

    @Test
    fun eachKeystrokeRestartsTheWaitAndOnlyTheSettledNumberIsAsked() = runTest {
        val w = World()
        val typist = Typist(this, w)
        for (text in listOf("4", "41", "412")) {
            typist.type(text)
            advanceTimeBy(GoToNumberViewModel.DEBOUNCE_MS - 1)
        }
        assertTrue(w.lookups.isEmpty())
        typist.settle()
        assertEquals(listOf(412), w.lookups)
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
    }

    /// The answer for an older number arrives after the text moved on. The
    /// caller does NOT cancel here: the view model alone must drop it.
    @Test
    fun aLateAnswerForAnOlderNumberIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val w = World().apply {
            lookupGate = gate
            lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i41", num = 41, title = "Old")
        }
        w.vm.setText("41")
        val old = launch { w.vm.lookUp() }
        advanceTimeBy(GoToNumberViewModel.DEBOUNCE_MS); runCurrent()
        assertEquals(listOf(41), w.lookups)

        w.vm.setText("412")
        assertFalse(w.vm.isLooking.value)
        w.lookupAnswer = null
        gate.complete(Unit)
        old.join()
        assertNull("the answer for number 41 never shows", w.vm.result.value)

        w.lookupGate = null
        launch { w.vm.lookUp() }
        advanceTimeBy(GoToNumberViewModel.DEBOUNCE_MS); runCurrent()
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
        assertFalse(w.vm.isLooking.value)
    }

    /// Cancelling the older lookup (what the dialog does) must not clear the
    /// newer one's progress mark on its way out.
    @Test
    fun aCancelledLookupLeavesTheNewerOneLooking() = runTest {
        val w = World().apply { lookupGate = CompletableDeferred() }
        val typist = Typist(this, w)
        typist.type("41")
        typist.settle()
        typist.type("412")
        assertTrue(w.vm.isLooking.value)
        assertNull(w.vm.result.value)
        w.lookupGate = null
        typist.settle()
        assertFalse(w.vm.isLooking.value)
        assertEquals(GoToNumberResult.Missing(412), w.vm.result.value)
    }

    @Test
    fun goBeforeTheAnswerResolvesAtOnceAndOpens() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.ITEM, id = "i412", num = 412)
            itemResolution = TrackerItemLinkResolver.Resolution.Open("i412")
        }
        val typist = Typist(this, w)
        typist.type("412")
        assertTrue("a number is enough for Go", w.vm.canGo)
        assertTrue("one press, no waiting for the debounce", w.vm.go())
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf<GoToTarget>(GoToTarget.Item("i412")), w.navigated)
        assertFalse(w.vm.isLooking.value)
        // The debounced lookup, if it still runs, finds the number answered.
        typist.settle()
        assertEquals(listOf(412), w.lookups)
    }

    @Test
    fun goOnAShownRowOpensWithoutAskingAgain() = runTest {
        val w = World().apply {
            lookupAnswer = NumberLookup(NumberKind.MILESTONE, id = "ms1", num = 412, title = "Shipped", missionID = "m7", missionNum = 7)
        }
        val typist = Typist(this, w)
        typist.type("412")
        typist.settle()
        assertEquals(NumberKind.MILESTONE, w.hit?.kind)
        assertTrue(w.vm.go())
        assertEquals(listOf(412), w.lookups)
        assertEquals("a milestone opens its mission", listOf<GoToTarget>(GoToTarget.Mission("m7")), w.navigated)
    }

    @Test
    fun goIsOfferedOnlyForANumberThatMayOpen() {
        val project = GoToNumberResult.Hit(412, NumberHit(NumberKind.PROJECT, 412, null, "p1"))
        assertTrue(GoToNumberViewModel.canGo("412", null, busy = false))
        assertTrue(GoToNumberViewModel.canGo(" #412 ", GoToNumberResult.Missing(412), busy = false))
        assertFalse(GoToNumberViewModel.canGo("412", null, busy = true))
        assertFalse(GoToNumberViewModel.canGo("", null, busy = false))
        assertFalse(GoToNumberViewModel.canGo("4a", null, busy = false))
        assertFalse(GoToNumberViewModel.canGo("412", project, busy = false))
        assertTrue("a stale project answer is for another number", GoToNumberViewModel.canGo("413", project, busy = false))
    }

    @Test
    fun copy() {
        assertEquals("Go to number", GoToNumberCopy.TITLE)
        assertEquals("Go", GoToNumberCopy.GO)
        assertEquals("Nothing is numbered 412.", GoToNumberCopy.nothingNumbered(412))
        assertEquals("Couldn't look up #412. Try again in a moment.", GoToNumberCopy.lookupFailed(412))
        assertEquals("Projects aren't available in this app yet.", GoToNumberCopy.PROJECT_NOTE)
        assertEquals("#412 is a project. Projects aren't available in this app yet.", GoToNumberCopy.project(412))
        assertEquals("Item #${412}", GoToNumberCopy.rowHeading(NumberHit(NumberKind.ITEM, 412, null, "i1")))
        assertEquals("Mission #${412}", GoToNumberCopy.rowHeading(NumberHit(NumberKind.MISSION, 412, null, "m1")))
        assertEquals("Milestone #${412}", GoToNumberCopy.rowHeading(NumberHit(NumberKind.MILESTONE, 412, null, "ms1")))
        assertEquals("Project #${412}", GoToNumberCopy.rowHeading(NumberHit(NumberKind.PROJECT, 412, null, "p1")))
        assertEquals("#412 · Item", GoToNumberCopy.hitHeading(NumberHit(NumberKind.ITEM, 412, null, "i1")))
        assertEquals("#7 · Mission", GoToNumberCopy.hitHeading(NumberHit(NumberKind.MISSION, 7, null, "m7")))
        assertEquals("#9 · Milestone", GoToNumberCopy.hitHeading(NumberHit(NumberKind.MILESTONE, 9, null, "ms9")))
        assertEquals("#3 · Project", GoToNumberCopy.hitHeading(NumberHit(NumberKind.PROJECT, 3, null, "p3")))
    }
}
