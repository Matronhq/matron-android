package chat.matron.android.viewmodels

import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.chat.ConversationSummaryEntry
import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.TimelineService
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.models.MatronDebug
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.AgentSpawnDecision
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.SessionState
import chat.matron.android.models.SessionStatus
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.platform.Haptics
import chat.matron.android.storage.LRUCache
import chat.matron.android.search.SearchService
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
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
    /// Answers agent-spawn consent cards. Nullable for the same reason as
    /// [agentChat]. Unlike [agentChat], resolution is never remembered by
    /// this view model — see [agentSpawnState].
    private val agentSpawn: AgentSpawnAnswering? = null,
    /// Backs the in-conversation search (apple #172). Nullable: fakes and a
    /// failed index open leave the entry point a no-op — the bar must not
    /// come up reporting bogus emptiness.
    private val search: SearchService? = null,
) {
    // MARK: - In-conversation search (apple #172)

    /// The search bar's state: the submitted query, its matches as seqs
    /// (newest first), and the focused index into them.
    data class ChatSearchState(val query: String, val matchSeqs: List<Long>, val index: Int)

    private val _chatSearch = MutableStateFlow<ChatSearchState?>(null)
    val chatSearch: StateFlow<ChatSearchState?> = _chatSearch.asStateFlow()

    /// A jump armed while the stream wasn't live (the results tap runs before
    /// the destination's start(), or a cached VM whose previous view already
    /// ran stop()): fires on the restarted stream's first delivery. Focusing
    /// against a dead stream would sample paginate growth against nothing and
    /// falsely latch `reachedHistoryStart`.
    private var pendingChatSearchFocusSeq: Long? = null

    /// Bumped by every [beginChatSearch] and [endChatSearch]; an in-flight
    /// query whose generation moved on discards its result.
    private var chatSearchGeneration = 0

    /// Runs the room-scoped query, focuses the NEWEST match, and arms the bar.
    /// A re-query disarms the previous query's parked jump.
    suspend fun beginChatSearch(query: String) {
        val service = search ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        // Generation token: a newer query or a close while this one is still
        // in flight supersedes it, so a slow earlier query can't overwrite
        // the newer state or resurrect a closed bar (Bugbot, #56).
        val generation = ++chatSearchGeneration
        val hits = try {
            service.query(trimmed, roomID, CHAT_SEARCH_MATCH_LIMIT)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            emptyList()
        }
        if (generation != chatSearchGeneration) return
        // A hit whose id is not a seq (the index only ever stores seqs) is
        // dropped rather than derailing navigation.
        val seqs = hits.mapNotNull { it.id.toLongOrNull() }
        _chatSearch.value = ChatSearchState(trimmed, seqs, 0)
        pendingChatSearchFocusSeq = null
        val newest = seqs.firstOrNull() ?: run { focusTask?.cancel(); return }
        focusOrPark(newest)
    }

    private suspend fun focusOrPark(seq: Long) {
        if (_hasReceivedFirstSnapshot.value && observationTask?.isActive == true) {
            pendingChatSearchFocusSeq = null
            focus(seq)
        } else {
            pendingChatSearchFocusSeq = seq
        }
    }

    /// ∧ steps OLDER (higher index — matches are newest-first), ∨ steps back
    /// toward the newest; clamped at both ends without re-focusing.
    suspend fun stepChatSearch(older: Boolean) {
        val state = _chatSearch.value ?: return
        val next = state.index + (if (older) 1 else -1)
        if (next !in state.matchSeqs.indices) return
        _chatSearch.value = state.copy(index = next)
        focusOrPark(state.matchSeqs[next])
    }

    /// Dismisses the bar and abandons any jump in flight — a deep hit's
    /// pagination would otherwise land `pendingFocusID` after the user closed
    /// search, scrolling the transcript out from under them.
    fun endChatSearch() {
        chatSearchGeneration += 1
        _chatSearch.value = null
        pendingChatSearchFocusSeq = null
        focusTask?.cancel()
    }

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

    /// TOC summary entries for this conversation, newest-first — mirrors
    /// `TimelineService.summaryEntriesStream()`. Empty until the journal
    /// replays the room's summary rows (or forever, on backends without one).
    private val _summaryEntries = MutableStateFlow<List<ConversationSummaryEntry>>(emptyList())
    val summaryEntries: StateFlow<List<ConversationSummaryEntry>> = _summaryEntries.asStateFlow()

    private val _rows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val rows: StateFlow<List<TimelineRow>> = _rows.asStateFlow()

    private val _windowedRows = MutableStateFlow<List<TimelineRow>>(emptyList())
    val windowedRows: StateFlow<List<TimelineRow>> = _windowedRows.asStateFlow()

    private val _rowAnchorIDs = MutableStateFlow<Set<String>>(emptySet())
    val rowAnchorIDs: StateFlow<Set<String>> = _rowAnchorIDs.asStateFlow()

    private val _activityLabel = MutableStateFlow<String?>(null)
    val activityLabel: StateFlow<String?> = _activityLabel.asStateFlow()

    /// True when the loaded timeline carries messages from ≥2 distinct
    /// NON-own senders — the agent-chat room signature ("dan-mac" and
    /// "dev-2" both replying), as opposed to an ordinary 1:1 chat with one
    /// bot. The timeline view reads this to decide whether to pass a sender
    /// into `MessageBubble` at all — 1:1 chats must render exactly as before
    /// (no avatar noise for "the bot"). Own messages never count, even
    /// toward a single-sender total, because the flag is specifically about
    /// attributing NON-own bubbles.
    ///
    /// Restricted to the durable message kinds that ever render a
    /// `MessageBubble` — `Text` / `Image` / `File` — NOT a raw scan of
    /// [items]. The synthetic rows the mapper synthesises mid-turn
    /// (`streamingItem` / `activityItem` / `toolStreamItem`) hardcode
    /// `sender = "agent"`, `isOwn = false`; counting them made an ordinary
    /// 1:1 chat (bot "matron" + its own ephemeral "agent" row) register as
    /// multi-sender for the whole turn, sprouting avatars that vanish again
    /// once the turn settles. `ActivityIndicator`, `ToolStreamLive`,
    /// `StateChange`, tool cards etc. are structurally excluded by the kind
    /// check.
    ///
    /// Memoised in [applyDerivedRecompute] (not computed per read) — the
    /// timeline reads it once per row, and an O(N) scan there is exactly the
    /// scroll-regression pattern the Apple original documents. Ports
    /// `ChatViewModel.hasMultipleSenders` (apple #141), including the
    /// Bugbot ephemeral-streaming-row exclusion.
    private val _hasMultipleSenders = MutableStateFlow(false)
    val hasMultipleSenders: StateFlow<Boolean> = _hasMultipleSenders.asStateFlow()

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

    /// The row the render window's newest edge is pinned to while the user
    /// has slid up into history past the cap, or null when the window is
    /// attached to the tail (apple #166). Identity-anchored, not index-
    /// anchored, so stream appends below don't move a slid window.
    private val _windowTailAnchorID = MutableStateFlow<String?>(null)
    val windowTailAnchorID: StateFlow<String?> = _windowTailAnchorID.asStateFlow()

    /// True while the window's newest edge is the transcript's newest row.
    val windowContainsTail: Boolean get() = _windowTailAnchorID.value == null

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
    private var summaryEntriesTask: Job? = null
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

    // MARK: - Agent-spawn consent cards

    /// The durable resolution for every agent-spawn card currently in the
    /// timeline, rebuilt fresh from [TimelineItem.Kind.SpawnOutcomeRow] items
    /// on every snapshot (see [applySnapshot]) — NOT persisted, unlike
    /// [agentChatAnswers]. Keyed by `SpawnOutcome.requestId`, the
    /// correlation key back to the card (see `AgentSpawnRequest.requestId`'s
    /// doc). A fresh view model for the same room reconstructs this purely
    /// from the replayed snapshot, with zero [KeyValueStore] involvement.
    private val _spawnOutcomes = MutableStateFlow<Map<String, SpawnOutcome>>(emptyMap())
    val spawnOutcomes: StateFlow<Map<String, SpawnOutcome>> = _spawnOutcomes.asStateFlow()

    /// Live per-card state while an answer is in flight or has failed. Not
    /// persisted, same rationale as [_agentChatStates]: a send interrupted by
    /// process death or a cancelled scope should come back answerable rather
    /// than stuck — and once the durable [SpawnOutcome] lands it supersedes
    /// whatever is left here regardless (see [agentSpawnState]'s precedence).
    private val _agentSpawnStates = MutableStateFlow<Map<String, AgentSpawnCardState>>(emptyMap())
    val agentSpawnStates: StateFlow<Map<String, AgentSpawnCardState>> = _agentSpawnStates.asStateFlow()

    /// Render state for one agent-spawn card. Precedence: the durable
    /// [SpawnOutcome] (a journaled `spawn_outcome` event, [_spawnOutcomes])
    /// always wins — once it lands there is nothing left to answer, no
    /// matter what a stale transient state says; then the transient
    /// in-flight/failed/[AgentSpawnCardState.Unavailable] state
    /// ([_agentSpawnStates]); then [AgentSpawnCardState.Idle] if an answerer
    /// is wired, else [AgentSpawnCardState.Unavailable] — mirrors
    /// [agentChatState]'s nil-answerer -> `Expired` convention: a card with
    /// no answerer must not offer buttons that do nothing.
    fun agentSpawnState(eventID: String, request: AgentSpawnRequest): AgentSpawnCardState {
        _spawnOutcomes.value[request.requestId]?.let { return AgentSpawnCardState.Resolved(it) }
        _agentSpawnStates.value[eventID]?.let { return it }
        return if (agentSpawn == null) AgentSpawnCardState.Unavailable else AgentSpawnCardState.Idle
    }

    /// Answers an agent-spawn card. Unlike [answerAgentChat] there is no
    /// local "answered" memory to write on success: the durable record is
    /// the journal's own `spawn_outcome` event, which [_spawnOutcomes] picks
    /// up on its own the moment it lands and which then supersedes whatever
    /// this leaves behind in [_agentSpawnStates]. So a plain success here
    /// does nothing further — the card just stays `Sending` until either the
    /// outcome event supersedes it, or a later failure makes it answerable
    /// again.
    ///
    /// Runs on [scope], not the caller's — the same "the row can scroll away
    /// or the chat can close mid-request" rationale as [answerAgentChat]: a
    /// row-scoped coroutine would be cancelled with the request still in
    /// flight, leaving the card stuck on `Sending` with no way to retry.
    fun answerAgentSpawn(
        eventID: String,
        request: AgentSpawnRequest,
        decision: AgentSpawnDecision,
    ) {
        val answerer = agentSpawn ?: return
        when (agentSpawnState(eventID, request)) {
            is AgentSpawnCardState.Resolved, is AgentSpawnCardState.Sending,
            AgentSpawnCardState.Unavailable,
            -> return
            else -> {}
        }
        setAgentSpawnState(eventID, AgentSpawnCardState.Sending)
        scope.launch {
            try {
                answerer.answerAgentSpawn(request.requestId, decision)
            } catch (cancel: CancellationException) {
                // Whole chat is going away. Drop the in-flight marker so the
                // card comes back answerable rather than stuck mid-send.
                _agentSpawnStates.value = _agentSpawnStates.value - eventID
                throw cancel
            } catch (conflict: JournalApiError.Conflict) {
                // The row stopped awaiting an answer between the card being
                // drawn and the tap (answered on another device, or
                // expired). Settle it as Unavailable — "no longer waiting for
                // an answer" — rather than a failure the user would only
                // retry; the real spawn_outcome event, once it lands,
                // supersedes this regardless (see [agentSpawnState]'s
                // precedence).
                setAgentSpawnState(eventID, AgentSpawnCardState.Unavailable)
            } catch (error: Throwable) {
                setAgentSpawnState(eventID, AgentSpawnCardState.Failed(describeAgentSpawnError(error)))
            }
        }
    }

    private fun setAgentSpawnState(eventID: String, state: AgentSpawnCardState) {
        _agentSpawnStates.value = _agentSpawnStates.value + (eventID to state)
    }

    // MARK: - Snapshot → derived state

    private fun applySnapshot(snapshot: List<TimelineItem>) {
        _items.value = snapshot
        _spawnOutcomes.value = snapshot.mapNotNull { item ->
            (item.kind as? TimelineItem.Kind.SpawnOutcomeRow)?.outcome
        }.associateBy { it.requestId }
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
        // Scratch for the queued-release memo: hidden "qr:" answer rows'
        // values, and each card's own release key. Joined after the loop —
        // a release can precede or follow its card in the snapshot.
        val qrReleaseValues = mutableMapOf<String, List<String>>()
        val cardReleaseKey = mutableMapOf<String, String>()
        val nonOwnSenders = mutableSetOf<String>()
        var nextHasMultipleSenders = false
        for (item in _items.value) {
            when (val kind = item.kind) {
                // The trailing activity indicator renders as a fixed footer, NOT a
                // row (as a row it became the scroll anchor during every bot turn
                // and vanished on completion).
                is TimelineItem.Kind.ActivityIndicator -> {
                    nextActivityLabel = kind.label
                    continue
                }
                // Hidden kinds, kept out of rows AND day bucketing. Bridge
                // release rows ("qr:" keys) are captured for the memo first:
                // earliest wins (the snapshot is seq-ascending), so a committed
                // `send` followed by boot reconcile's terminal `expired` keeps
                // reporting the send that actually happened.
                is TimelineItem.Kind.AskUserAnswer -> {
                    if (kind.promptEventID.startsWith(QUEUED_RELEASE_KEY_PREFIX)) {
                        qrReleaseValues.putIfAbsent(kind.promptEventID, kind.selectedValues)
                    }
                    continue
                }
                is TimelineItem.Kind.StateChange -> continue
                // Queue cards remember their bridge prompt id; captured here so
                // the release memo can key by the card's event id.
                is TimelineItem.Kind.AskUser -> {
                    kind.event.queuedReleasePromptID?.let {
                        cardReleaseKey[kind.eventID] = JournalTimelineMapper.queuedReleaseAnswerKey(it)
                    }
                }
                else -> {}
            }
            // hasMultipleSenders only counts the durable message kinds that
            // ever render an avatar (Text / Image / File) — NOT ToolStreamLive,
            // StateChange, tool cards, etc. (already excluded above or below by
            // kind). That alone still isn't enough: the mid-turn streaming
            // placeholder row (JournalTimelineMapper.streamingItem) is ALSO a
            // Text kind — it borrows the real message kind so it renders as a
            // normal bubble while the reply streams in — but it hardcodes
            // `sender = "agent"`, which is not the bot's real
            // (displayName-resolved) sender. Left uncounted, a plain 1:1 chat
            // (bot "matron" + its own streaming echo "agent") would spuriously
            // register as multi-sender for the whole turn.
            // TimelineItem.isEphemeralStreamingPlaceholder is the single source
            // of truth for this exclusion — timelineAvatarSender needs the same
            // check on the render side (Bugbot, apple #141) and must not drift
            // from this one.
            if (!item.isOwn && !item.isEphemeralStreamingPlaceholder) {
                when (item.kind) {
                    is TimelineItem.Kind.Text, is TimelineItem.Kind.Image, is TimelineItem.Kind.File -> {
                        nonOwnSenders.add(item.sender)
                        if (nonOwnSenders.size >= 2) nextHasMultipleSenders = true
                    }
                    else -> {}
                }
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
        val nextReleaseResolved = mutableMapOf<String, List<String>>()
        for ((cardID, key) in cardReleaseKey) {
            qrReleaseValues[key]?.let { nextReleaseResolved[cardID] = it }
        }
        if (nextReleaseResolved != _releaseResolvedAnswers.value) _releaseResolvedAnswers.value = nextReleaseResolved
        _rows.value = nextRows
        firstRenderableItemID = first
        _lastRenderableItemID.value = last
        lastRenderableItemIsOwn = lastIsOwn
        _hasMultipleSenders.value = nextHasMultipleSenders
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
        val rows = _rows.value
        var anchorIdx: Int? = null
        _windowTailAnchorID.value?.let { anchorID ->
            anchorIdx = rows.indexOfLast { it.id == anchorID }.takeIf { it >= 0 }
            if (anchorIdx == null) {
                // The anchored row vanished (a wipe, a retired echo): rescue
                // to the nearest surviving row we were showing, else reattach.
                val rescue = rescueAnchor()
                if (rescue != null) {
                    _windowTailAnchorID.value = rescue.first
                    anchorIdx = rescue.second
                }
            }
        }
        val window = anchorIdx?.let { upper ->
            val end = upper + 1
            rows.subList(maxOf(0, end - visibleWindowSize), end).toMutableList()
        } ?: run {
            if (_windowTailAnchorID.value != null) _windowTailAnchorID.value = null
            rows.takeLast(visibleWindowSize).toMutableList()
        }
        val head = window.firstOrNull()
        if (head is TimelineRow.Message) {
            window.add(0, TimelineRow.Separator(head.item.timestamp))
        }
        _windowedRows.value = window
    }

    // MARK: - History window (apple #166: capped at MAX_WINDOW_SIZE and slid
    // through history on an identity anchor)

    /// The nearest surviving row among what the window was showing, newest
    /// first — durable messages only (an echo or streaming placeholder can
    /// retire at any moment and would strand the anchor again).
    private fun rescueAnchor(): Pair<String, Int>? {
        val rows = _rows.value
        if (_windowedRows.value.isEmpty() || rows.isEmpty()) return null
        val indexByID = HashMap<String, Int>(rows.size)
        rows.forEachIndexed { i, row -> indexByID[row.id] = i }
        for (row in _windowedRows.value.asReversed()) {
            val message = row as? TimelineRow.Message ?: continue
            if (message.item.id.startsWith("echo:") || message.item.id.startsWith("eph:")) continue
            val i = indexByID[row.id] ?: continue
            return row.id to i
        }
        return null
    }

    /// The newest durable message row at or below [index] (not below [floor]).
    private fun anchorID(atOrBelow: Int, floor: Int = 0): String? {
        val rows = _rows.value
        var i = atOrBelow
        while (i >= floor) {
            val row = rows[i]
            if (row is TimelineRow.Message && !row.item.id.startsWith("echo:") && !row.item.id.startsWith("eph:")) return row.id
            i -= 1
        }
        return null
    }

    /// Grows the window over loaded rows up to the cap, then slides it up
    /// through history on an anchor. Returns false when nothing older is
    /// loaded (time to paginate).
    private fun extendWindowUpLocally(): Boolean {
        val rows = _rows.value
        val upper = _windowTailAnchorID.value
            ?.let { id -> rows.indexOfLast { it.id == id }.takeIf { it >= 0 } }
            ?.let { it + 1 } ?: rows.size
        val lower = maxOf(0, upper - visibleWindowSize)
        if (lower <= 0) return false
        if (visibleWindowSize < MAX_WINDOW_SIZE) {
            visibleWindowSize = minOf(MAX_WINDOW_SIZE, minOf(upper, visibleWindowSize + WINDOW_GROWTH_STEP))
        } else {
            val newUpper = maxOf(visibleWindowSize, upper - WINDOW_GROWTH_STEP)
            val anchor = anchorID(atOrBelow = newUpper - 1) ?: return true // no durable row to hold — keep the window
            _windowTailAnchorID.value = anchor
        }
        recomputeWindow()
        return true
    }

    /// Reveals older content: grows (then slides) the render window over
    /// loaded rows first (instant), fetching another page only when the
    /// window already shows everything local. Holds [isExtendingWindow]
    /// through the layout pass.
    suspend fun extendHistoryWindow() {
        if (_isExtendingWindow.value || isPaginatingBackward) return
        _isExtendingWindow.value = true
        if (extendWindowUpLocally()) {
            delay(150)
            _isExtendingWindow.value = false
            return
        }
        _isExtendingWindow.value = false
        paginateBackward()
        _isExtendingWindow.value = true
        if (extendWindowUpLocally()) delay(150)
        _isExtendingWindow.value = false
    }

    /// Reveals newer content below a slid window: slides the anchor down a
    /// step, reattaching at the tail once it reaches the newest rows. A
    /// no-op while the window contains the tail. Holds [isExtendingWindow]
    /// through the layout pass like [extendHistoryWindow].
    fun revealNewerHistory() {
        if (_isExtendingWindow.value || isPaginatingBackward) return
        val currentAnchor = _windowTailAnchorID.value ?: return
        val rows = _rows.value
        val anchorIdx = rows.indexOfLast { it.id == currentAnchor }.takeIf { it >= 0 } ?: return
        _isExtendingWindow.value = true
        val newUpper = anchorIdx + 1 + WINDOW_GROWTH_STEP
        if (newUpper >= rows.size) {
            _windowTailAnchorID.value = null
        } else {
            val anchor = anchorID(atOrBelow = newUpper - 1)
            if (anchor != null && anchor != currentAnchor) _windowTailAnchorID.value = anchor
            // else: only transient rows follow — hold.
        }
        recomputeWindow()
        scope.launch {
            delay(150)
            _isExtendingWindow.value = false
        }
    }

    /// Snaps the window back to steady-state. Called only when no reader is up
    /// in history.
    fun resetHistoryWindow() {
        if (visibleWindowSize == DEFAULT_WINDOW_SIZE && _windowTailAnchorID.value == null) return
        visibleWindowSize = DEFAULT_WINDOW_SIZE
        _windowTailAnchorID.value = null
        recomputeWindow()
    }

    /// [resetHistoryWindow], but only if no re-subscription happened since
    /// [generation] was read — a view's deferred reset must not clobber a
    /// newer visit's window.
    fun resetHistoryWindow(ifGeneration: Int) {
        if (ifGeneration != observationGeneration) return
        resetHistoryWindow()
    }

    /// Widens the window so a remembered scroll position outside the tail window
    /// can be scrolled to on restore. Holds [isExtendingWindow] through the pass.
    fun ensureWindowContains(id: String) {
        val index = _rows.value.indexOfLast { row ->
            if (row is TimelineRow.Message) row.item.id == id else row.id == id
        }
        if (index < 0) return
        val rows = _rows.value
        val needed = rows.size - index + 20
        if (needed <= MAX_WINDOW_SIZE) {
            // Within the cap: widen from the tail as before.
            if (needed <= visibleWindowSize && _windowTailAnchorID.value == null) return
            _windowTailAnchorID.value = null
            visibleWindowSize = maxOf(visibleWindowSize, minOf(MAX_WINDOW_SIZE, needed))
        } else {
            // Deep target: mount a capped window around it instead of every
            // row between it and the tail (apple #166).
            val anchor = anchorID(atOrBelow = minOf(rows.lastIndex, index + 20), floor = index) ?: rows[index].id
            _windowTailAnchorID.value = anchor
            visibleWindowSize = MAX_WINDOW_SIZE
        }
        _isExtendingWindow.value = true
        recomputeWindow()
        scope.launch {
            delay(150)
            _isExtendingWindow.value = false
        }
    }

    // MARK: - Summaries TOC jump-to-message

    /// Pending scroll anchor for a TOC jump. The timeline observes this exactly
    /// like the Apple views observe `pendingFocusID`: disengage tail-follow,
    /// call [ensureWindowContains], scroll the LazyList to the row, then
    /// [clearPendingFocus].
    private val _pendingFocusID = MutableStateFlow<String?>(null)
    val pendingFocusID: StateFlow<String?> = _pendingFocusID.asStateFlow()

    /// Clears [pendingFocusID] once a view has consumed it and scrolled.
    fun clearPendingFocus() {
        _pendingFocusID.value = null
    }

    /// Backing job for [focus] — a second call must supersede rather than race
    /// the first: two in-flight jumps would busy-yield against each other's
    /// [paginateBackward] calls with no ordering guarantee over which one's
    /// `pendingFocusID` write wins. Cancelling the superseded job makes the
    /// outcome deterministic — but only because BOTH exits from the paginate
    /// loop in [performFocus] re-check cancellation: the loop-top check for
    /// the common case where cancellation lands mid-paginate, and a second
    /// check right after the loop for the uncontended `break` (no growth and
    /// nothing else in flight), which otherwise falls straight through to the
    /// unconditional `pendingFocusID` write with no cancellation check in
    /// between. Only the most recent call's target is ever landed.
    private var focusTask: Job? = null

    /// Navigates the transcript to the message nearest (at or before) [seq] —
    /// the summaries TOC sheet's jump-to-message action. Pages history
    /// backward until the target region is loaded locally, giving up when
    /// [reachedHistoryStart] latches; at that point it lands on the oldest row
    /// actually available rather than doing nothing. Port of the Apple
    /// `focus(seq:)` (see its doc comment for the no-progress-bail rationale:
    /// [paginateBackward]'s reentrancy guard early-returns when another call
    /// is in flight, so a bare loop could spin without ever progressing).
    suspend fun focus(seq: Long) {
        focusTask?.cancel()
        val job = scope.launch { performFocus(seq) }
        focusTask = job
        job.join()
    }

    private suspend fun performFocus(seq: Long) {
        var paginates = 0
        while (nearestMessageID(atOrBefore = seq) == null && !reachedHistoryStart) {
            if (!currentCoroutineContext().isActive) return
            // A search hit can sit arbitrarily deep; bound the walk so a
            // stale index entry can't page forever (apple #172).
            if (paginates >= MAX_FOCUS_PAGINATES) break
            paginates += 1
            val beforeCount = _items.value.size
            paginateBackward()
            val madeProgress = _items.value.size != beforeCount || reachedHistoryStart
            if (!madeProgress) {
                // Uncontended no-growth: bail to the oldest-row fallback
                // rather than waiting out the reachedHistoryStart latch.
                if (!isPaginatingBackward) break
                yield()
            }
        }
        // Every exit from the loop above — including the uncontended `break` —
        // must re-check: a superseded job that breaks out would otherwise land
        // its fallback target over the newer call's.
        if (!currentCoroutineContext().isActive) return
        val target = nearestMessageID(atOrBefore = seq) ?: oldestMessageID() ?: return
        ensureWindowContains(target)
        _pendingFocusID.value = target
    }

    /// Latest [rows] message id whose seq is `<= seq`, or `null` if every
    /// loaded message postdates it. Rows are ascending (oldest first — see
    /// [applyDerivedRecompute]), so the scan stops at the first row past the
    /// target. Non-numeric ids (echoes, ephemerals) are skipped.
    private fun nearestMessageID(atOrBefore: Long): String? {
        var best: String? = null
        for (row in _rows.value) {
            if (row !is TimelineRow.Message) continue
            val rowSeq = row.item.id.toLongOrNull() ?: continue
            if (rowSeq <= atOrBefore) best = row.item.id else break
        }
        return best
    }

    /// The oldest loaded message row's id — the fallback landing spot when the
    /// target region never loads (history genuinely doesn't reach that far).
    private fun oldestMessageID(): String? {
        for (row in _rows.value) {
            if (row is TimelineRow.Message && row.item.id.toLongOrNull() != null) return row.item.id
        }
        return null
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
                    // A search jump parked while the stream was dead fires
                    // now that a delivery proves it live (apple #172).
                    pendingChatSearchFocusSeq?.let { seq ->
                        pendingChatSearchFocusSeq = null
                        scope.launch { focus(seq) }
                    }
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

        summaryEntriesTask?.cancel()
        summaryEntriesTask = scope.launch {
            timeline.summaryEntriesStream().collect { entries ->
                _summaryEntries.value = entries
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
        summaryEntriesTask?.cancel()
        summaryEntriesTask = null
        connectionTask?.cancel()
        connectionTask = null
        emptyDebounceTask?.cancel()
        emptyDebounceTask = null
        resumeTask?.cancel()
        resumeTask = null
        historyRefillTask?.cancel()
        historyRefillTask = null
        focusTask?.cancel()
        focusTask = null
        // Drop any unconsumed TOC jump target. VM instances are cached across
        // visits and `pendingFocusID` is a StateFlow, so a new collector
        // receives the current value immediately: a target still set when the
        // view exits (mid-scroll, or between the focus landing and the
        // consumer's clearPendingFocus) would replay the jump on re-entry.
        // The Apple original doesn't need this — its views consume via
        // `.onChange`, which fires on transitions only, never on subscription.
        _pendingFocusID.value = null
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
            if (queuedReleaseAnswer(kind.eventID) != null) continue
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
    /// our own cross-device answer in the timeline) — or, for a busy-queue
    /// card, released by the bridge (see [queuedReleaseAnswer]).
    fun isPromptAnswered(eventID: String): Boolean {
        if (answeredPromptIDs.contains(eventID)) return true
        for (item in _items.value) {
            if (!item.isOwn) continue
            val kind = item.kind
            if (kind is TimelineItem.Kind.AskUserAnswer && kind.promptEventID == eventID) return true
            if (item.inReplyToEventID == eventID) return true
        }
        return queuedReleaseAnswer(eventID) != null
    }

    /// The bridge's durable resolution for a busy-queue card (keyed by the
    /// card's event id), or `null` while the card is still live. The mapper
    /// hides each `queued_release` prompt_reply as an answer row keyed
    /// `"qr:<prompt_id>"`; matching is by the card's own
    /// `queuedReleasePromptID`, NOT by seq — a "Send all now" tap on one card
    /// flushes the whole queue and the bridge emits one release per sent
    /// card, which is how the sibling cards' dead buttons retire.
    /// Deliberately not `isOwn`-gated: releases are bridge-authored facts
    /// about the queue (sent / cancelled / expired), not another user's
    /// answer, and the card must resolve for everyone.
    ///
    /// Earliest release wins (unlike [spawnOutcomes], where later rows win):
    /// the realistic double is a committed `send` followed by boot
    /// reconcile's terminal `expired`, and the card should keep reporting the
    /// send that actually happened rather than downgrade to the generic
    /// resolved state.
    ///
    /// Never folded into [answeredPromptIDs] — safe because a release always
    /// has a higher seq than its card and the snapshot is the full local
    /// history (the render window is display-only), so any store that holds
    /// the card holds its release. If local event trimming is ever added,
    /// this is the invariant that breaks first (port of apple #162).
    private fun queuedReleaseAnswer(eventID: String): List<String>? = _releaseResolvedAnswers.value[eventID]

    /// Backing memo for [queuedReleaseAnswer], card event id → release
    /// values. Rebuilt in [applyDerivedRecompute]'s single pass and assigned
    /// only on change (same idiom as [spawnOutcomes]) — the lookups run from
    /// composables per ask row per snapshot, and a full-history scan there is
    /// exactly the per-row cost the timeline's CPU history warns about.
    private val _releaseResolvedAnswers = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /// Observed by the chat screen so a release landing recomposes the ask
    /// rows: the hidden `qr:` row changes no visible row, so nothing else
    /// would (Bugbot, #48). Same role as [spawnOutcomes] for spawn cards.
    val releaseResolvedAnswers: StateFlow<Map<String, List<String>>> = _releaseResolvedAnswers.asStateFlow()

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
        // Release-resolved queue card: name the action via the card's own
        // option labels ("⚡ Send all now"). An `expired` release matches no
        // option and shows the generic resolved state — "You chose: expired"
        // would be a lie, nobody chose anything.
        queuedReleaseAnswer(promptEventID)?.let { values ->
            if (values != listOf(EXPIRED_RELEASE_ACTION)) return mapValuesToLabels(values, promptEventID)
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

        /// Most matches the in-conversation search walks (apple #172).
        const val CHAT_SEARCH_MATCH_LIMIT = 500

        /// Most backward pages a single focus(seq) will fetch before landing
        /// on the oldest available row (apple #172).
        const val MAX_FOCUS_PAGINATES = 20

        /// Prefix of the hidden answer-row key the mapper files a bridge
        /// `queued_release` reply under (see [JournalTimelineMapper.queuedReleaseAnswerKey]).
        private val QUEUED_RELEASE_KEY_PREFIX = JournalTimelineMapper.queuedReleaseAnswerKey("")

        /// Boot reconcile's terminal release action for an orphaned queue
        /// card — a resolution, but not a choice anyone made.
        private const val EXPIRED_RELEASE_ACTION = "expired"

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

        internal fun describeAgentSpawnError(error: Throwable): String = when (error) {
            is JournalApiError.Transport ->
                "Couldn't reach the server — check your connection and try again."
            is JournalApiError.NotFound -> "That request is no longer on the server."
            else -> "The server refused that answer."
        }

        private const val DEFAULT_WINDOW_SIZE = 120
        private const val WINDOW_GROWTH_STEP = 120

        /// Most rows the window ever renders; beyond this it slides on an
        /// anchor instead of growing (apple #166).
        const val MAX_WINDOW_SIZE = 360
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
