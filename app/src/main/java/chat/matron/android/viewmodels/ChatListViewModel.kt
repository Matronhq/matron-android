package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatRecencyGroup
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Drives the chat list: recency-grouped summaries, total unread badge, and an
/// error field. Ported from matron-apple's `ChatListViewModel`. [scope] replaces
/// the Swift original's implicit `@MainActor Task`.
class ChatListViewModel(
    private val chat: ChatService,
    private val scope: CoroutineScope,
) {
    data class GroupedSummaries(
        val group: ChatRecencyGroup,
        val summaries: List<ChatSummary>,
    ) {
        val id: String get() = group.name
    }

    private val _groups = MutableStateFlow<List<GroupedSummaries>>(emptyList())
    val groups: StateFlow<List<GroupedSummaries>> = _groups.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /// Sum of `unreadCount` across every chat in [groups]. Drives the app-icon /
    /// launcher badge; updated in lockstep with [groups] so a host `.onChange`
    /// listener fires exactly once per snapshot.
    private val _totalUnread = MutableStateFlow(0)
    val totalUnread: StateFlow<Int> = _totalUnread.asStateFlow()

    /// Last error raised by the upstream `chatSummaries()` stream, so a readiness
    /// timeout doesn't manifest as an "infinite spinner then silent empty".
    /// Cleared on the next successful snapshot.
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var observationTask: Job? = null

    /// Subscribes to the long-lived `chatSummaries()` stream. An empty first
    /// yield (sync still warming up) just means the next yield arrives when rooms
    /// land — the VM iterates the stream without re-subscribing.
    fun start() {
        observationTask?.cancel()
        observationTask = scope.launch {
            try {
                chat.chatSummaries().collect { snapshot ->
                    _groups.value = group(snapshot)
                    _totalUnread.value = snapshot.sumOf { it.unreadCount }
                    _isLoading.value = false
                    _error.value = null
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = error.message ?: error.toString()
                _isLoading.value = false
            }
        }
    }

    /// Pull-to-refresh: drives a one-shot snapshot through the live pipe; the
    /// active [start] stream receives the extra yield.
    suspend fun refresh() {
        try {
            chat.forceSnapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        }
    }

    /// Cancels the in-flight observation. Call from the host's teardown.
    fun cancel() {
        observationTask?.cancel()
        observationTask = null
    }

    companion object {
        fun group(
            summaries: List<ChatSummary>,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<GroupedSummaries> {
            val buckets = summaries.groupBy { ChatRecencyGroup.bucket(it.lastActivity, now, zone) }
            return ChatRecencyGroup.entries.mapNotNull { bucket ->
                val grouped = buckets[bucket]?.sortedWith(byRecencyDescending)
                if (grouped.isNullOrEmpty()) null else GroupedSummaries(bucket, grouped)
            }
        }

        /// Rooms with a known lastActivity come first, newest first; rooms with
        /// null lastActivity sort by title for a stable order.
        private val byRecencyDescending = Comparator<ChatSummary> { a, b ->
            val la = a.lastActivity
            val lb = b.lastActivity
            when {
                la != null && lb != null -> lb.compareTo(la)
                la == null && lb != null -> 1
                la != null && lb == null -> -1
                else -> a.title.compareTo(b.title)
            }
        }
    }
}
