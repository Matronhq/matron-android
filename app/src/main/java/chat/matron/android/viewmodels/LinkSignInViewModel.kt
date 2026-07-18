package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthService
import chat.matron.android.auth.ServerURLValidator
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkClaim
import chat.matron.android.journal.LinkPollResult
import chat.matron.android.journal.LinkURI
import chat.matron.android.journal.PairingCode
import chat.matron.android.models.UserSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// The claimant slice of [JournalApi] (both calls unauthenticated),
/// extracted so the view model tests against a fake.
interface LinkClaiming {
    suspend fun linkClaim(code: String, deviceName: String): LinkClaim
    suspend fun linkPoll(claimToken: String): LinkPollResult
}

/// Production adapter over [JournalApi].
class JournalLinkClaimService(private val api: JournalApi) : LinkClaiming {
    override suspend fun linkClaim(code: String, deviceName: String) = api.linkClaim(code, deviceName)
    override suspend fun linkPoll(claimToken: String) = api.linkPoll(claimToken)
}

/// Signs a NEW device in from a link code — the claimant half of QR
/// device-link login. Kotlin port of matron-apple's `LinkSignInViewModel`.
/// Two entry points: [handleScanned] (scanner, full `matron://link` URI) and
/// [submitManual] (typed server URL + code). Both converge on claim → poll →
/// build the same [UserSession] shape password login builds (`userID` = the
/// server-returned username) → `auth.persist` → `SignedIn`, which the host
/// screen forwards to the normal `onSignedIn` path.
class LinkSignInViewModel(
    private val auth: AuthService,
    private val deviceDisplayName: String,
    private val scope: CoroutineScope,
    private val apiFactory: (String) -> LinkClaiming = { JournalLinkClaimService(JournalApi(it)) },
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface State {
        data object Idle : State
        data object Claiming : State
        data object WaitingForApproval : State
        data class Error(val message: String) : State
        data class SignedIn(val session: UserSession) : State
    }

    /// Manual path: the sign-in form's server field seeds this at submit.
    var serverURL: String = ""

    private var _codeInput: String = ""
    /// Auto-formatted as `XXXX-XXXX` while typing, like PairingViewModel.
    var codeInput: String
        get() = _codeInput
        set(value) { _codeInput = PairingCode.display(value) }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollTask: Job? = null

    // Monotonic generation guard against an orphaned claim resuming after
    // cancel(). Mirrors DeviceLinkViewModel's fix (matron-apple's
    // LinkSignInViewModel just gained the equivalent).
    //
    // `claim()` (called from `handleScanned`/`submitManual`) is a *suspend*
    // function driven by whatever coroutine the caller runs it on — it is NOT
    // tracked by `pollTask`. `cancel()` cancelling `pollTask` therefore
    // cannot reach a `claim()` call that's still suspended on `linkClaim`'s
    // network hop when `cancel()` lands. Without this guard, that stale call
    // resumes after `cancel()`, mutates `state` back to `WaitingForApproval`,
    // and spawns a fresh poll loop — in the VM's injected (activity-level)
    // scope — that can later persist a cancelled link session over the
    // active one.
    //
    // `claim()` captures the live generation right after entering `Claiming`;
    // `cancel()` bumps it. Every path after the one suspension point
    // (`api.linkClaim`) re-checks the captured value against the live
    // counter and abandons silently — no `_state` write, no `startPolling` —
    // on a mismatch. The `CancellationException` rethrow stays first and
    // unguarded.
    private var generation = 0L

    suspend fun handleScanned(payload: String) {
        val parsed = try {
            LinkURI.parse(payload)
        } catch (e: LinkURI.ParseError.UnsupportedVersion) {
            _state.value = State.Error("This QR code needs a newer version of Matron.")
            return
        } catch (e: LinkURI.ParseError) {
            _state.value = State.Error("Not a Matron sign-in code.")
            return
        }
        claim(server = parsed.serverURL, code = parsed.code)
    }

    suspend fun submitManual() {
        val raw = serverURL.trim()
        if (raw.isEmpty() || !PairingCode.isPlausible(codeInput)) return
        val url = try {
            ServerURLValidator.normalize(raw)
        } catch (e: ServerURLValidator.ValidationError) {
            _state.value = State.Error("That doesn't look like a valid server URL.")
            return
        }
        claim(server = url, code = PairingCode.display(codeInput))
    }

    /// Back out: stop polling and return to the sign-in form. The show side
    /// still sees `claimed` and can deny or let the code expire.
    fun cancel() {
        generation++
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Idle
    }

    private suspend fun claim(server: String, code: String) {
        if (_state.value is State.Claiming || _state.value is State.WaitingForApproval) return
        _state.value = State.Claiming
        val gen = generation
        val api = apiFactory(server)
        val claim = try {
            api.linkClaim(code, deviceDisplayName)
        } catch (e: JournalApiError.Conflict) {
            if (gen != generation) return // cancel() landed while this call was in flight
            _state.value = State.Error("This code was already used. Generate a new one on your signed-in device.")
            return
        } catch (e: JournalApiError.NotFound) {
            if (gen != generation) return
            _state.value = State.Error("Code not recognized or expired. Show a fresh QR code and try again.")
            return
        } catch (e: JournalApiError.RateLimited) {
            if (gen != generation) return
            _state.value = State.Error("Too many attempts — try again in a minute.")
            return
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            if (gen != generation) return
            _state.value = State.Error("Couldn't reach the server — try again.")
            return
        }
        if (gen != generation) return // cancel() landed while this call was in flight
        _state.value = State.WaitingForApproval
        startPolling(api, server, claim.claimToken, gen)
    }

    // `gen` is the claim's generation snapshot. `pollTask?.cancel()` kills the
    // loop at its next *cancellable* suspension, but a `linkPoll` response the
    // transport has already delivered resumes the loop body on the cancelled
    // job and runs straight through to `persist`/state writes with no further
    // suspension point — so, mirroring the claim path (and matron-apple's
    // poll loops), every branch re-checks `gen`/`isActive` after the await
    // before touching `_state` or persisting.
    private fun startPolling(api: LinkClaiming, server: String, claimToken: String, gen: Long) {
        pollTask?.cancel()
        pollTask = scope.launch {
            var interval = pollInterval
            while (isActive) {
                delay(interval)
                if (!isActive) return@launch
                try {
                    val result = api.linkPoll(claimToken)
                    if (gen != generation || !isActive) return@launch // stale: cancel() landed mid-poll
                    when (result) {
                        is LinkPollResult.Pending -> interval = pollInterval
                        is LinkPollResult.Denied -> {
                            _state.value = State.Error("Sign-in was denied on the other device.")
                            return@launch
                        }
                        is LinkPollResult.Approved -> {
                            val a = result.approval
                            val session = UserSession(
                                userID = a.username,
                                deviceID = a.deviceID.toString(),
                                homeserverURL = server,
                                accessToken = a.token,
                            )
                            try {
                                auth.persist(session)
                            } catch (e: Throwable) {
                                _state.value = State.Error("Signed in, but couldn't save the session — try again.")
                                return@launch
                            }
                            _state.value = State.SignedIn(session)
                            return@launch
                        }
                    }
                } catch (e: JournalApiError.NotFound) {
                    if (gen != generation) return@launch
                    _state.value = State.Error("Sign-in expired. Scan again.")
                    return@launch
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: Throwable) {
                    interval = errorPollInterval // network loss: back off, keep trying
                }
            }
        }
    }
}
