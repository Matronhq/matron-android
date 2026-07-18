package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkStart
import chat.matron.android.journal.LinkStatus
import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.Rendezvous
import chat.matron.android.journal.RendezvousPollResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Scriptable show-side fake: `statusScript` is consumed one result per poll;
/// when it runs dry the last result repeats.
class FakeDeviceLinker : DeviceLinking {
    var startResults = mutableListOf<Result<LinkStart>>(Result.success(LinkStart("KTNM-3VQ8", 120)))
    var statusScript = mutableListOf<Result<LinkStatus>>(Result.success(LinkStatus.Waiting(100)))
    var approveResult: Result<Unit> = Result.success(Unit)
    var denyResult: Result<Unit> = Result.success(Unit)
    var startCount = 0
    var statusCount = 0
    val approvedCodes = mutableListOf<String>()
    val deniedCodes = mutableListOf<String>()

    /// Generation-guard test hook (mirrors `FakeSnapshotSource`'s gate in
    /// journal's `SyncEngineTestHelpers.kt`): when [gateLinkStart] is set,
    /// the next `linkStart()` call increments [startCount], signals
    /// [linkStartGateReached], then blocks until [releaseLinkStart] is
    /// called — lets a test park a start/regenerate call mid-network-hop to
    /// exercise `DeviceLinkViewModel`'s stop-during-in-flight-start guard.
    var gateLinkStart = false
    val linkStartGateReached = CompletableDeferred<Unit>()
    private val linkStartRelease = CompletableDeferred<Unit>()

    fun releaseLinkStart() {
        linkStartRelease.complete(Unit)
    }

    override suspend fun linkStart(): LinkStart {
        startCount += 1
        if (gateLinkStart) {
            linkStartGateReached.complete(Unit)
            linkStartRelease.await()
        }
        return (if (startResults.size > 1) startResults.removeAt(0) else startResults[0]).getOrThrow()
    }
    /// Status-side twin of [gateLinkStart]: parks the next `linkStatus()`
    /// call at [statusGateReached] until [releaseStatus]. The park is
    /// `NonCancellable` on purpose — it models a response the transport has
    /// already delivered, so `pollTask?.cancel()` cannot reach it and only
    /// the poll loop's post-await generation guard can stop the resumed body.
    var gateStatus = false
    val statusGateReached = CompletableDeferred<Unit>()
    private val statusRelease = CompletableDeferred<Unit>()

    fun releaseStatus() {
        statusRelease.complete(Unit)
    }

    override suspend fun linkStatus(): LinkStatus {
        statusCount += 1
        if (gateStatus) {
            statusGateReached.complete(Unit)
            withContext(NonCancellable) { statusRelease.await() }
        }
        return (if (statusScript.size > 1) statusScript.removeAt(0) else statusScript[0]).getOrThrow()
    }
    override suspend fun linkApprove(code: String) {
        approvedCodes.add(code)
        approveResult.getOrThrow()
    }
    override suspend fun linkDeny(code: String) {
        deniedCodes.add(code)
        denyResult.getOrThrow()
    }
}

private const val RLINK_RID = "23456789BCDFGHJKMNPQRSTVWX"
private const val RLINK_PAYLOAD = "matron://rlink?v=1&rid=23456789BCDFGHJKMNPQRSTVWX"

private class FakeRelayOffer : RelayRendezvousing {
    var offerResult: Result<Unit> = Result.success(Unit)
    val offers = mutableListOf<Triple<String, String, String>>()

    /// Gate-the-offer test hook, same shape as [FakeDeviceLinker.gateStatus]:
    /// parks the next `offerRendezvous()` call at [offerGateReached] until
    /// [releaseOffer] — lets a test drive a status poll while the offer is
    /// still in flight. `NonCancellable` on purpose, mirroring the status
    /// gate: models a call already committed to the wire.
    var gateOffer = false
    val offerGateReached = CompletableDeferred<Unit>()
    private val offerRelease = CompletableDeferred<Unit>()

    fun releaseOffer() {
        offerRelease.complete(Unit)
    }

    override suspend fun createRendezvous(): Rendezvous = error("unused")
    override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult = error("unused")
    override suspend fun offerRendezvous(rid: String, server: String, code: String) {
        offers.add(Triple(rid, server, code))
        if (gateOffer) {
            offerGateReached.complete(Unit)
            withContext(NonCancellable) { offerRelease.await() }
        }
        offerResult.getOrThrow()
    }
}

class DeviceLinkViewModelTest {
    private fun makeVM(fake: FakeDeviceLinker, scope: CoroutineScope) = DeviceLinkViewModel(
        api = fake,
        serverURL = "https://chat.example.com",
        scope = scope,
        pollInterval = 1.milliseconds,
        errorPollInterval = 1.milliseconds,
    )

    @Test
    fun start_showsCodeAndQRPayload() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeDeviceLinker(), scope)
            vm.start()
            assertEquals(DeviceLinkViewModel.Phase.Showing("KTNM-3VQ8"), vm.phase.value)
            assertEquals(
                chat.matron.android.journal.LinkURI.format("https://chat.example.com", "KTNM-3VQ8"),
                vm.qrPayload,
            )
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun start_notFound_meansServerTooOld() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.startResults = mutableListOf(Result.failure(JournalApiError.NotFound))
            val vm = makeVM(fake, scope)
            vm.start()
            assertEquals(DeviceLinkViewModel.Phase.Unsupported, vm.phase.value)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun claimedStatus_flipsToApproveCard() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(
                Result.success(LinkStatus.Waiting(100)),
                Result.success(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 90)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value == DeviceLinkViewModel.Phase.Claimed("Pixel 9", "198.51.100.7") }
            assertEquals(DeviceLinkViewModel.Phase.Claimed("Pixel 9", "198.51.100.7"), vm.phase.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    // Regression (bugbot, mirrors matron-apple's poll-loop fix): a linkStatus
    // response the transport already delivered when approve() lands resumes
    // the poll body on the cancelled job — it must not write Claimed over the
    // terminal Approved phase.
    @Test
    fun approve_duringInFlightStatusPoll_keepsTerminalPhase() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(
                Result.success(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 90)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }

            fake.gateStatus = true
            fake.statusGateReached.await() // a poll is now parked mid-network-hop
            vm.approve()
            assertEquals(DeviceLinkViewModel.Phase.Approved, vm.phase.value)

            fake.releaseStatus()
            // Give the resumed (stale) poll body a chance to misbehave.
            delay(50)
            assertEquals(DeviceLinkViewModel.Phase.Approved, vm.phase.value)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun statusNotFound_regeneratesSilently() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.startResults = mutableListOf(
                Result.success(LinkStart("KTNM-3VQ8", 120)),
                Result.success(LinkStart("WXYZ-2345", 120)),
            )
            fake.statusScript = mutableListOf(
                Result.failure(JournalApiError.NotFound),
                Result.success(LinkStatus.Waiting(100)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value == DeviceLinkViewModel.Phase.Showing("WXYZ-2345") }
            assertEquals(DeviceLinkViewModel.Phase.Showing("WXYZ-2345"), vm.phase.value)
            assertEquals(2, fake.startCount)
            assertNull(vm.noticeMessage.value) // expiry while waiting is routine, not an error
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun approve_isTerminalAndStopsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 90)))
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.approve()
            assertEquals(DeviceLinkViewModel.Phase.Approved, vm.phase.value)
            assertEquals(listOf("KTNM-3VQ8"), fake.approvedCodes)
            val countAtApprove = fake.statusCount
            delay(50)
            assertEquals(countAtApprove, fake.statusCount) // poll loop stopped
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun approve_expired_regeneratesWithNotice() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 5)))
            fake.approveResult = Result.failure(JournalApiError.NotFound)
            fake.startResults = mutableListOf(
                Result.success(LinkStart("KTNM-3VQ8", 120)),
                Result.success(LinkStart("WXYZ-2345", 120)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.approve()
            assertEquals(DeviceLinkViewModel.Phase.Showing("WXYZ-2345"), vm.phase.value)
            assertEquals("Code expired — showing a fresh one", vm.noticeMessage.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun deny_isTerminal() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 90)))
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.deny()
            assertEquals(DeviceLinkViewModel.Phase.Denied, vm.phase.value)
            assertEquals(listOf("KTNM-3VQ8"), fake.deniedCodes)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun stop_haltsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { fake.statusCount >= 1 }
            vm.stop()
            val count = fake.statusCount
            delay(50)
            assertEquals(count, fake.statusCount)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun transportErrorOnStatus_keepsShowingAndKeepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(
                Result.failure(JournalApiError.Transport("offline")),
                Result.success(LinkStatus.Waiting(90)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { fake.statusCount >= 2 }
            assertEquals(DeviceLinkViewModel.Phase.Showing("KTNM-3VQ8"), vm.phase.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    // --- Plan-owner amendment: generation guard against an orphaned poll loop ---
    //
    // `start()` (and the internal regenerate path it shares with the 404
    // auto-regenerate) is a *suspend* function driven by whatever coroutine
    // the caller (e.g. a Compose LaunchedEffect) happens to run it on — it is
    // NOT tracked by the view model's own `pollTask`. `stop()` cancelling
    // `pollTask` therefore cannot reach a `start()` call that's still parked
    // on the network hop. Without a guard, that stale call resumes after
    // `stop()`, mutates `phase` back to `Showing`, and spawns a brand new
    // poll loop that nothing can ever stop again (the Apple twin's "orphaned
    // poll loop" bug). This test parks `linkStart()` mid-flight, calls
    // `stop()`, then releases it and asserts the resumed call is a no-op.
    @Test
    fun stop_duringInFlightStart_abandonsStaleCallSilently() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.gateLinkStart = true
            val vm = makeVM(fake, scope)
            val startJob = scope.launch { vm.start() }
            fake.linkStartGateReached.await() // deterministic: parked on the network hop
            assertEquals(DeviceLinkViewModel.Phase.Loading, vm.phase.value)

            vm.stop() // lands while start() is still suspended in linkStart()
            fake.releaseLinkStart() // let the now-stale call resume
            startJob.join()

            assertEquals(DeviceLinkViewModel.Phase.Loading, vm.phase.value) // never advanced to Showing
            assertNull(vm.noticeMessage.value)
            val statusCountAfterResume = fake.statusCount
            delay(50) // an orphaned poll loop would have polled status by now
            assertEquals(statusCountAfterResume, fake.statusCount) // none spawned
        } finally { scope.cancel() }
        Unit
    }

    // --- Task 4: offerScanned (signed-in Scan side) ---

    @Test
    fun offerScanned_sendsTheLiveSessionCodeAndServer() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker().apply {
                startResults = mutableListOf(Result.success(LinkStart("2345-6789", 120)))
            }
            val relay = FakeRelayOffer()
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
            val startCountBefore = fake.startCount
            vm.offerScanned(RLINK_PAYLOAD)
            assertEquals(listOf(Triple(RLINK_RID, "https://chat.example.com", "2345-6789")), relay.offers)
            assertEquals("Sent — approve the request when it appears.", vm.noticeMessage.value)
            // Regression (apple review finding): offerScanned must never call
            // linkStart again — a second linkStart REPLACES the live session
            // whose code was just offered to the relay.
            assertEquals(startCountBefore, fake.startCount)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun offerScanned_parseFailures_neverTouchTheRelay() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val relay = FakeRelayOffer()
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
            vm.offerScanned("matron://rlink?v=9&rid=$RLINK_RID")
            assertEquals("This QR code needs a newer version of Matron.", vm.noticeMessage.value)
            vm.offerScanned("https://not-matron.example.com")
            assertEquals("Not a Matron link code.", vm.noticeMessage.value)
            assertTrue(relay.offers.isEmpty())
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun offerScanned_relayOutcomes_mapToNotices() = runBlocking {
        val cases = listOf(
            Result.failure<Unit>(RelayError.Conflict()) to "That code was already used by another device.",
            Result.failure<Unit>(RelayError.NotFound()) to "That code expired — ask the computer to show a fresh one.",
            Result.failure<Unit>(RelayError.Transport("down")) to "Couldn't reach the Matron relay — try again.",
        )
        for ((result, notice) in cases) {
            val scope = CoroutineScope(coroutineContext + Job())
            try {
                val fake = FakeDeviceLinker()
                val relay = FakeRelayOffer().apply { offerResult = result }
                val vm = DeviceLinkViewModel(
                    api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                    pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
                )
                vm.start()
                waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
                vm.offerScanned(RLINK_PAYLOAD)
                assertEquals(notice, vm.noticeMessage.value)
            } finally { scope.cancel() }
        }
        Unit
    }

    @Test
    fun offerScanned_withoutALiveCode_asksToRetry() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val relay = FakeRelayOffer()
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.offerScanned(RLINK_PAYLOAD) // start() never called — no Showing phase yet
            assertTrue(relay.offers.isEmpty())
            assertEquals("Still fetching a link code — try scanning again in a moment.", vm.noticeMessage.value)
        } finally { scope.cancel() }
        Unit
    }

    // --- Controller amendment B: terminal-state notice copy ---
    //
    // The brief's non-Showing guard used one notice for every non-Showing
    // phase. A scan landing while the show side is Claimed/Approved/Denied
    // is a different situation from "still loading" — there IS a session,
    // it's just already past the point where offering a fresh scan makes
    // sense, so it gets its own copy telling the user to finish it first.
    @Test
    fun offerScanned_duringTerminalPhase_saysFinishTheSessionFirst() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 90)))
            val relay = FakeRelayOffer()
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.offerScanned(RLINK_PAYLOAD)
            assertEquals(
                "A link session is already in progress — finish it before linking another device.",
                vm.noticeMessage.value,
            )
            assertTrue(relay.offers.isEmpty())
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun offerScanned_whileLoading_stillUsesTheFetchingNotice() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.gateLinkStart = true // parks start() in Loading, never reaches Showing
            val relay = FakeRelayOffer()
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            val startJob = scope.launch { vm.start() }
            fake.linkStartGateReached.await()
            assertEquals(DeviceLinkViewModel.Phase.Loading, vm.phase.value)
            vm.offerScanned(RLINK_PAYLOAD)
            assertEquals("Still fetching a link code — try scanning again in a moment.", vm.noticeMessage.value)
            assertTrue(relay.offers.isEmpty())
            fake.releaseLinkStart()
            startJob.join()
        } finally { scope.cancel() }
        Unit
    }

    // --- Controller amendment A: inhibit the status poll while the offer is
    // in flight ---
    //
    // approve()/deny() already guard the poll loop with `isSubmitting` while
    // their own network call is in flight, so a `linkStatus` response that
    // was already on the wire when the tap landed can't regenerate the
    // session out from under a terminal phase. offerScanned must get the
    // exact same protection around `offerRendezvous` — a NotFound that
    // resolves mid-offer must not regenerate the session whose code was
    // just handed to the relay.
    @Test
    fun offerScanned_duringInFlightStatusPoll_inhibitsRegenerationUntilOfferResolves() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker().apply {
                startResults = mutableListOf(
                    Result.success(LinkStart("2345-6789", 120)),
                    Result.success(LinkStart("WXYZ-2345", 120)),
                )
                statusScript = mutableListOf(
                    Result.failure(JournalApiError.NotFound),
                    Result.failure(JournalApiError.NotFound),
                    Result.success(LinkStatus.Waiting(100)),
                )
            }
            val relay = FakeRelayOffer().apply { gateOffer = true }
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }

            fake.gateStatus = true
            fake.statusGateReached.await() // a status poll is now parked mid-network-hop

            val offerJob = scope.launch { vm.offerScanned(RLINK_PAYLOAD) }
            relay.offerGateReached.await() // offer is now in flight — isSubmitting is true

            fake.releaseStatus() // the parked poll resumes with NotFound while isSubmitting is true
            delay(50) // give the resumed (stale) poll body a chance to misbehave
            assertEquals(1, fake.startCount) // no regeneration triggered
            assertEquals(DeviceLinkViewModel.Phase.Showing("2345-6789"), vm.phase.value)

            relay.releaseOffer()
            offerJob.join()
            assertEquals("Sent — approve the request when it appears.", vm.noticeMessage.value)

            // The offer has resolved and isSubmitting has cleared — the poll loop
            // must still be alive to notice the (still-404ing) session and
            // regenerate. Pre-fix, the `_isSubmitting` disjunct in the NotFound
            // handler RETURNS instead of skipping, permanently killing the loop when
            // the parked NotFound above resolved.
            waitUntil { vm.phase.value == DeviceLinkViewModel.Phase.Showing("WXYZ-2345") }
            assertEquals(DeviceLinkViewModel.Phase.Showing("WXYZ-2345"), vm.phase.value)
            assertEquals(2, fake.startCount)
        } finally { scope.cancel() }
        Unit
    }

    // --- Controller amendment B: reentrancy guard at offerScanned entry ---
    //
    // A double-fired scan callback (camera decode racing a fast tap, etc.)
    // must not stack a second offer on the one still in flight. Mirrors the
    // apple sibling: the reentrant call must return as a no-op before ever
    // reaching the relay.
    @Test
    fun offerScanned_reentrantCallWhileOfferInFlight_isANoOp() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val relay = FakeRelayOffer().apply { gateOffer = true }
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }

            val firstJob = scope.launch { vm.offerScanned(RLINK_PAYLOAD) }
            relay.offerGateReached.await() // first offer is now in flight — isSubmitting is true
            assertEquals(1, relay.offers.size)

            // The reentrant call must return immediately as a no-op rather than
            // parking on the relay's (single-release) gate a second time — wrap
            // in a timeout so a regression fails the test instead of hanging it.
            kotlinx.coroutines.withTimeout(2_000) { vm.offerScanned(RLINK_PAYLOAD) }
            assertEquals(1, relay.offers.size) // the reentrant call never reached the relay

            relay.releaseOffer()
            firstJob.join()
            assertEquals(1, relay.offers.size)
            assertEquals("Sent — approve the request when it appears.", vm.noticeMessage.value)
        } finally { scope.cancel() }
        Unit
    }
}
