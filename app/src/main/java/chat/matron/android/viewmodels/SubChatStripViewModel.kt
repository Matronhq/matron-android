package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatService
import chat.matron.android.chat.SubChatSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Drives the parent chat's running-subagent strip and the sub-chat switcher.
/// Subscribes to `ChatService.children(parentConvoID)` and republishes the
/// latest snapshot split into "all children" (switcher) and "running children"
/// (the sticky strip). Ported from matron-apple's `SubChatStripViewModel`.
///
/// [scope] replaces the Swift original's implicit `@MainActor Task`; the UI
/// stage supplies a lifecycle-scoped one.
class SubChatStripViewModel(
    private val chat: ChatService,
    val parentConvoID: String,
    private val scope: CoroutineScope,
) {
    private val _children = MutableStateFlow<List<SubChatSummary>>(emptyList())
    /// Every child of the parent (running and finished), creation order.
    val children: StateFlow<List<SubChatSummary>> = _children.asStateFlow()

    private val _runningChildren = MutableStateFlow<List<SubChatSummary>>(emptyList())
    /// The subset still running — empty ⇒ the strip hides entirely.
    val runningChildren: StateFlow<List<SubChatSummary>> = _runningChildren.asStateFlow()

    private var observationTask: Job? = null

    /// Monotonic token identifying the current observation run; bumped by every
    /// [start]. Hosts pass it to [stop] so a stale surface's teardown (which can
    /// fire AFTER a successor's start on push navigation) can't cancel a
    /// successor's fresh stream.
    var observationGeneration: Int = 0
        private set

    fun start(): Job {
        observationGeneration += 1
        observationTask?.cancel()
        val task = scope.launch {
            chat.children(parentConvoID).collect { snapshot ->
                _children.value = snapshot
                _runningChildren.value = snapshot.filter { it.isRunning }
            }
        }
        observationTask = task
        return task
    }

    /// Stops the observation only if [generation] still identifies the current
    /// run — a stale view instance's teardown becomes a no-op.
    fun stop(generation: Int) {
        if (generation == observationGeneration) stop()
    }

    fun stop() {
        observationTask?.cancel()
        observationTask = null
    }

    /// The running child to preselect when the strip has exactly one. `null`
    /// when there are none or several.
    val soleRunningChild: SubChatSummary?
        get() = _runningChildren.value.singleOrNull()

    companion object {
        private const val SUBTASK_INDICATOR_PREFIX = "🔀 Subtask: "

        /// The description carried by a bridge subtask-indicator message, or
        /// `null` when [body] isn't one. The indicator is always the whole
        /// message (modulo surrounding whitespace), never an infix.
        fun subtaskDescription(fromMessageBody: String): String? {
            // Runs for EVERY message row on every list-body evaluation, so it
            // must bail without copying the body: skip leading whitespace by
            // index, prefix-check in place, and only then touch the (short)
            // remainder. The old full `trim()` copied every message body on
            // every evaluation (apple #167).
            val start = fromMessageBody.indexOfFirst { !it.isWhitespace() }
            if (start < 0 || !fromMessageBody.startsWith(SUBTASK_INDICATOR_PREFIX, startIndex = start)) return null
            val description = fromMessageBody.substring(start + SUBTASK_INDICATOR_PREFIX.length).trim()
            return description.ifEmpty { null }
        }

        /// The child conversation a subtask indicator most plausibly refers to.
        /// Exact title matches win first; only with none does prefix matching
        /// apply (the bridge truncates the indicator's description to 80 chars).
        /// Duplicate titles tie-break by preferring a still-running child, then
        /// the newest (`children` arrives in creation order, so `last` is the
        /// most recent spawn).
        fun resolveSubtaskTarget(
            description: String,
            among: List<SubChatSummary>,
        ): SubChatSummary? {
            val exact = among.filter { it.title == description }
            val matches = if (exact.isEmpty()) among.filter { it.title.startsWith(description) } else exact
            return matches.lastOrNull { it.isRunning } ?: matches.lastOrNull()
        }

        /// The navigation path after switching the open sub-chat viewer from
        /// [current] to [sibling]: replace the stack tail (pop-then-push) so
        /// hopping between siblings doesn't grow the back stack. `null` means no
        /// navigation is needed. Falls back to a plain push when the tail isn't
        /// [current] (defensive).
        fun pathReplacingCurrentChild(
            path: List<String>,
            current: String,
            with: String,
        ): List<String>? {
            if (with == current) return null
            val newPath = path.toMutableList()
            if (newPath.lastOrNull() == current) newPath.removeAt(newPath.size - 1)
            newPath.add(with)
            return newPath
        }
    }
}
