package chat.matron.android.viewmodels

import chat.matron.android.journal.Base64URL
import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.RendezvousCrypto
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Show-side of the reverse QR flow (spec §2): a signed-out device that
/// can't scan generates a single-use offer key, asks the shared relay for a
/// rendezvous, and renders {rid, key} as a v=2 QR, then polls. When a
/// signed-in phone scans it and posts an opaque box (server+code sealed under
/// that key), this VM opens the box locally and hands {server, code} to the
/// existing [LinkSignInViewModel] — from there the flow is byte-for-byte the
/// shipped claim → approve → token path against the user's own journal. The
/// relay only ever holds ciphertext; it never sees the key, a token, or a
/// readable {server, code} (rendezvous-offer-encryption spec §4.2).
class RendezvousSignInViewModel(
    private val relay: RelayRendezvousing,
    private val link: LinkSignInViewModel,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
    private val keyProvider: () -> ByteArray = { RendezvousCrypto.generateKey() },
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
        val key = keyProvider()
        _state.value = State.Showing(RendezvousURI.format(rendezvous.rid, key))
        startPolling(rendezvous.rid, rendezvous.secret, key, gen)
    }

    private fun startPolling(rid: String, secret: String, key: ByteArray, gen: Long) {
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
                        // Open the box locally with the key we published in the QR. An
                        // undecryptable/malformed box (someone who knows only the rid — not
                        // the key — occupied the slot with garbage) is treated exactly like
                        // an expired rendezvous: regenerate and keep showing.
                        val offer = openOffer(result.box, key)
                        if (offer == null) {
                            createAndShow(gen)
                            return@launch
                        }
                        val (server, code) = offer
                        _state.value = State.Connecting(server.toHttpUrlOrNull()?.host ?: server)
                        link.serverURL = server
                        link.codeInput = code
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
                        when (link.state.value) {
                            is LinkSignInViewModel.State.Claiming,
                            is LinkSignInViewModel.State.WaitingForApproval,
                            is LinkSignInViewModel.State.SignedIn,
                            -> Unit
                            is LinkSignInViewModel.State.Idle,
                            is LinkSignInViewModel.State.Error,
                            -> {
                                _state.value = State.Error(
                                    "Couldn't connect to that computer's session — try again.",
                                )
                            }
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /// Decrypt and parse a polled offer box. Returns null on any failure
    /// (base64 decode, auth failure, non-JSON, or missing fields) — the caller
    /// regenerates. submitManual() re-validates `server` via
    /// ServerURLValidator on the way into the claim, so no separate URL
    /// validation is needed here.
    private fun openOffer(box: String, key: ByteArray): Pair<String, String>? = try {
        val bytes = Base64URL.decode(box) ?: return null
        val plaintext = RendezvousCrypto.open(bytes, key)
        val obj = Json.parseToJsonElement(plaintext.decodeToString()).jsonObject
        val server = obj["server"]?.jsonPrimitive?.content
        val code = obj["code"]?.jsonPrimitive?.content
        if (server == null || code == null) null else server to code
    } catch (e: Throwable) {
        null
    }
}
