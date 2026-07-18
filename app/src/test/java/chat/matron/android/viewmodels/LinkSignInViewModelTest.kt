package chat.matron.android.viewmodels

import chat.matron.android.auth.FakeAuthService
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkApproval
import chat.matron.android.journal.LinkClaim
import chat.matron.android.journal.LinkPollResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLinkClaimer : LinkClaiming {
    var claimResult: Result<LinkClaim> = Result.success(LinkClaim("aa11", 60))
    /// Consumed one per poll; last repeats when dry.
    var pollScript = mutableListOf<Result<LinkPollResult>>(Result.success(LinkPollResult.Pending))
    val claimedCodes = mutableListOf<String>()
    val claimedDeviceNames = mutableListOf<String>()
    var pollCount = 0

    /// Generation-guard test hook (mirrors `FakeDeviceLinker.gateLinkStart` in
    /// `DeviceLinkViewModelTest.kt`): when [gateClaim] is set, the next
    /// `linkClaim()` call records the attempt, signals [claimGateReached],
    /// then blocks until [releaseClaim] is called — lets a test park a claim
    /// call mid-network-hop to exercise `LinkSignInViewModel`'s
    /// cancel-during-in-flight-claim guard.
    var gateClaim = false
    val claimGateReached = CompletableDeferred<Unit>()
    private val claimRelease = CompletableDeferred<Unit>()

    fun releaseClaim() {
        claimRelease.complete(Unit)
    }

    override suspend fun linkClaim(code: String, deviceName: String): LinkClaim {
        claimedCodes.add(code)
        claimedDeviceNames.add(deviceName)
        if (gateClaim) {
            claimGateReached.complete(Unit)
            claimRelease.await()
        }
        return claimResult.getOrThrow()
    }
    /// Poll-side twin of [gateClaim]: parks the next `linkPoll()` call at
    /// [pollGateReached] until [releasePoll]. The park is `NonCancellable` on
    /// purpose — it models a response that the transport has already
    /// delivered, so `pollTask?.cancel()` cannot reach it and only the
    /// view model's post-await generation guard can stop the resumed code.
    var gatePoll = false
    val pollGateReached = CompletableDeferred<Unit>()
    private val pollRelease = CompletableDeferred<Unit>()

    fun releasePoll() {
        pollRelease.complete(Unit)
    }

    override suspend fun linkPoll(claimToken: String): LinkPollResult {
        pollCount += 1
        if (gatePoll) {
            pollGateReached.complete(Unit)
            withContext(NonCancellable) { pollRelease.await() }
        }
        return (if (pollScript.size > 1) pollScript.removeAt(0) else pollScript[0]).getOrThrow()
    }
}

class LinkSignInViewModelTest {
    private val scannedURI = "matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8"

    private fun makeVM(fake: FakeLinkClaimer, scope: CoroutineScope, auth: FakeAuthService = FakeAuthService()) =
        LinkSignInViewModel(
            auth = auth,
            deviceDisplayName = "Matron Android",
            scope = scope,
            apiFactory = { fake },
            pollInterval = 1.milliseconds,
            errorPollInterval = 1.milliseconds,
        )

    @Test
    fun scanned_happyPath_buildsAndPersistsSession() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.success(LinkPollResult.Pending),
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val auth = FakeAuthService()
            val vm = makeVM(fake, scope, auth)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            val session = (vm.state.value as LinkSignInViewModel.State.SignedIn).session
            assertEquals("dan", session.userID)               // username, never typed
            assertEquals("42", session.deviceID)
            assertEquals("https://chat.example.com", session.homeserverURL)
            assertEquals("tok99", session.accessToken)
            assertEquals(listOf(session), auth.persistedSessions) // persisted BEFORE state flips
            assertEquals(listOf("KTNM-3VQ8"), fake.claimedCodes)
            assertEquals(listOf("Matron Android"), fake.claimedDeviceNames)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun scanned_notALink_andWrongVersion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeLinkClaimer(), scope)
            vm.handleScanned("https://a-random-website.example/qr")
            assertEquals(LinkSignInViewModel.State.Error("Not a Matron sign-in code."), vm.state.value)
            vm.handleScanned("matron://link?v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            assertEquals(
                LinkSignInViewModel.State.Error("This QR code needs a newer version of Matron."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun manual_happyPath_normalizesCodeAndURL() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val vm = makeVM(fake, scope)
            vm.serverURL = "chat.example.com" // ServerURLValidator adds https://
            vm.codeInput = "ktnm3vq8"
            assertEquals("KTNM-3VQ8", vm.codeInput) // auto-format like PairingViewModel
            vm.submitManual()
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            assertEquals(listOf("KTNM-3VQ8"), fake.claimedCodes)
            val session = (vm.state.value as LinkSignInViewModel.State.SignedIn).session
            assertTrue(session.homeserverURL.startsWith("https://chat.example.com"))
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun manual_invalidURL_errors() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeLinkClaimer(), scope)
            vm.serverURL = "not a url"
            vm.codeInput = "KTNM-3VQ8"
            vm.submitManual()
            assertEquals(
                LinkSignInViewModel.State.Error("That doesn't look like a valid server URL."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun claim_conflict_notFound_rateLimited() = runBlocking {
        val cases = listOf(
            JournalApiError.Conflict to "This code was already used. Generate a new one on your signed-in device.",
            JournalApiError.NotFound to "Code not recognized or expired. Show a fresh QR code and try again.",
            JournalApiError.RateLimited to "Too many attempts — try again in a minute.",
        )
        for ((error, message) in cases) {
            val scope = CoroutineScope(coroutineContext + Job())
            try {
                val fake = FakeLinkClaimer()
                fake.claimResult = Result.failure(error)
                val vm = makeVM(fake, scope)
                vm.handleScanned(scannedURI)
                assertEquals(LinkSignInViewModel.State.Error(message), vm.state.value)
            } finally { scope.cancel() }
        }
        Unit
    }

    @Test
    fun poll_denied() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(Result.success(LinkPollResult.Denied))
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.Error }
            assertEquals(
                LinkSignInViewModel.State.Error("Sign-in was denied on the other device."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun poll_expired() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(Result.failure(JournalApiError.NotFound))
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.Error }
            assertEquals(LinkSignInViewModel.State.Error("Sign-in expired. Scan again."), vm.state.value)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun poll_transportError_backsOffAndKeepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.failure(JournalApiError.Transport("offline")),
                Result.success(LinkPollResult.Approved(LinkApproval("t", 1, 1, "dan"))),
            )
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            assertTrue(vm.state.value is LinkSignInViewModel.State.SignedIn) // one dropped poll never kills the flow
        } finally { scope.cancel() }
        Unit
    }

    // Regression for the cancellation race mirrored from matron-apple's
    // LinkSignInViewModel fix: cancel() landing while linkClaim() is still
    // in flight must not let the resumed success path flip to
    // WaitingForApproval or spawn an orphan poll loop. The claim coroutine is
    // launched on a caller-provided scope (mirroring SignInScreen's
    // screen-local rememberCoroutineScope, distinct from the VM's injected
    // `scope` used only for polling) so cancel() cannot reach it directly —
    // only the generation guard can.
    @Test
    fun cancel_duringInFlightClaim_abandonsClaimSilently() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val callerScope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.gateClaim = true
            val auth = FakeAuthService()
            val vm = makeVM(fake, scope, auth)
            val claimJob = callerScope.launch { vm.handleScanned(scannedURI) }
            fake.claimGateReached.await()
            assertEquals(LinkSignInViewModel.State.Claiming, vm.state.value)

            vm.cancel()
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)

            fake.releaseClaim()
            claimJob.join()

            // Give any wrongly-spawned poll loop a chance to run.
            delay(50)
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)
            assertEquals(0, fake.pollCount)
            assertTrue(auth.persistedSessions.isEmpty())
        } finally {
            scope.cancel()
            callerScope.cancel()
        }
        Unit
    }

    // Regression for the poll-side twin of the claim race (bugbot, mirrors
    // matron-apple's poll-loop fix): cancel() landing while a linkPoll that
    // already has an Approved response is in flight must not let the resumed
    // loop body persist the session or flip state to SignedIn.
    @Test
    fun cancel_duringInFlightPoll_dropsLateApproval() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.gatePoll = true
            fake.pollScript = mutableListOf(
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val auth = FakeAuthService()
            val vm = makeVM(fake, scope, auth)
            vm.handleScanned(scannedURI)
            fake.pollGateReached.await()
            assertEquals(LinkSignInViewModel.State.WaitingForApproval, vm.state.value)

            vm.cancel()
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)

            fake.releasePoll()
            // Give the resumed (stale) poll body a chance to misbehave.
            delay(50)
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)
            assertTrue(auth.persistedSessions.isEmpty())
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun cancel_stopsPollingAndReturnsToIdle() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { fake.pollCount >= 1 }
            vm.cancel()
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)
            val count = fake.pollCount
            delay(50)
            assertEquals(count, fake.pollCount)
        } finally { scope.cancel() }
        Unit
    }
}
