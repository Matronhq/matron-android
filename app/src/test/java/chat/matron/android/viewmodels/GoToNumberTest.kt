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
import kotlinx.coroutines.launch
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
/// with one inline line saying what happened.
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
        assertTrue(failed.message, failed.message.startsWith("Couldn't look up #412"))
        w.lookupError = IllegalStateException("")
        assertEquals(GoToNumberResolver.Found.Failed("Couldn't look up #412"), w.resolver.find(412))
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

    @Test
    fun aProjectSaysProjectsAreNotAvailable() = runTest {
        val w = World().apply { lookupAnswer = NumberLookup(NumberKind.PROJECT, id = "p1", num = 412, title = "Beta") }
        assertFalse(w.go("412"))
        assertEquals("#412 is a project. Projects aren't available in this app yet.", w.vm.message.value)
        assertTrue(w.navigated.isEmpty())
    }

    @Test
    fun noSuchNumberAndAFailedLookupAreDifferentMessages() = runTest {
        val w = World()
        assertFalse(w.go("412"))
        assertEquals("No #412", w.vm.message.value)
        w.lookupError = JournalApiError.Transport("")
        assertFalse(w.go("412"))
        assertEquals("Couldn't look up #412 — Couldn't reach the server.", w.vm.message.value)
    }

    @Test
    fun invalidTextNeverReachesTheResolver() = runTest {
        val w = World()
        assertFalse(w.go("four"))
        assertEquals(GoToNumberCopy.INVALID, w.vm.message.value)
        assertTrue(w.lookups.isEmpty())
    }

    @Test
    fun editingClearsTheMessage() = runTest {
        val w = World()
        w.go("412")
        assertEquals("No #412", w.vm.message.value)
        w.vm.setText("412")
        assertEquals("the same text keeps its message", "No #412", w.vm.message.value)
        w.vm.setText("413")
        assertNull(w.vm.message.value)
    }

    @Test
    fun busyWhileTheLookupIsOutAndASecondGoIsIgnored() = runTest {
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
        assertEquals("No #412", w.vm.message.value)
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

    @Test
    fun copy() {
        assertEquals("Go to number", GoToNumberCopy.TITLE)
        assertEquals("Go", GoToNumberCopy.GO)
        assertEquals("#412 · Item", GoToNumberCopy.hitHeading(NumberHit(NumberKind.ITEM, 412, null, "i1")))
        assertEquals("#7 · Mission", GoToNumberCopy.hitHeading(NumberHit(NumberKind.MISSION, 7, null, "m7")))
        assertEquals("#9 · Milestone", GoToNumberCopy.hitHeading(NumberHit(NumberKind.MILESTONE, 9, null, "ms9")))
        assertEquals("#3 · Project", GoToNumberCopy.hitHeading(NumberHit(NumberKind.PROJECT, 3, null, "p3")))
    }
}
