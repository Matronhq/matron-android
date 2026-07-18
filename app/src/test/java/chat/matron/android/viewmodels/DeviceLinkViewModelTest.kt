package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkStart
import chat.matron.android.journal.LinkStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    override suspend fun linkStatus(): LinkStatus {
        statusCount += 1
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
}
