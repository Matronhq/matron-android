package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairURI
import chat.matron.android.journal.PairingCode
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// Drives the "Add agent" pairing modal: code entry → mandatory requester-IP
/// preview → name + approve → wait-for-claim. Ported from matron-apple's
/// `PairingViewModel`. [scope] replaces the Swift original's `@MainActor Task`s;
/// [now] and the intervals are injected for deterministic tests.
///
/// The code arrives typed, or as a [PairURI] payload (`matron://pair?…`) —
/// scanned via [handleScanned] or pasted into [codeInput]. A payload naming a
/// different journal than [accountServerURL] is refused before any preview: the
/// code would be looked up on the wrong server.
class PairingViewModel(
    private val api: DevicesProviding,
    private val existingNames: List<String>,
    /// The signed-in account's journal base URL, compared (origin + base path) against
    /// the server a scanned pairing payload names.
    private val accountServerURL: String,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Instant.now() },
    private val pollInterval: Duration = 2500.milliseconds,
    private val previewDebounce: Duration = 300.milliseconds,
    /// Tag characters already in use across the roster, for the duplicate
    /// warning (apple #158).
    private val existingTags: List<String> = emptyList(),
) {
    sealed interface Phase {
        data object EnterCode : Phase
        data class Preview(val requesterIP: String) : Phase
        data object WaitingForClaim : Phase
        data class Success(val agentName: String) : Phase
    }

    private val _codeInput = MutableStateFlow("")

    /// Auto-formatted as `XXXX-XXXX` while typing; sloppy input is accepted and
    /// normalized on use. A pasted `matron:` payload is handled exactly like a
    /// scan ([handleScanned]).
    var codeInput: String
        get() = _codeInput.value
        set(value) {
            if (value.trim().startsWith("matron:", ignoreCase = true)) {
                handleScanned(value)
                return
            }
            val formatted = PairingCode.display(value)
            val old = _codeInput.value
            _codeInput.value = formatted
            if (formatted != old) codeChanged()
        }

    private val _duplicateNameWarning = MutableStateFlow<String?>(null)
    /// Duplicate names are legal server-side — warn, don't block.
    val duplicateNameWarning: StateFlow<String?> = _duplicateNameWarning.asStateFlow()

    private var _agentName: String = ""
    /// The box's short hostname. Not renameable after approval.
    var agentName: String
        get() = _agentName
        set(value) {
            _agentName = value
            _duplicateNameWarning.value =
                if (existingNames.contains(value)) "You already have an agent called $value" else null
        }

    /// Optional roster tag character to enrol the box with (apple #158): a
    /// colleague's dev-a/dev-b become a/b from day one. Sieved on use.
    var tagChar: String = ""
        set(value) {
            field = value
            val tag = DevicesViewModel.tagCharFromDraft(value)
            _duplicateTagWarning.value =
                if (tag != null && existingTags.any { it.equals(tag, ignoreCase = true) }) "Another box already uses “$tag”" else null
        }

    private val _duplicateTagWarning = MutableStateFlow<String?>(null)
    /// Duplicate tags are legal — warn, don't block.
    val duplicateTagWarning: StateFlow<String?> = _duplicateTagWarning.asStateFlow()

    private val _phase = MutableStateFlow<Phase>(Phase.EnterCode)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isApproving = MutableStateFlow(false)
    val isApproving: StateFlow<Boolean> = _isApproving.asStateFlow()

    private val _expiresAt = MutableStateFlow<Instant?>(null)
    /// Pair-code TTL deadline (from the preview).
    val expiresAt: StateFlow<Instant?> = _expiresAt.asStateFlow()

    private var previewTask: Job? = null
    private var claimTask: Job? = null

    /// A scanned (or pasted) QR payload. A valid [PairURI] for the signed-in
    /// journal fills the code and previews at once (no typing debounce);
    /// anything else clears the code and explains why.
    fun handleScanned(payload: String) {
        if (!inCodeEntryStage) return
        val raw = payload.trim()
        val parsed = try {
            PairURI.parse(raw)
        } catch (e: PairURI.ParseError.UnsupportedVersion) {
            rejectScan("This pairing code needs a newer version of Matron.")
            return
        } catch (e: PairURI.ParseError.Malformed) {
            rejectScan("That pairing code is incomplete. Get a fresh code from the box and try again.")
            return
        } catch (e: PairURI.ParseError.NotAPairURI) {
            rejectScan(
                if (raw.startsWith("matron://link?", ignoreCase = true)) {
                    "That's a sign-in code, not an agent pairing code. Scan the QR the box shows when pairing."
                } else {
                    "Not a Matron agent pairing code. Scan the QR the box shows when pairing."
                },
            )
            return
        }
        if (PairURI.sameJournal(parsed.serverURL, accountServerURL) != true) {
            val codeHost = PairURI.displayJournal(parsed.serverURL)
            val accountHost = PairURI.displayJournal(accountServerURL)
            rejectScan(
                "This code is for $codeHost, but you're signed in to $accountHost. " +
                    "Pair the box from an account on $codeHost, or point the box at $accountHost.",
            )
            return
        }
        _codeInput.value = parsed.code
        codeChanged(debounce = Duration.ZERO)
    }

    /// A refused scan never previews: drop any pending preview and the code
    /// it would have checked, then show [message].
    private fun rejectScan(message: String) {
        previewTask?.cancel()
        previewTask = null
        _codeInput.value = ""
        _phase.value = Phase.EnterCode
        _expiresAt.value = null
        _errorMessage.value = message
    }

    private fun codeChanged(debounce: Duration = previewDebounce) {
        previewTask?.cancel()
        _errorMessage.value = null
        if (_phase.value is Phase.Success) return // done — edits are a fresh modal's job
        _phase.value = Phase.EnterCode
        _expiresAt.value = null
        val code = PairingCode.normalize(codeInput)
        if (code.length != PairingCode.LENGTH) return
        previewTask = scope.launch {
            delay(debounce)
            if (!isActive) return@launch
            preview(code)
        }
    }

    /// Preview responses belong to the code-entry stage; once approval has gone
    /// through, a late response must not pull the flow back out of
    /// waiting/success.
    private val inCodeEntryStage: Boolean
        get() = when (_phase.value) {
            is Phase.EnterCode, is Phase.Preview -> true
            is Phase.WaitingForClaim, is Phase.Success -> false
        }

    private suspend fun preview(code: String) {
        try {
            val preview = api.pairPreview(code)
            if (!inCodeEntryStage) return
            _phase.value = Phase.Preview(preview.requesterIP)
            _expiresAt.value = now().plusSeconds(preview.expiresIn.toLong())
        } catch (notFound: JournalApiError.NotFound) {
            if (!inCodeEntryStage) return
            _errorMessage.value =
                "Code not recognized or expired. Get a fresh code from the box and try again."
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (!inCodeEntryStage) return
            _errorMessage.value = "Couldn't check that code — try again."
        }
    }

    /// Snapshot the roster, approve the code under [agentName], then poll for the
    /// box's claim. Returns once the claim loop is RUNNING (it finishes in the
    /// background so the sheet stays dismissible).
    suspend fun approve() {
        if (_phase.value !is Phase.Preview || _isApproving.value) return
        _isApproving.value = true
        try {
            _errorMessage.value = null
            val code = PairingCode.normalize(codeInput)
            val name = agentName.trim()
            if (name.isEmpty()) {
                _errorMessage.value = "Name the agent first — the name can't be changed later."
                return
            }
            // device id snapshot BEFORE approving: the claim is detected by a new
            // agent id, never by name (names aren't unique).
            val snapshot: Set<Long> = try {
                api.devices().map { it.id }.toSet()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _errorMessage.value = "Couldn't reach the server — try again."
                return
            }
            try {
                api.pairApprove(code, name, DevicesViewModel.tagCharFromDraft(tagChar))
            } catch (conflict: JournalApiError.Conflict) {
                _errorMessage.value = "This code was already approved."
                return
            } catch (notFound: JournalApiError.NotFound) {
                _errorMessage.value =
                    "Code not recognized or expired. Get a fresh code from the box and try again."
                return
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _errorMessage.value = "Couldn't approve — try again."
                return
            }
            // A code edit made while approve was in flight queues a fresh
            // debounced preview; kill it before entering the wait state.
            previewTask?.cancel()
            previewTask = null
            _phase.value = Phase.WaitingForClaim
            val deadline = _expiresAt.value ?: now().plusSeconds(600)
            claimTask = scope.launch { pollForClaim(snapshot, deadline) }
        } finally {
            _isApproving.value = false
        }
    }

    private suspend fun pollForClaim(snapshot: Set<Long>, deadline: Instant) {
        while (currentScopeActive() && !now().isAfter(deadline)) {
            val claimed = runCatching { api.devices() }.getOrNull()
                ?.firstOrNull { it.kind == "agent" && !snapshot.contains(it.id) }
            if (claimed != null) {
                _phase.value = Phase.Success(claimed.name)
                return
            }
            delay(pollInterval)
        }
        if (!currentScopeActive()) return
        _errorMessage.value = "The box never collected its token. Start again with a fresh code."
        _phase.value = Phase.EnterCode
    }

    private suspend fun currentScopeActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    /// Stops the claim poll (the wait is dismissible).
    fun cancelWaiting() {
        claimTask?.cancel()
        claimTask = null
    }
}
