package chat.matron.android.viewmodels

import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.TimelineService
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.models.MatronDebug
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.SessionState
import chat.matron.android.models.SessionStatus
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.platform.Haptics
import chat.matron.android.storage.LRUCache
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Rendering unit for the chat timeline: `items` interleaved with `.separator`
/// rows at calendar-day boundaries. Ported from matron-apple's `TimelineRow`.
sealed interface TimelineRow {
    data class Message(val item: TimelineItem) : TimelineRow
    data class Separator(val date: Instant) : TimelineRow

    val id: String
        get() = when (this) {
            is Message -> "msg:${item.id}"
            // Bucket by calendar day (system zone, matching the Swift original's
            // `Calendar.current`) so all rows on one day share a stable identity.
            is Separator -> "sep:${date.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()}"
        }
}

/// Payload identifying a pending ask-user prompt. Ported from matron-apple's
/// `AskUserPromptContext`.
data class AskUserPromptContext(val id: String, val event: AskUserEvent)

/// Drives a single chat screen: subscribes to a room's `TimelineService.items()`
/// stream, exposes derived row/anchor state, session status, media resolution,
/// pagination, and ask-user prompt bookkeeping. Ported from matron-apple's
/// `ChatViewModel`.
///
/// Platform adaptations: [scope] replaces the Swift `@MainActor Task`s;
/// answered-prompt persistence uses an injected [KeyValueStore] (Swift's
/// `UserDefaults`); [image] resolves raw bytes (the SwiftUI `Image` decode/cache
/// is UI-stage); date bucketing uses an injectable [zone] (Swift's `Calendar`).
/// [writeTempFile] takes the destination root as a parameter (the UI passes the
/// app cache dir) where Swift reached for `FileManager.temporaryDirectory`, so
/// this layer stays Android-free. Diagnostic logging (os.Logger / MatronFileLog)
/// is dropped — behaviour is unaffected.
class ChatViewModel(
    val roomID: String,
    private val timeline: TimelineService,
    private val media: MediaService,
    private val scope: CoroutineScope,
    private val answeredPromptStore: KeyValueStore,
    private val haptics: Haptics = Haptics.None,
    /// Answers agent-chat consent cards. Nullable so the many call sites that
    /// don't render them (tests, previews) construct unchanged; a card with no
    /// answerer renders read-only rather than offering buttons that would do
    /// nothing — the exact failure this whole path exists to fix.
    private val agentChat: AgentChatAnswering? = null,
) {
    // MARK: - Published state

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /// Set when [writeTempFile] fails (media fetch or disk write) so the file-tap
    /// affordance isn't a silent dead button. Dismissible; not auto-cleared by
    /// timeline activity like [error] is.
    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    fun dismissAttachmentError() {
        _attachmentError.value = null
    }

    private val _sessionStatus = MutableStateFlow<SessionStatus?>(null)
    val sessionStatus: StateFlow<SessionStatus?> = _sessionStatus.asStateFlow()

    private val _rows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val rows: StateFlow<List<TimelineRow>> = _rows.asStateFlow()

    private val _windowedRows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val windowedRows: StateFlow<List<TimelineRow>> = _windowedRows.asStateFlow()

    private val _rowAnchorIDs = MutableStateFlow<Set<String>>(emptySet())
    val rowAnchorIDs: StateFlow<Set<String>> = _rowAnchorIDs.asStateFlow()

    private val _activityLabel = MutableStateFlow<String?>(null)
    val activityLabel: StateFlow<String?> = _activityLabel.asStateFlow()

    /// True while the conversation's durable session_state is "running" — the
    /// bridge flips it at turn start/end. Carries the floating stop button:
    /// [activityLabel] legitimately clears mid-turn (bridge dedups activity
    /// frames; the overlay staleness sweep drops a quiet indicator), so it
    /// can't keep an affordance visible for a whole turn.
    private val _isTurnRunning = MutableStateFlow(false)
    val isTurnRunning: StateFlow<Boolean> = _isTurnRunning.asStateFlow()

    private val _lastRenderableItemID = MutableStateFlow<String?>(null)
    val lastRenderableItemID: StateFlow<String?> = _lastRenderableItemID.asStateFlow()

    private val _isExtendingWindow = MutableStateFlow(false)
    val isExtendingWindow: StateFlow<Boolean> = _isExtendingWindow.asStateFlow()

    private val _hasReceivedFirstSnapshot = MutableStateFlow(false)
    val hasReceivedFirstSnapshot: StateFlow<Boolean> = _hasReceivedFirstSnapshot.asStateFlow()

    private val _settledEmpty = MutableStateFlow(false)
    val settledEmpty: StateFlow<Boolean> = _settledEmpty.asStateFlow()

    // Suppresses the turn-complete tick on the first recompute after each
    // start(): the VM instance is cached and reused across chat re-entries
    // (ChatVMCache), so _activityLabel survives from a prior visit and its
    // stale value must not be read as a "was running" baseline. Only edges
    // observed while the chat is open should tick.
    private var suppressNextTickEdge = true

    // Internal / untested-by-tests derived + control state (UI-thread confined).
    var firstRenderableItemID: String? = null
        private set
    var lastRenderableItemIsOwn: Boolean = false
        private set
    var isPaginatingBackward: Boolean = false
        private set
    var reachedHistoryStart: Boolean = false
        private set
    var observationGeneration: Int = 0
        private set

    private var visibleWindowSize = DEFAULT_WINDOW_SIZE
    private var consecutiveNoGrowthPaginates = 0

    /// Date-separator bucketing zone. Injectable so tests pin a timezone;
    /// re-buckets on change so a late set doesn't desync rows from items.
    var zone: ZoneId = ZoneId.systemDefault()
        set(value) {
            field = value
            applyDerivedRecompute()
        }

    /// Grace before an empty timeline counts as settled-empty (test knob).
    var emptyPlaceholderGraceMs: Long = 400

    /// Ceiling on the foreground-resume suppression window (test knob).
    var resumeGraceMs: Long = 10_000

    /// Max wait for a paginated snapshot to arrive before counting no-growth.
    /// A `var` test knob (the Swift original's constant is 2.5s); behaviour is
    /// identical, only the wait bound changes.
    var snapshotWaitMs: Long = 2_500

    private var isResuming = false
    private var observationTask: Job? = null
    private var statusTask: Job? = null
    private var sessionStateTask: Job? = null
    private var connectionTask: Job? = null
    private var emptyDebounceTask: Job? = null
    private var resumeTask: Job? = null
    private var historyRefillTask: Job? = null

    private val resolvedImages = LRUCache<String, ByteArray>(MEDIA_CACHE_LIMIT)
    private val failedRequests = LRUCache<String, Unit>(MEDIA_CACHE_LIMIT)
    private val inFlightRequests = mutableSetOf<String>()

    /// File-attachment URLs whose blob download is currently in flight. A
    /// StateFlow (the Kotlin analogue of the Swift port's `@Observable` set)
    /// so the timeline's file chip recomposes to draw a spinner — a large PDF
    /// takes double-digit seconds to pull through the journal server and a tap
    /// with no visible reaction reads as a dead tap. Port of apple #138.
    private val _downloadingFiles = MutableStateFlow<Set<String>>(emptySet())
    val downloadingFiles: StateFlow<Set<String>> = _downloadingFiles.asStateFlow()

    /// Attachment URL → temp file already written by [writeTempFile].
    /// Re-opening an attachment must not re-download a multi-MB blob the user
    /// just waited for.
    private val fileTempFiles = mutableMapOf<String, File>()

    /// Media URLs (file OR image attachments) whose fetch returned a
    /// definitive 404 — reaped server-side, permanently gone (apple #139).
    /// Drives the chips' Expired rendering for events that synced BEFORE the
    /// reap and so never carry the payload tombstone. Deliberately NOT cleared
    /// when connectivity returns (unlike [failedRequests]) — blob ids are
    /// immutable, so a 404 never becomes retryable. Unbounded like
    /// [fileTempFiles], and bounded in practice by attachments this session
    /// has actually tried to fetch.
    private val _unavailableMedia = MutableStateFlow<Set<String>>(emptySet())
    val unavailableMedia: StateFlow<Set<String>> = _unavailableMedia.asStateFlow()

    private val answeredPromptsKey = "matron.answeredPrompts.$roomID"
    private val answeredPromptIDs: MutableSet<String> =
        answeredPromptStore.getStringList(answeredPromptsKey)?.toMutableSet() ?: mutableSetOf()

    private val askViewModels = mutableMapOf<String, AskUserSheetViewModel>()

    // MARK: - Agent-chat consent cards

    /// Consent cards answered on THIS device, keyed by journal seq, with the
    /// decision made. Persisted under `matron.agentChatAnswers.<roomID>`.
    ///
    /// Unlike an ask-user reply, answering a consent card is an HTTP call and
    /// produces no journal event — so there is nothing in the timeline to read
    /// the outcome back from, on this device or any other. Local memory is the
    /// only thing standing between the user and a card that looks unanswered
    /// forever.
    ///
    /// Stored as "<seq>:<decision>" strings because [KeyValueStore] carries
    /// ordered string lists, not maps. Journal seqs are digits, so the first
    /// ':' is an unambiguous separator.
    private val agentChatAnswersKey = "matron.agentChatAnswers.$roomID"
    private val agentChatAnswers: MutableMap<String, String> =
        answeredPromptStore.getStringList(agentChatAnswersKey).orEmpty()
            .mapNotNull { entry ->
                val split = entry.indexOf(':')
                if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
            }.toMap().toMutableMap()

    /// Live per-card state while a call is in flight or has failed. Not
    /// persisted: a send that was interrupted should come back answerable.
    private val _agentChatStates = MutableStateFlow<Map<String, AgentChatCardState>>(emptyMap())
    val agentChatStates: StateFlow<Map<String, AgentChatCardState>> = _agentChatStates.asStateFlow()

    /// Render state for one consent card. A remembered decision wins over
    /// everything: once answered, the card is history.
    fun agentChatState(eventID: String): AgentChatCardState {
        agentChatAnswers[eventID]?.let { decision ->
            return if (decision == EXPIRED_ANSWER) {
                AgentChatCardState.Expired
            } else {
                AgentChatCardState.Answered(decision == AgentChatDecision.APPROVE.wire)
            }
        }
        _agentChatStates.value[eventID]?.let { return it }
        // No answerer wired: show the card, but don't offer buttons that cannot
        // resolve it.
        return if (agentChat == null) AgentChatCardState.Expired else AgentChatCardState.Idle
    }

    /// Answers a consent card. The ONLY path that resolves one — a reply into
    /// the room never reaches the parked row.
    ///
    /// Runs on the view model's own [scope] rather than the caller's, and that
    /// is load-bearing: the card is a row in a lazy list, so a row-scoped
    /// coroutine is cancelled the moment the card scrolls out of view or the
    /// user leaves the chat. Cancelled mid-request, the card would keep the
    /// `Sending` marker that blocks retries — permanently unanswerable.
    ///
    /// A `Conflict` means the row stopped awaiting an answer between the card
    /// being drawn and the tap (answered on another device, or 24h expired);
    /// that is not an error the user can act on, so it settles the card as
    /// expired rather than showing a failure they'd only retry.
    fun answerAgentChat(
        eventID: String,
        request: AgentChatRequest,
        decision: AgentChatDecision,
    ) {
        val answerer = agentChat ?: return
        if (agentChatAnswers.containsKey(eventID)) return
        if (_agentChatStates.value[eventID] is AgentChatCardState.Sending) return
        setAgentChatState(eventID, AgentChatCardState.Sending)
        scope.launch {
            try {
                answerer.answerAgentChat(request.roomID, request.targetDeviceID, decision)
                rememberAgentChatAnswer(eventID, decision.wire)
            } catch (cancel: CancellationException) {
                // Whole chat is going away. Drop the in-flight marker so the
                // card comes back answerable rather than stuck mid-send.
                _agentChatStates.value = _agentChatStates.value - eventID
                throw cancel
            } catch (conflict: JournalApiError.Conflict) {
                rememberAgentChatAnswer(eventID, EXPIRED_ANSWER)
            } catch (error: Throwable) {
                setAgentChatState(eventID, AgentChatCardState.Failed(describeAgentChatError(error)))
            }
        }
    }

    private fun setAgentChatState(eventID: String, state: AgentChatCardState) {
        _agentChatStates.value = _agentChatStates.value + (eventID to state)
    }

    private fun rememberAgentChatAnswer(eventID: String, value: String) {
        agentChatAnswers[eventID] = value
        answeredPromptStore.setStringList(
            agentChatAnswersKey,
            agentChatAnswers.map { (id, decision) -> "$id:$decision" },
        )
        // Drop the transient entry AFTER the persisted one lands, so the state
        // read never falls through to Idle in between.
        _agentChatStates.value = _agentChatStates.value - eventID
    }


    // MARK: - Snapshot → derived state

    private fun applySnapshot(snapshot: List<TimelineItem>) {
        _items.value = snapshot
        applyDerivedRecompute()
    }

    /// Single pass: filter hidden items, interleave day separators, capture
    /// first/last renderable ids and the activity-indicator label.
    private fun applyDerivedRecompute() {
        val nextRows = ArrayList<TimelineRow>(_items.value.size + 4)
        var first: String? = null
        var last: String? = null
        var lastIsOwn = false
        var previousDay: LocalDate? = null
        var nextActivityLabel: String? = null
        for (item in _items.value) {
            when (val kind = item.kind) {
                // The trailing activity indicator renders as a fixed footer, NOT a
                // row (as a row it became the scroll anchor during every bot turn
                // and vanished on completion).
                is TimelineItem.Kind.ActivityIndicator -> {
                    nextActivityLabel = kind.label
                    continue
                }
                // Hidden kinds, kept out of rows AND day bucketing.
                is TimelineItem.Kind.StateChange, is TimelineItem.Kind.AskUserAnswer -> continue
                else -> {}
            }
            if (first == null) first = item.id
            last = item.id
            lastIsOwn = item.isOwn
            val day = item.timestamp.atZone(zone).toLocalDate()
            if (previousDay == null || day != previousDay) {
                nextRows.add(TimelineRow.Separator(item.timestamp))
                previousDay = day
            }
            nextRows.add(TimelineRow.Message(item))
        }
        _rows.value = nextRows
        firstRenderableItemID = first
        _lastRenderableItemID.value = last
        lastRenderableItemIsOwn = lastIsOwn
        val previousActivityLabel = _activityLabel.value
        _activityLabel.value = nextActivityLabel
        // Turn complete: the trailing activity indicator went from present
        // (working) to absent (idle). Fires once on that edge — but never on
        // the first recompute after start(), since the VM is cached and reused
        // across re-entries (ChatVMCache) and _activityLabel is never reset by
        // stop()/start(); a stale "thinking…" baseline from a prior visit must
        // not be read as a "was running" edge for a turn the user never
        // watched complete. Only edges observed while the chat is open tick.
        if (suppressNextTickEdge) {
            suppressNextTickEdge = false
        } else if (previousActivityLabel != null && nextActivityLabel == null && _items.value.isNotEmpty()) {
            // A genuine turn-complete leaves the messages and drops only the
            // trailing indicator. An empty snapshot instead is a mirror wipe
            // (resync/reconnect): the indicator vanished with everything else,
            // no turn finished — don't tick.
            haptics.tick()
        }
        _rowAnchorIDs.value = nextRows.map { row ->
            if (row is TimelineRow.Message) row.item.id else row.id
        }.toSet()
        recomputeWindow()
    }

    /// Rebuilds [windowedRows] from [rows] and the window size, re-synthesizing a
    /// leading separator when the cut lands mid-day.
    private fun recomputeWindow() {
        val window = _rows.value.takeLast(visibleWindowSize).toMutableList()
        val head = window.firstOrNull()
        if (head is TimelineRow.Message) {
            window.add(0, TimelineRow.Separator(head.item.timestamp))
        }
        _windowedRows.value = window
    }

    // MARK: - History window

    /// Reveals older content: grows the render window over loaded rows first
    /// (instant), fetching another page only when the window already shows
    /// everything local. Holds [isExtendingWindow] through the layout pass.
    suspend fun extendHistoryWindow() {
        if (_isExtendingWindow.value || isPaginatingBackward) return
        if (visibleWindowSize < _rows.value.size) {
            _isExtendingWindow.value = true
            visibleWindowSize = minOf(_rows.value.size, visibleWindowSize + WINDOW_GROWTH_STEP)
            recomputeWindow()
            delay(150)
            _isExtendingWindow.value = false
            return
        }
        paginateBackward()
        if (visibleWindowSize < _rows.value.size) {
            _isExtendingWindow.value = true
            visibleWindowSize = minOf(_rows.value.size, visibleWindowSize + WINDOW_GROWTH_STEP)
            recomputeWindow()
            delay(150)
            _isExtendingWindow.value = false
        }
    }

    /// Snaps the window back to steady-state. Called only when no reader is up
    /// in history.
    fun resetHistoryWindow() {
        if (visibleWindowSize == DEFAULT_WINDOW_SIZE) return
        visibleWindowSize = DEFAULT_WINDOW_SIZE
        recomputeWindow()
    }

    /// Widens the window so a remembered scroll position outside the tail window
    /// can be scrolled to on restore. Holds [isExtendingWindow] through the pass.
    fun ensureWindowContains(id: String) {
        val index = _rows.value.indexOfLast { row ->
            if (row is TimelineRow.Message) row.item.id == id else row.id == id
        }
        if (index < 0) return
        val needed = _rows.value.size - index + 20
        if (needed > visibleWindowSize) {
            _isExtendingWindow.value = true
            visibleWindowSize = needed
            recomputeWindow()
            scope.launch {
                delay(150)
                _isExtendingWindow.value = false
            }
        }
    }

    // MARK: - Empty-state debounce + foreground resume

    /// Debounces the empty → [settledEmpty] transition. A non-empty snapshot
    /// clears it immediately (and ends any resume window); an empty one schedules
    /// the flip after the grace, unless [isResuming] holds it.
    fun updateSettledEmpty(isEmpty: Boolean) {
        emptyDebounceTask?.cancel()
        emptyDebounceTask = null
        if (!isEmpty) {
            _settledEmpty.value = false
            isResuming = false
            resumeTask?.cancel()
            resumeTask = null
            return
        }
        if (isResuming) return
        val graceMs = emptyPlaceholderGraceMs
        emptyDebounceTask = scope.launch {
            delay(graceMs)
            _settledEmpty.value = true
        }
    }

    /// Enters the foreground-resume window: hides the placeholder until the
    /// timeline re-populates or the ceiling elapses.
    fun handleForeground() {
        _settledEmpty.value = false
        emptyDebounceTask?.cancel()
        emptyDebounceTask = null
        isResuming = true
        val ceilingMs = resumeGraceMs
        resumeTask?.cancel()
        resumeTask = scope.launch {
            delay(ceilingMs)
            isResuming = false
            updateSettledEmpty(_items.value.isEmpty())
        }
    }

    // MARK: - Observation lifecycle

    /// Starts observing the timeline. Returns *after* the first snapshot has been
    /// applied (or the stream finished without yielding), and returns the
    /// observation [Job] so callers can `join()` to know the stream drained.
    suspend fun start(): Job {
        observationGeneration += 1
        observationTask?.cancel()
        _settledEmpty.value = false
        suppressNextTickEdge = true
        emptyDebounceTask?.cancel()
        emptyDebounceTask = null
        _sessionStatus.value = null
        // A VM is cached per-room (ChatVMCache) and outlives a single visit, so
        // an undismissed attachment error from a prior visit must not resurface
        // as "fresh" on re-entry (same stale-state hazard as
        // ComposerViewModel.sendError).
        _attachmentError.value = null

        val firstSignal = CompletableDeferred<Unit>()
        fun fireOnce() {
            if (!firstSignal.isCompleted) firstSignal.complete(Unit)
        }

        val task = scope.launch {
            try {
                timeline.items().collect { snapshot ->
                    val before = _items.value.size
                    applySnapshot(snapshot)
                    _error.value = null
                    _hasReceivedFirstSnapshot.value = true
                    updateSettledEmpty(snapshot.isEmpty())
                    // Content → empty is the signature of a mirror wipe under an
                    // open view; refetch the newest page (nothing else does).
                    if (before > 0 && snapshot.isEmpty()) scheduleHistoryRefill()
                    fireOnce()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = error.message ?: error.toString()
            }
            // Fallthrough on normal finish or non-cancel error: flip the gate so
            // a never-warming room's placeholder isn't stuck hidden.
            _hasReceivedFirstSnapshot.value = true
            updateSettledEmpty(_items.value.isEmpty())
            fireOnce()
        }
        observationTask = task

        statusTask?.cancel()
        statusTask = scope.launch {
            timeline.sessionStatus().collect { update ->
                _sessionStatus.value = (_sessionStatus.value ?: SessionStatus()).merged(update)
            }
        }

        // _isTurnRunning is deliberately NOT reset before the stream re-arms:
        // the VM is cached across re-entries (ChatVMCache) and the Room
        // observation re-emits the current state immediately, so a
        // false-then-true blip would flicker the stop button on every chat
        // switch mid-turn.
        sessionStateTask?.cancel()
        sessionStateTask = scope.launch {
            timeline.sessionState().collect { state ->
                _isTurnRunning.value = SessionState.fromWire(state) == SessionState.Running
            }
        }

        // A prior failed image fetch only means "unreachable then" — once the
        // sync connection comes back up, give it another chance rather than
        // negative-caching it for the rest of the VM's (session-long) lifetime.
        // Eventually consistent, not live: clearing the cache doesn't retry any
        // already-composed row itself, it just lets the *next* image(url) call
        // (recomposition, scroll recycle, re-open) fetch again instead of
        // short-circuiting to the stale negative result.
        connectionTask?.cancel()
        connectionTask = scope.launch {
            timeline.connectionState().collect { state ->
                if (state is SyncConnectionState.Running) failedRequests.clear()
            }
        }

        firstSignal.await()
        return task
    }

    /// Generation-guarded stop: no-op unless [generation] still names the current
    /// observation (a stale view's teardown can fire after a successor's start).
    fun stop(generation: Int) {
        if (generation == observationGeneration) stop()
    }

    fun stop() {
        observationTask?.cancel()
        observationTask = null
        statusTask?.cancel()
        statusTask = null
        sessionStateTask?.cancel()
        sessionStateTask = null
        connectionTask?.cancel()
        connectionTask = null
        emptyDebounceTask?.cancel()
        emptyDebounceTask = null
        resumeTask?.cancel()
        resumeTask = null
        historyRefillTask?.cancel()
        historyRefillTask = null
    }

    /// One-shot refetch of the newest page after content → empty. Resets the
    /// end-of-history verdict (a wipe invalidates it). Single-flight.
    private fun scheduleHistoryRefill() {
        if (historyRefillTask != null) return
        reachedHistoryStart = false
        consecutiveNoGrowthPaginates = 0
        historyRefillTask = scope.launch {
            paginateBackward()
            historyRefillTask = null
        }
    }

    // MARK: - Pagination + actions

    suspend fun paginateBackward() {
        if (isPaginatingBackward || reachedHistoryStart) return
        isPaginatingBackward = true
        try {
            val beforeCount = _items.value.size
            try {
                timeline.paginateBackward(30)
                // Poll for the items stream to deliver the new snapshot.
                val deadline = System.currentTimeMillis() + snapshotWaitMs
                while (_items.value.size == beforeCount && System.currentTimeMillis() < deadline) {
                    delay(SNAPSHOT_POLL_MS)
                }
                val grew = _items.value.size > beforeCount
                if (grew) {
                    consecutiveNoGrowthPaginates = 0
                } else {
                    consecutiveNoGrowthPaginates += 1
                    if (consecutiveNoGrowthPaginates >= NO_GROWTH_LIMIT) reachedHistoryStart = true
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = error.message ?: error.toString()
            }
        } finally {
            isPaginatingBackward = false
        }
    }

    suspend fun markAsRead() {
        runCatching { timeline.markAsRead() }
    }

    /// Mac toolbar refresh + ⌘R: re-paginate from the head.
    suspend fun refresh() = paginateBackward()

    /// Sends a slash command (the Compact buttons), bypassing the composer.
    /// Failures are swallowed (the missing echo already tells the user).
    suspend fun sendCommand(command: String) {
        runCatching { timeline.sendText(command) }
    }

    /// Retry handler for own-messages whose send state is Failed or Queued —
    /// the timeline's tap-to-retry affordance. Requeues the message's outbox
    /// row and forces a send attempt (or a reconnect nudge when offline); the
    /// echo's state updates flow back through the normal `items()` snapshot
    /// stream.
    fun retrySend(itemID: String) {
        scope.launch { runCatching { timeline.retrySend(itemID) } }
    }

    /// Discards an unsent (queued/failed) own-message — the escape hatch for a
    /// message the user no longer wants delivered.
    fun discardSend(itemID: String) {
        scope.launch { runCatching { timeline.discardSend(itemID) } }
    }

    // MARK: - Media

    /// Returns cached bytes for a media URL, or `null` and kicks off a fetch.
    /// Idempotent: repeat calls coalesce to one in-flight request; URLs that
    /// fetch to `null` are remembered so it doesn't loop — until connectivity
    /// returns (see the `connectionTask` in [start]), and even then only
    /// eventually: the clear doesn't retry in place, it just lets the next call
    /// to [image] for that URL fetch again instead of short-circuiting.
    fun image(url: String): ByteArray? {
        resolvedImages[url]?.let { return it }
        if (url in _unavailableMedia.value) return null
        if (failedRequests.contains(url)) return null
        if (inFlightRequests.contains(url)) return null
        inFlightRequests.add(url)
        scope.launch {
            // fetchOutcome, not image() — a reaped image's 404 must land in
            // the permanent [unavailableMedia] set (drives the "Image expired"
            // placeholder) rather than the retry-bounded [failedRequests] LRU
            // that connectivity recovery clears (apple #139).
            when (val outcome = media.fetchOutcome(url)) {
                is MediaFetchOutcome.Data -> resolvedImages[url] = outcome.bytes
                MediaFetchOutcome.NotFound -> _unavailableMedia.value += url
                MediaFetchOutcome.Failure -> failedRequests[url] = Unit
            }
            inFlightRequests.remove(url)
        }
        return null
    }

    /// Non-fetching read of the resolved-media cache for a single URL.
    fun resolvedImage(url: String): ByteArray? = resolvedImages[url]

    /// Whether a file attachment's blob download is currently in flight —
    /// drives the timeline chip's spinner. [downloadingFiles] is the
    /// recomposition channel (the Swift port reads `@Observable` state here).
    fun isDownloadingFile(url: String): Boolean = url in _downloadingFiles.value

    /// Whether a fetch for this attachment came back 404 — the blob was reaped
    /// server-side (journal media reaper), which is permanent: blob ids are
    /// immutable. Drives the chips' Expired state for events that synced
    /// BEFORE the reap and so never carry the payload tombstone
    /// ([TimelineItem.Kind.File.expired]) — the 404 on tap is how an
    /// already-synced client learns. [unavailableMedia] is the recomposition
    /// channel, same as [isDownloadingFile]'s (apple #139).
    fun isMediaUnavailable(url: String): Boolean = url in _unavailableMedia.value

    /// Downloads a file attachment and writes it to
    /// `<directory>/matron-attachments/<url digest>/<sanitised filename>`,
    /// returning the written file or `null` on fetch/write failure — either
    /// failure also breadcrumbs and sets [attachmentError] so the file-tap
    /// affordance isn't a silent dead button. The temp filename preserves the
    /// original [filename] so the downstream open/share UI shows a sensible
    /// label instead of a UUID; uniqueness lives in the digest parent
    /// directory, because distinct attachments routinely share a display
    /// filename ("report.pdf" from two rooms) and a shared flat directory
    /// would let the second download clobber the first — after which the
    /// temp-file cache serves the wrong attachment's bytes (Bugbot on the
    /// Apple PR, apple #138). Files written here are *not* cleaned up — the OS
    /// reaps the cache dir under storage pressure and the size cost is bounded
    /// by attachments the user has actively opened.
    suspend fun writeTempFile(url: String, filename: String, directory: File): File? {
        // Known-reaped blob: no request — the server already said 404 and
        // ids never come back. The chip's Expired state is the feedback.
        if (url in _unavailableMedia.value) return null
        // Repeat open: serve the temp file written last time (the OS may have
        // reaped the cache dir between launches — fall through and re-download
        // if it's gone).
        fileTempFiles[url]?.takeIf { it.exists() }?.let { return it }
        // Re-tap while the (multi-second) download is still running: a no-op,
        // not a second parallel download. The chip's spinner (driven by
        // [isDownloadingFile]) is the "hold on" signal — deliberately no
        // [attachmentError] here. `update` makes the check-and-claim atomic
        // so two concurrent callers can't both pass the guard.
        var claimed = false
        _downloadingFiles.update { current ->
            claimed = url !in current
            if (claimed) current + url else current
        }
        if (!claimed) return null
        try {
            val bytes: ByteArray
            when (val outcome = media.fetchOutcome(url)) {
                is MediaFetchOutcome.Data -> bytes = outcome.bytes
                MediaFetchOutcome.NotFound -> {
                    // Permanent: flip the chip to Expired and stop re-fetching
                    // — no error banner, the chip itself says why (apple #139).
                    MatronDebug.breadcrumb("writeTempFile: blob reaped (404) for $url")
                    _unavailableMedia.value += url
                    return null
                }
                MediaFetchOutcome.Failure -> {
                    // Transient (network/auth): retryable, banner as before.
                    MatronDebug.breadcrumb("writeTempFile: media fetch failed for $url")
                    _attachmentError.value = "Couldn't open \"$filename\" — check your connection and try again."
                    return null
                }
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(File(directory, "matron-attachments"), attachmentURLDigest(url))
                        .apply { mkdirs() }
                    val dest = File(dir, sanitisedAttachmentFilename(filename))
                    dest.writeBytes(bytes)
                    dest
                }.onFailure { MatronDebug.breadcrumb("writeTempFile: disk write failed for $filename: $it") }
                    .getOrNull()
            }
            if (written == null) {
                _attachmentError.value = "Couldn't open \"$filename\"."
            } else {
                fileTempFiles[url] = written
            }
            return written
        } finally {
            _downloadingFiles.update { it - url }
        }
    }

    val resolvedImageCount: Int get() = resolvedImages.count
    val failedRequestCount: Int get() = failedRequests.count

    // MARK: - Ask-user prompts

    /// The most recent still-unanswered, unexpired ask-user prompt, or `null`.
    /// A prompt counts answered when it's in [answeredPromptIDs], or the timeline
    /// holds our own `prompt_reply` targeting it.
    fun pendingAsk(): AskUserPromptContext? {
        persistVisibleAnswers()
        val answeredInTimeline = collectOwnAnswers()
        for (item in _items.value.asReversed()) {
            val kind = item.kind
            if (kind !is TimelineItem.Kind.AskUser) continue
            if (answeredPromptIDs.contains(kind.eventID)) continue
            if (answeredInTimeline.contains(kind.eventID)) continue
            val expiresAt = kind.event.expiresAt
            if (expiresAt != null && !Instant.now().isBefore(expiresAt)) continue
            return AskUserPromptContext(kind.eventID, kind.event)
        }
        return null
    }

    /// Folds cross-device answers visible in the timeline into the persisted
    /// answered set, intersected with prompts actually present (so replies to
    /// ordinary messages don't grow the set without bound).
    fun persistVisibleAnswers() {
        val answeredInTimeline = mutableSetOf<String>()
        val promptIDsInTimeline = mutableSetOf<String>()
        for (item in _items.value) {
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUserAnswer && kind.promptEventID.isNotEmpty() && item.isOwn) {
                answeredInTimeline.add(kind.promptEventID)
            }
            if (item.isOwn) item.inReplyToEventID?.let { answeredInTimeline.add(it) }
            if (kind is TimelineItem.Kind.AskUser) promptIDsInTimeline.add(kind.eventID)
        }
        for (id in answeredInTimeline.intersect(promptIDsInTimeline)) {
            if (!answeredPromptIDs.contains(id)) markPromptAnswered(id)
        }
    }

    /// True if [eventID]'s prompt was answered by US (this device, persisted, or
    /// our own cross-device answer in the timeline).
    fun isPromptAnswered(eventID: String): Boolean {
        if (answeredPromptIDs.contains(eventID)) return true
        for (item in _items.value) {
            if (!item.isOwn) continue
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUserAnswer && kind.promptEventID == eventID) return true
            if (item.inReplyToEventID == eventID) return true
        }
        return false
    }

    /// Persists [eventID] as answered so push re-decryption can't re-pop it.
    fun markPromptAnswered(eventID: String) {
        answeredPromptIDs.add(eventID)
        answeredPromptStore.setStringList(answeredPromptsKey, answeredPromptIDs.toList())
    }

    /// Factory for the sheet VM, keeping [timeline] private to this class.
    fun makeAskUserSheetViewModel(
        eventID: String,
        event: AskUserEvent,
        onClose: () -> Unit,
    ): AskUserSheetViewModel = AskUserSheetViewModel(event, eventID, timeline, onClose)

    /// Stable per-prompt sheet VM for the inline card, created + cached on first
    /// use. `null` when no such prompt is in the timeline.
    fun askViewModel(eventID: String): AskUserSheetViewModel? {
        askViewModels[eventID]?.let { return it }
        val event = askEvent(eventID) ?: return null
        val vm = makeAskUserSheetViewModel(eventID, event) { markPromptAnswered(eventID) }
        askViewModels[eventID] = vm
        return vm
    }

    /// The chosen answer for a prompt (for the card's resolved state), or `null`.
    /// Buttons map selected values back to labels; text channel returns the body.
    fun answerSummary(promptEventID: String): String? {
        for (item in _items.value) {
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUserAnswer && kind.promptEventID == promptEventID && item.isOwn) {
                return mapValuesToLabels(kind.selectedValues, promptEventID)
            }
        }
        for (item in _items.value) {
            if (item.isOwn && item.inReplyToEventID == promptEventID) {
                val kind = item.kind
                if (kind is TimelineItem.Kind.Text) return kind.body
            }
        }
        return null
    }

    private fun collectOwnAnswers(): Set<String> {
        val answered = mutableSetOf<String>()
        for (item in _items.value) {
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUserAnswer && kind.promptEventID.isNotEmpty() && item.isOwn) {
                answered.add(kind.promptEventID)
            }
            if (item.isOwn) item.inReplyToEventID?.let { answered.add(it) }
        }
        return answered
    }

    private fun askEvent(eventID: String): AskUserEvent? {
        for (item in _items.value) {
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUser && kind.eventID == eventID) return kind.event
        }
        return null
    }

    private fun mapValuesToLabels(values: List<String>, promptEventID: String): String {
        val labelByValue = mutableMapOf<String, String>()
        when (val kind = askEvent(promptEventID)?.kind) {
            is AskUserEvent.InputKind.Choice -> kind.options.forEach { labelByValue[it.value] = it.label }
            is AskUserEvent.InputKind.MultiChoice -> kind.options.forEach { labelByValue[it.value] = it.label }
            else -> {}
        }
        return values.joinToString(", ") { labelByValue[it] ?: it }
    }

    companion object {
        /// Cap for both [resolvedImages] and [failedRequests].
        const val MEDIA_CACHE_LIMIT = 100

        /// Persisted marker for a consent card the server said was no longer
        /// awaiting an answer. Not a decision, so it can't collide with
        /// [AgentChatDecision]'s wire values.
        private const val EXPIRED_ANSWER = "expired"

        internal fun describeAgentChatError(error: Throwable): String = when (error) {
            is JournalApiError.Transport ->
                "Couldn't reach the server — check your connection and try again."
            is JournalApiError.NotFound -> "That request is no longer on the server."
            else -> "The server refused that answer."
        }

        private const val DEFAULT_WINDOW_SIZE = 120
        private const val WINDOW_GROWTH_STEP = 120
        private const val NO_GROWTH_LIMIT = 2
        private const val SNAPSHOT_POLL_MS = 50L

        /// Scroll-target the views pin across a history-window extension. Prefers
        /// the topmost visible non-separator row; falls back to the pre-extend
        /// window's first message row, then `null`.
        fun historyPinTarget(visibleIDs: List<String>, preExtendRows: List<TimelineRow>): String? {
            visibleIDs.firstOrNull { !it.startsWith("sep:") }?.let { return it }
            for (row in preExtendRows) if (row is TimelineRow.Message) return row.item.id
            return null
        }

        /// First 8 bytes of the URL's SHA-256, hex — the per-attachment temp
        /// subdirectory name (mirrors the Swift port's CryptoKit digest,
        /// apple #138). Test seam: `internal` so the keying contract can be
        /// pinned without reading the VM's private cache.
        internal fun attachmentURLDigest(url: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /// Strip path-traversal and directory-separator components from an
        /// event-attached filename — it arrives from event metadata, which is
        /// attacker-controllable, so a malicious sender must not be able to
        /// craft `../../foo` to escape the attachments dir. Keeps the basename
        /// for human-friendly open/share labels; inputs that reduce to an
        /// empty or `.`/`..`-only string fall back to a UUID so the write
        /// always lands inside the attachments dir. Test seam: `internal` so
        /// tests can assert the contract without hitting disk.
        internal fun sanitisedAttachmentFilename(raw: String): String {
            // Basename drops any directory tree the sender embedded; handle
            // both separator styles (Windows-style senders send `\`).
            val trimmed = raw.substringAfterLast('/').substringAfterLast('\\')
            val stripped = trimmed.replace(":", "_").trim()
            if (stripped.isEmpty() || stripped == "." || stripped == "..") {
                return UUID.randomUUID().toString()
            }
            return stripped
        }
    }
}
