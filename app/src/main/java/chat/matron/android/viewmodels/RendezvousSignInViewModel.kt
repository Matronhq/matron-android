package chat.matron.android.viewmodels

import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.RendezvousPollResult
import chat.matron.android.journal.RendezvousURI
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
            _state.value = State.Error("Couldn't reach the Matron relay — check your connection and try again.")
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
                        _state.value = State.Connecting(
                            result.server.toHttpUrlOrNull()?.host ?: result.server,
                        )
                        link.serverURL = result.server
                        link.codeInput = result.code
                        link.submitManual()
                        return@launch
                    }
                }
            }
        }
    }
}
