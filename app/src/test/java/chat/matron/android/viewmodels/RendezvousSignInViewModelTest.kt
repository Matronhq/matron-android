package chat.matron.android.viewmodels

import chat.matron.android.auth.FakeAuthService
import chat.matron.android.journal.LinkApproval
import chat.matron.android.journal.LinkPollResult
import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.Rendezvous
import chat.matron.android.journal.RendezvousPollResult
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RID_1 = "23456789BCDFGHJKMNPQRSTVWX"
private val RID_2 = "X".repeat(26)
private val SECRET = "a".repeat(64)

class RendezvousSignInViewModelTest {

    private class FakeRelay : RelayRendezvousing {
        var createResults = mutableListOf<Result<Rendezvous>>(Result.success(Rendezvous(RID_1, SECRET, 180)))
        var createCount = 0
        var pollScript = mutableListOf<Result<RendezvousPollResult>>(Result.success(RendezvousPollResult.Waiting))
        var pollCount = 0
        var gatePoll = false
        val pollGateReached = CompletableDeferred<Unit>()
        private val pollRelease = CompletableDeferred<Unit>()
        fun releasePoll() { pollRelease.complete(Unit) }
        var offers = mutableListOf<Triple<String, String, String>>()

        override suspend fun createRendezvous(): Rendezvous {
            createCount += 1
            return (if (createResults.size > 1) createResults.removeAt(0) else createResults[0]).getOrThrow()
        }
        override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult {
            pollCount += 1
            if (gatePoll) {
                pollGateReached.complete(Unit)
                // Models a transport-delivered response the cancel can't reach
                // (same pattern as FakeLinkClaimer.linkPoll).
                withContext(NonCancellable) { pollRelease.await() }
            }
            return (if (pollScript.size > 1) pollScript.removeAt(0) else pollScript[0]).getOrThrow()
        }
        override suspend fun offerRendezvous(rid: String, server: String, code: String) {
            offers.add(Triple(rid, server, code))
        }
    }

    private fun makeVMs(relay: FakeRelay, claimer: FakeLinkClaimer, scope: CoroutineScope, auth: FakeAuthService):
        Pair<RendezvousSignInViewModel, LinkSignInViewModel> {
        val link = LinkSignInViewModel(
            auth = auth, deviceDisplayName = "Matron Android", scope = scope,
            apiFactory = { claimer }, pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        val vm = RendezvousSignInViewModel(
            relay = relay, link = link, scope = scope,
            pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        return vm to link
    }

    @Test
    fun start_showsRlinkQR_thenOfferDrivesLinkSignInToCompletion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.pollScript = mutableListOf(
                Result.success(RendezvousPollResult.Waiting),
                Result.success(RendezvousPollResult.Offered("https://chat.example.com", "2345-6789")),
            )
            val claimer = FakeLinkClaimer()
            claimer.pollScript = mutableListOf(
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val auth = FakeAuthService()
            val (vm, link) = makeVMs(relay, claimer, scope, auth)

            vm.start()
            assertEquals(
                RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_1"),
                vm.state.value,
            )
            waitUntil { link.state.value is LinkSignInViewModel.State.SignedIn }
            assertEquals(RendezvousSignInViewModel.State.Connecting("chat.example.com"), vm.state.value)
            assertEquals(listOf("2345-6789"), claimer.claimedCodes)
            val session = (link.state.value as LinkSignInViewModel.State.SignedIn).session
            assertEquals("dan", session.userID)
            assertEquals(listOf(session), auth.persistedSessions)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun expiredRendezvous_silentlyRegenerates() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.createResults = mutableListOf(
                Result.success(Rendezvous(RID_1, SECRET, 180)),
                Result.success(Rendezvous(RID_2, "b".repeat(64), 180)),
            )
            relay.pollScript = mutableListOf(
                Result.failure(RelayError.NotFound()),
                Result.success(RendezvousPollResult.Waiting),
            )
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            waitUntil { relay.createCount == 2 }
            waitUntil { vm.state.value == RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_2") }
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun createFailure_isARetryableError() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.createResults = mutableListOf(Result.failure(RelayError.Transport("down")))
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            assertEquals(
                RendezvousSignInViewModel.State.Error("Couldn't reach the Matron relay — check your connection and try again."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun transientPollFailure_keepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.pollScript = mutableListOf(
                Result.failure(RelayError.Transport("blip")),
                Result.success(RendezvousPollResult.Waiting),
                Result.success(RendezvousPollResult.Waiting),
            )
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            waitUntil { relay.pollCount >= 3 }
            assertEquals(
                RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_1"),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun stop_duringInFlightPoll_dropsTheLateOffer() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.gatePoll = true
            relay.pollScript = mutableListOf(
                Result.success(RendezvousPollResult.Offered("https://chat.example.com", "2345-6789")),
            )
            val claimer = FakeLinkClaimer()
            val auth = FakeAuthService()
            val (vm, link) = makeVMs(relay, claimer, scope, auth)
            vm.start()
            relay.pollGateReached.await()
            vm.stop()
            relay.releasePoll()
            delay(50)
            assertEquals(RendezvousSignInViewModel.State.Idle, vm.state.value)
            assertEquals(LinkSignInViewModel.State.Idle, link.state.value)
            assertTrue(claimer.claimedCodes.isEmpty())
            assertTrue(auth.persistedSessions.isEmpty())
        } finally { scope.cancel() }
        Unit
    }
}
