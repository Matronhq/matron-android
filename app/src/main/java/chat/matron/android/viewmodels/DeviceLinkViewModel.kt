package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkStart
import chat.matron.android.journal.LinkStatus
import chat.matron.android.journal.LinkURI
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

/// The show-QR slice of [JournalApi], extracted so the view model tests
/// against a fake (same pattern as [DevicesProviding]).
interface DeviceLinking {
    suspend fun linkStart(): LinkStart
    suspend fun linkStatus(): LinkStatus
    suspend fun linkApprove(code: String)
    suspend fun linkDeny(code: String)
}

/// Production adapter over [JournalApi].
class JournalDeviceLinkService(private val api: JournalApi) : DeviceLinking {
    override suspend fun linkStart() = api.linkStart()
    override suspend fun linkStatus() = api.linkStatus()
    override suspend fun linkApprove(code: String) = api.linkApprove(code)
    override suspend fun linkDeny(code: String) = api.linkDeny(code)
}

/// Drives Settings → "Link a Device": start a session, render the QR, poll
/// status, and on a claim show the approve card (claimant name + IP — the
/// mandatory confirm-tap of the design; scanning alone never signs anything
/// in). Kotlin port of matron-apple's `DeviceLinkViewModel`.
///
/// Lifecycle: `start()` on screen enter, `stop()` on leave. Status 404 while
/// on screen means the session expired — routine, so the QR silently
/// regenerates. Approve/deny are terminal; the show side does not wait for
/// the claimant's final poll.
class DeviceLinkViewModel(
    private val api: DeviceLinking,
    private val serverURL: String,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface Phase {
        data object Loading : Phase
        data class Showing(val code: String) : Phase
        data class Claimed(val deviceName: String, val requesterIP: String) : Phase
        data object Approved : Phase
        data object Denied : Phase
        /// 404 on start: the server predates /link/*.
        data object Unsupported : Phase
        data class Error(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _noticeMessage = MutableStateFlow<String?>(null)
    /// One-line banner above a regenerated QR ("Code expired — showing a
    /// fresh one") or under a failed tap ("Couldn't approve — try again.").
    val noticeMessage: StateFlow<String?> = _noticeMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    /// True while an approve/deny round-trip is in flight; reentrant taps are
    /// ignored and the poll loop skips regeneration to avoid racing it.
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    /// The full QR payload for the current code (null unless Showing).
    val qrPayload: String?
        get() = (_phase.value as? Phase.Showing)?.let { LinkURI.format(serverURL, it.code) }

    private var pollTask: Job? = null
    /// The active session's display code — what approve/deny send back as the
    /// belt-and-braces intent check.
    private var currentCode: String? = null

    // Plan-owner amendment: monotonic generation guard against an orphaned
    // poll loop. Mirrors matron-apple's `DeviceLinkViewModel` fix.
    //
    // `start()` (and the regenerate path it shares with the internal 404
    // auto-regenerate and the approve/deny expiry-regenerate) is a *suspend*
    // function driven by whatever coroutine the caller runs it on — it is
    // NOT tracked by `pollTask`. `stop()` cancelling `pollTask` therefore
    // cannot reach a `start()`/regenerate call that's still suspended on the
    // network hop when `stop()` lands. Without this guard, that stale call
    // resumes after `stop()`, mutates `phase` back to `Showing`, and spawns a
    // brand new poll loop that nothing can ever stop again.
    //
    // `start()` bumps and captures a fresh generation before calling
    // `startSession`; `stop()` alone also bumps it. `startSession` re-checks
    // the captured value against the live counter immediately after its one
    // suspension point (the `linkStart()` call) and abandons silently — no
    // phase mutation, no `startPolling` — on a mismatch.
    private var generation = 0L

    suspend fun start() {
        stop()
        val gen = ++generation
        _noticeMessage.value = null
        _phase.value = Phase.Loading
        startSession(gen)
    }

    fun stop() {
        generation++
        pollTask?.cancel()
        pollTask = null
    }

    suspend fun approve() {
        val code = currentCode ?: return
        if (_phase.value !is Phase.Claimed || _isSubmitting.value) return
        _isSubmitting.value = true
        try {
            api.linkApprove(code)
            stop()
            _phase.value = Phase.Approved
        } catch (e: JournalApiError.NotFound) {
            _noticeMessage.value = "Code expired — showing a fresh one"
            stop()
            startSession(generation)
        } catch (e: JournalApiError.Conflict) {
            // Nothing left to approve (raced expiry/replacement) — same
            // recovery as expiry: fresh code.
            _noticeMessage.value = "Code expired — showing a fresh one"
            stop()
            startSession(generation)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _noticeMessage.value = "Couldn't approve — try again."
        } finally {
            _isSubmitting.value = false
        }
    }

    suspend fun deny() {
        val code = currentCode ?: return
        if (_phase.value !is Phase.Claimed || _isSubmitting.value) return
        _isSubmitting.value = true
        try {
            api.linkDeny(code)
            stop()
            _phase.value = Phase.Denied
        } catch (e: JournalApiError.NotFound) {
            stop()
            startSession(generation)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _noticeMessage.value = "Couldn't deny — try again."
        } finally {
            _isSubmitting.value = false
        }
    }

    /// [gen] is the generation captured by the caller before the one
    /// suspension point below (`api.linkStart()`). If `stop()` bumped
    /// [generation] while this call was in flight, [gen] no longer matches
    /// and this method abandons without touching `phase` or `currentCode` or
    /// spawning a poll loop.
    private suspend fun startSession(gen: Long) {
        try {
            val started = api.linkStart()
            if (gen != generation) return // stop() landed while this call was in flight
            currentCode = started.code
            _phase.value = Phase.Showing(started.code)
            startPolling(gen)
        } catch (e: JournalApiError.NotFound) {
            if (gen != generation) return
            _phase.value = Phase.Unsupported
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            if (gen != generation) return
            _phase.value = Phase.Error("Couldn't reach the server — try again.")
        }
    }

    private fun startPolling(gen: Long) {
        pollTask?.cancel()
        pollTask = scope.launch {
            var interval = pollInterval
            while (isActive) {
                delay(interval)
                if (!isActive) return@launch
                if (_isSubmitting.value) continue // don't race an in-flight tap
                try {
                    val status = api.linkStatus()
                    // Stale-response guard, same as the claimant poll loop: a
                    // response already delivered when approve()/deny()/stop()
                    // landed resumes here on the cancelled job with no further
                    // suspension point — it must not overwrite the terminal
                    // Approved/Denied phase.
                    if (gen != generation || !isActive) return@launch
                    when (status) {
                        is LinkStatus.Waiting -> Unit // phase already Showing
                        is LinkStatus.Claimed -> {
                            if (_phase.value !is Phase.Claimed) {
                                _phase.value = Phase.Claimed(status.deviceName, status.requesterIP)
                            }
                        }
                    }
                    interval = pollInterval
                } catch (e: JournalApiError.NotFound) {
                    // Expired (routine): regenerate silently. startSession
                    // spawns a fresh poll task; this one must end.
                    if (gen != generation || !isActive || _isSubmitting.value) return@launch
                    startSession(gen)
                    return@launch
                } catch (e: JournalApiError.Unauthenticated) {
                    // Starter signed out / revoked mid-flow: the host screen
                    // closes on its own sign-out path; stop quietly.
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
