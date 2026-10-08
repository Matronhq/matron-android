package chat.matron.android.viewmodels

import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentChatPendingDTO
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// The one call that resolves a consent card. Split out from
/// [AgentChatProviding] because [ChatViewModel] answers cards inline in the
/// timeline and has no business listing anything.
interface AgentChatAnswering {
    suspend fun answerAgentChat(
        roomID: String,
        targetDeviceID: Long,
        decision: AgentChatDecision,
    ): Boolean
}

/// The agent-chat slice of the journal API, extracted so the settings screen
/// tests against a fake. Ported from matron-apple's `AgentChatProviding`.
interface AgentChatProviding : AgentChatAnswering {
    suspend fun agentChatPending(): List<AgentChatPendingDTO>
}

/// Production adapter over [JournalApi] (which already exposes these calls).
class JournalAgentChatService(private val api: JournalApi) : AgentChatProviding {
    override suspend fun agentChatPending(): List<AgentChatPendingDTO> = api.agentChatPending()

    override suspend fun answerAgentChat(
        roomID: String,
        targetDeviceID: Long,
        decision: AgentChatDecision,
    ): Boolean = api.answerAgentChat(roomID, targetDeviceID, decision)
}

/// Drives the Agent chats settings screen: the requests still waiting on the
/// user. Every ask waits for an answer — there is no standing consent to list
/// beside them.
///
/// Pull-based like [DevicesViewModel] — the list is not a journal event, so
/// callers [refresh] on screen enter and the model re-fetches after every
/// mutation.
class AgentChatViewModel(private val api: AgentChatProviding) {
    private val _pending = MutableStateFlow<List<AgentChatPendingDTO>>(emptyList())
    val pending: StateFlow<List<AgentChatPendingDTO>> = _pending.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /// `false` on a server that predates the agent-chat endpoints, so the
    /// screen can say so instead of showing a permanently empty list.
    private val _isSupported = MutableStateFlow(true)
    val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    /// `false` until the first successful load. The screens gate their
    /// "nothing here" copy on it: [refresh] runs from a LaunchedEffect, i.e.
    /// after the first frame, so an unguarded empty list tells the user they
    /// have no pending requests a beat before their pending requests arrive.
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    /// Rows with a call in flight, so one row's spinner doesn't disable the
    /// whole screen.
    private val _busyIDs = MutableStateFlow<Set<String>>(emptySet())
    val busyIDs: StateFlow<Set<String>> = _busyIDs.asStateFlow()

    suspend fun refresh() {
        _isLoading.value = true
        try {
            _pending.value = api.agentChatPending().sortedByDescending { it.createdAt }
            _hasLoaded.value = true
            _isSupported.value = true
            _errorMessage.value = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            // No such route: this journal predates agent chat. Say so once
            // rather than showing a permanently empty list as if the user
            // simply had nothing pending.
            _isSupported.value = false
            _hasLoaded.value = true
            _pending.value = emptyList()
            _errorMessage.value = null
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't load agent chats — ${describe(error)}"
        } finally {
            _isLoading.value = false
        }
    }

    /// Answers a parked request. `Conflict` means the row stopped awaiting an
    /// answer between the list load and the tap (answered on another device, or
    /// expired) — the decision the user wanted is either already made or moot,
    /// so drop the row rather than showing an error for something they cannot
    /// act on.
    suspend fun answer(row: AgentChatPendingDTO, decision: AgentChatDecision) {
        if (row.id in _busyIDs.value) return
        _busyIDs.value = _busyIDs.value + row.id
        try {
            try {
                api.answerAgentChat(row.roomID, row.targetDeviceID, decision)
            } catch (conflict: JournalApiError.Conflict) {
                // Already resolved elsewhere — fall through to the refresh.
            }
            _errorMessage.value = null
            refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't answer that request — ${describe(error)}"
        } finally {
            _busyIDs.value = _busyIDs.value - row.id
        }
    }

    companion object {
        fun describe(error: Throwable): String =
            if (error is JournalApiError.Transport) {
                "check your connection and try again."
            } else {
                "the server said no (${error.message ?: error})."
            }

        /// Short relative age of a parked ask, e.g. "3h ago". Rounded down and
        /// capped at days — the exact minute never matters here, only whether
        /// this has been waiting a while.
        fun ageText(createdAt: Long, now: Instant = Instant.now()): String {
            val elapsed = Duration.between(Instant.ofEpochMilli(createdAt), now)
            val seconds = elapsed.seconds
            return when {
                seconds < 60 -> "just now"
                seconds < 3_600 -> "${elapsed.toMinutes()}m ago"
                seconds < 86_400 -> "${elapsed.toHours()}h ago"
                else -> "${elapsed.toDays()}d ago"
            }
        }
    }
}
