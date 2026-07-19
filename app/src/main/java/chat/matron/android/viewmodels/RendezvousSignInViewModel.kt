package chat.matron.android.viewmodels

import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.RendezvousPollResult
import chat.matron.android.journal.RendezvousURI
import chat.matron.android.platform.Haptics
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Show-side of the reverse QR flow (spec §2): a signed-out device that
/// can't scan asks the shared relay for a rendezvous, renders it as a QR,
/// and polls. When a signed-in phone scans it and posts {server, code},
/// this VM hands both values to the existing [LinkSignInViewModel] — from
/// there the flow is byte-for-byte the shipped claim → approve → token
/// path against the user's own journal. The relay never sees a token.
class RendezvousSignInViewModel(
    private val relay: RelayRendezvousing,
    private val link: LinkSignInViewModel,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
    private val haptics: Haptics = Haptics.None,
) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Showing(val qrPayload: String) : State

        /// Shown before and during the claim so the user can see WHICH
        /// server the relay pointed us at (spec §4: compromised-relay
        /// transparency). The link VM's own states drive the rest.
        data class Connecting(val serverHost: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /// Enters the Error state, buzzing once by default. [buzz] is set false only
    /// when the delegated [link] VM has already entered its own Error and buzzed
    /// — this VM then mirrors the failure for its host-line UI without a second
    /// near-simultaneous error buzz.
    private fun fail(message: String, buzz: Boolean = true) {
        _state.value = State.Error(message)
        if (buzz) haptics.error()
    }

    // Same stale-async discipline as LinkSignInViewModel/DeviceLinkViewModel:
    // stop() bumps the generation; every post-suspension branch re-checks it
    // before touching state.
    private var generation = 0L
    private var pollTask: Job? = null

    suspend fun start() {
        generation++
        val gen = generation
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Loading
        createAndShow(gen)
    }

    fun stop() {
        generation++
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Idle
    }

    private suspend fun createAndShow(gen: Long) {
        val rendezvous = try {
            relay.createRendezvous()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            if (gen != generation) return
            fail("Couldn't reach the Matron relay — check your connection and try again.")
            return
        }
        if (gen != generation) return
        _state.value = State.Showing(RendezvousURI.format(rendezvous.rid))
        startPolling(rendezvous.rid, rendezvous.secret, gen)
    }

    private fun startPolling(rid: String, secret: String, gen: Long) {
        pollTask = scope.launch {
            while (isActive) {
                val result = try {
                    relay.pollRendezvous(rid, secret)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: RelayError.NotFound) {
                    if (gen != generation || !isActive) return@launch
                    // Rendezvous expired: silently regenerate — the mirror of
                    // the show-side's link-expiry regeneration.
                    createAndShow(gen)
                    return@launch
                } catch (e: Throwable) {
                    if (gen != generation || !isActive) return@launch
                    delay(errorPollInterval)
                    continue
                }
                if (gen != generation || !isActive) return@launch
                when (result) {
                    is RendezvousPollResult.Waiting -> delay(pollInterval)
                    is RendezvousPollResult.Offered -> {
                        // A scan/typed claim may already be in flight on the shared link
                        // VM. Hijacking it here would overwrite the user's entered
                        // server/code and pin this VM's Connecting host line over a wait
                        // that belongs to a different claim (spec §4 transparency). The
                        // relay's poll is a repeatable read — the offer survives until the
                        // rendezvous TTL — so defer: keep polling and pick the offer up if
                        // the link VM comes back to rest (SignedIn never resumes; the
                        // screen is closing and a live session must not be replaced).
                        when (link.state.value) {
                            is LinkSignInViewModel.State.Claiming,
                            is LinkSignInViewModel.State.WaitingForApproval,
                            is LinkSignInViewModel.State.SignedIn,
                            -> {
                                delay(pollInterval)
                                continue
                            }
                            is LinkSignInViewModel.State.Idle,
                            is LinkSignInViewModel.State.Error,
                            -> Unit
                        }
                        _state.value = State.Connecting(
                            result.server.toHttpUrlOrNull()?.host ?: result.server,
                        )
                        link.serverURL = result.server
                        link.codeInput = result.code
                        link.submitManual()
                        if (gen != generation || !isActive) return@launch
                        // submitManual() can return without ever starting a
                        // claim (its own early-return guards: empty/invalid
                        // server URL or code, or a claim already in
                        // progress), and the link VM's claim/poll can also
                        // land in its own Error phase. Either way the link VM
                        // is parked in Idle/Error with nothing left to drive
                        // it forward, so this VM must not sit in Connecting
                        // forever — surface a retryable error instead. The
                        // progressing phases (Claiming, WaitingForApproval,
                        // SignedIn) mean the claim is under way; the link
                        // VM's own state drives the UI from here.
                        val message = "Couldn't connect to that computer's session — try again."
                        when (link.state.value) {
                            is LinkSignInViewModel.State.Claiming,
                            is LinkSignInViewModel.State.WaitingForApproval,
                            is LinkSignInViewModel.State.SignedIn,
                            -> Unit
                            // The link VM reached Error via its own fail(), which
                            // already buzzed — mirror the message but don't
                            // double-buzz.
                            is LinkSignInViewModel.State.Error -> fail(message, buzz = false)
                            // submitManual() early-returned in Idle without ever
                            // buzzing — this buzz is the only failure feedback.
                            is LinkSignInViewModel.State.Idle -> fail(message)
                        }
                        return@launch
                    }
                }
            }
        }
    }
}
