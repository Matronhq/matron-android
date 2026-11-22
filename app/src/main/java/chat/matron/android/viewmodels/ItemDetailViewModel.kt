package chat.matron.android.viewmodels

import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.ItemSpawnConsent
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.AgentSpawnDecision
import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.SessionState
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.AttachmentLimits
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemWorking
import chat.matron.android.models.ItemWorkingIndicator
import chat.matron.android.models.QueuedReplyState
import chat.matron.android.models.StagedAttachment
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/// Backs the item detail screen: one item, its comments, and the local
/// outbox of comments still in flight. Writes go through [ItemsSyncing],
/// which owns the outbox and refetch coalescing — this view model calls
/// `refreshItem` after every mutating action without worrying about
/// de-duping concurrent calls. Ported from matron-apple's `ItemDetailViewModel`.
///
/// Picked photos/files are staged into a tray ([stagedAttachments], copies
/// under [stagingDirectory] — the chat composer's [StagedAttachment]) and
/// leave with the draft as one reply, never on their own at pick time. The
/// tray has no cap: a reply with more attachments than one comment may carry
/// is queued as several comments by [ItemsSyncing.enqueueComment].
class ItemDetailViewModel(
    val itemID: String,
    private val store: ItemsStoreReading,
    private val api: ItemsProviding,
    private val sync: ItemsSyncing,
    private val scope: CoroutineScope,
    private val stagingDirectory: File,
    private val now: () -> Instant = Instant::now,
    /// Answers a spawn consent item's card (`POST /agent-spawn/answer`, as the
    /// chat card does). `null` draws the card read-only: buttons wired to
    /// nothing would look like they acted.
    private val agentSpawn: AgentSpawnAnswering? = null,
    /// Where the unsent reply (draft and tray) is kept on disk, so it
    /// survives leaving the item, eviction and a restart. `null` keeps it in
    /// memory only.
    private val unsentReplies: UnsentReplyStore? = null,
    /// Answers a reply's queue card (Send now, Cancel, Edit). `null` draws no
    /// buttons on a queued reply: one wired to nothing would look like it acted.
    private val queuedRelease: QueuedReplySending? = null,
    /// How long a Send now or Cancel waits for the bridge's release before
    /// the reply goes back to tappable. A tap the bridge refuses (a card from
    /// before its restart, say) gets no release, and "Sending now…" must not
    /// spin forever.
    private val sendNowConfirmTimeoutMillis: Long = 20_000,
) {
    private val unsentPlace = UnsentReplyStore.Place.Item(itemID)
    private val unsentEpoch = unsentReplies?.epoch ?: 0
    private val _item = MutableStateFlow<TrackerItem?>(null)
    val item: StateFlow<TrackerItem?> = _item.asStateFlow()

    private val _comments = MutableStateFlow<List<TrackerComment>>(emptyList())
    val comments: StateFlow<List<TrackerComment>> = _comments.asStateFlow()

    private val _openableCommentConvoIDs = MutableStateFlow<Set<String>>(emptySet())
    val openableCommentConvoIDs: StateFlow<Set<String>> = _openableCommentConvoIDs.asStateFlow()

    private val _pendingComments = MutableStateFlow<List<ItemOutboxEntity>>(emptyList())
    val pendingComments: StateFlow<List<ItemOutboxEntity>> = _pendingComments.asStateFlow()

    /// The reply being composed. The field writes it on every keystroke; it is
    /// also a flow ([draftFlow]) because a send can fail after the screen has
    /// gone and come back, and the field must then pick the restored text up.
    private val _draft = MutableStateFlow("")
    val draftFlow: StateFlow<String> = _draft.asStateFlow()
    var draft: String
        get() = _draft.value
        set(value) { _draft.value = value; persistUnsent() }

    /// The tray: attachments picked but not yet sent, in send order.
    private val _stagedAttachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val stagedAttachments: StateFlow<List<StagedAttachment>> = _stagedAttachments.asStateFlow()

    /// Writes to the tray go through here so each change is saved.
    private var tray: List<StagedAttachment>
        get() = _stagedAttachments.value
        set(value) { _stagedAttachments.value = value; persistUnsent() }

    init {
        unsentReplies?.reply(unsentPlace)?.let { saved ->
            _draft.value = saved.text
            _stagedAttachments.value = saved.attachments
        }
        if (unsentReplies?.strandedSends(unsentPlace)?.isNotEmpty() == true) {
            scope.launch { recoverStrandedSends() }
        }
    }

    /// Replies an earlier run was sending when the app stopped. One that
    /// never reached the outbox goes back in the box. One that had reached
    /// the outbox insert is put back only if it is neither in the outbox nor
    /// already in the thread, so a queued reply is never offered twice. If
    /// the outbox can't be read, it stays recorded for the next visit.
    private suspend fun recoverStrandedSends() {
        val stores = unsentReplies ?: return
        for ((id, send) in stores.strandedSends(unsentPlace)) {
            val putBack = if (!send.queuing) {
                true
            } else {
                val queued = runCatching {
                    store.itemOutboxRows(itemID).any { it.localID == id || it.localID.startsWith("$id-") }
                }.getOrNull() ?: continue
                val delivered = queued || (runCatching { store.comments(itemID) }.getOrNull() ?: continue)
                    .any { isDelivery(it, send) }
                !delivered
            }
            if (putBack) {
                val merged = UnsentReplyStore.merge(send.reply, UnsentReply(_draft.value, _stagedAttachments.value))
                _draft.value = merged.text
                tray = merged.attachments
            } else {
                // Queued: the outbox holds what it needs.
                send.reply.attachments.forEach { it.deleteStagedCopy() }
            }
            stores.dropStranded(unsentPlace, id, unsentEpoch)
        }
    }

    /// Whether [comment] is [send] already delivered (it left the outbox): the
    /// user's own plain reply (not a contributor's or a hand-over copy, not a
    /// button tap), posted since the send began (a minute's grace for clock
    /// skew), with the same text and, when the send carried files, files
    /// drawn from them by name and size — a split reply's first part carries
    /// some of them, and a file is counted once.
    private fun isDelivery(comment: TrackerComment, send: StrandedSend): Boolean {
        if (comment.author != ItemAuthor.USER || comment.kind != TrackerComment.Kind.COMMENT) return false
        if (comment.action != null || comment.contributor != null || comment.attribution != null) return false
        if (comment.createdAt.toEpochMilli() < send.startedAt - 60_000) return false
        if (comment.body.trim() != send.reply.text.trim()) return false
        if (send.files.isEmpty()) return comment.attachments.isEmpty()
        if (comment.attachments.isEmpty()) return false
        val left = send.files.toMutableList()
        return comment.attachments.all { left.remove(it.name to it.size) }
    }

    private fun persistUnsent() {
        unsentReplies?.save(unsentPlace, UnsentReply(_draft.value, _stagedAttachments.value), unsentEpoch)
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /// The size of the thread once the opening `refreshItem` has completed —
    /// `null` until then. The detail view uses it to tell the opening load
    /// apart from a new reply: growth whose starting count is below this
    /// number is the load (or a stale replay of it) and must not drag an
    /// unread thread to its end. Set on completion whether or not the refetch
    /// succeeded: a failed refetch leaves the cached thread as the thread.
    private val _loadedCommentCount = MutableStateFlow<Int?>(null)
    val loadedCommentCount: StateFlow<Int?> = _loadedCommentCount.asStateFlow()

    /// The indicator to draw at the foot of the thread while the agent that
    /// owns the item is busy, from the item's `working` state: `null` when
    /// there is nothing to show.
    private val _workingIndicator = MutableStateFlow<ItemWorkingIndicator?>(null)
    val workingIndicator: StateFlow<ItemWorkingIndicator?> = _workingIndicator.asStateFlow()

    /// The owner conversation's session state on this device: `null` until
    /// its flow has delivered, and while this device has no row for it.
    private var ownerSessionState: String? = null
    private var ownerConvoID: String? = null
    private var ownerSessionJob: Job? = null
    private var workingLapseJob: Job? = null
    private var workingLapseAt: Instant? = null

    private val jobs = mutableListOf<Job>()
    private var commentsJob: Job? = null
    private var refreshJob: Job? = null

    fun start() {
        stop()
        val id = itemID
        jobs += scope.launch {
            store.itemFlow(id).collect {
                _item.value = it
                subscribeOwnerSession(it?.originConvoID)
                subscribeConsentRows()
                refreshSpawnConsent()
                subscribeQueuedRows(it?.originConvoID)
                scheduleWorkingLapse(it?.working?.until)
                updateWorkingIndicator()
            }
        }
        subscribeComments()
        jobs += scope.launch {
            _comments.map { comments -> comments.mapNotNull { it.authorConversation?.id }.toSet() }
                .distinctUntilChanged()
                .collectLatest { ids ->
                    // A changed thread must not keep links from an earlier
                    // subscription while its local rows are being read.
                    _openableCommentConvoIDs.value = emptySet()
                    store.knownConversationIDsFlow(ids).collect { _openableCommentConvoIDs.value = it }
                }
        }
        jobs += scope.launch { store.itemOutboxFlow(id).collect { _pendingComments.value = it } }
        // Comments only reach the local cache through a refetch — opening the
        // detail screen must trigger one, not just rely on whatever the panel
        // last fetched.
        _loadedCommentCount.value = null
        refreshJob = scope.launch {
            sync.refreshItem(id)
            // Drop the old subscription first: a pre-refetch snapshot it still
            // holds can no longer land after the loaded thread. The fresh
            // subscription's first value is the store as it is now.
            subscribeComments()
            runCatching { store.comments(id) }.getOrNull()?.let { _comments.value = it }
            _loadedCommentCount.value = _comments.value.size
            updateWorkingIndicator()
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        _openableCommentConvoIDs.value = emptySet()
        commentsJob?.cancel(); commentsJob = null
        refreshJob?.cancel(); refreshJob = null
        ownerSessionJob?.cancel(); ownerSessionJob = null
        ownerConvoID = null; ownerSessionState = null
        workingLapseJob?.cancel(); workingLapseJob = null; workingLapseAt = null
        consentJob?.cancel(); consentJob = null; consentConvoID = null
        queuedJob?.cancel(); queuedJob = null; queuedConvoID = null
    }

    private fun updateWorkingIndicator() {
        // Another person's item has no owner conversation here, and never
        // says whether its agent is busy.
        val working = _item.value?.takeIf { it.originConvoID.isNotEmpty() }?.working?.takeIf { it.isCurrent(now()) }
        val known = ownerSessionState
        // The stored copy is as old as its last fetch. A device that has the
        // owner conversation sets it against what it knows itself: once it
        // has seen the turn end, the copy is stale, and the refetch that
        // event started will say so. A device without the conversation waits
        // for the opening read before it claims any work at all.
        val midTurn = if (known != null) known == SessionState.RUNNING else _loadedCommentCount.value != null
        _workingIndicator.value = when (working?.state) {
            null -> null
            // Mid-turn on something else: as fresh as a running copy, so held
            // to the same check. It has no `until` to lapse at.
            ItemWorking.State.QUEUED ->
                if (working.behindTurn) ItemWorkingIndicator.BUSY_ELSEWHERE.takeIf { midTurn } else ItemWorkingIndicator.STARTING
            // The bridge's own word on its queue: a reply of the user's on
            // this item still waits on it, so the turn running is not about it.
            ItemWorking.State.RUNNING -> when {
                !midTurn -> null
                _queuedReplies.value.values.any { it.isWaiting } -> ItemWorkingIndicator.BUSY_ELSEWHERE
                else -> ItemWorkingIndicator.WORKING
            }
        }
    }

    /// Follows the owner conversation's session state. A turn starting or
    /// ending changes `working` without touching the item, so each change
    /// reads the item again. A granted item's owner is "": nothing to follow.
    private fun subscribeOwnerSession(convoID: String?) {
        if (convoID == ownerConvoID) return
        ownerConvoID = convoID
        ownerSessionJob?.cancel(); ownerSessionJob = null
        ownerSessionState = null
        if (convoID.isNullOrEmpty()) return
        val id = itemID
        ownerSessionJob = scope.launch {
            var first = true
            store.itemOwnerSessionStateFlow(convoID).collect { state ->
                val changed = state != ownerSessionState
                ownerSessionState = state
                updateWorkingIndicator()
                // The first value is the state the opening refetch already
                // read the item under. Not awaited here: the next state must
                // not wait behind this one's read.
                if (!first && changed) {
                    jobs.removeAll { it.isCompleted }
                    jobs += scope.launch { sync.refreshItem(id) }
                }
                first = false
            }
        }
    }

    /// A queued state stops showing at its `until` without the item being
    /// read again: wake then, once per deadline.
    private fun scheduleWorkingLapse(until: Instant?) {
        if (until == workingLapseAt) return
        workingLapseAt = until
        workingLapseJob?.cancel(); workingLapseJob = null
        val wait = until?.let { Duration.between(now(), it).toMillis() } ?: return
        if (wait <= 0) return
        workingLapseJob = scope.launch {
            delay(wait + 50)
            updateWorkingIndicator()
        }
    }

    private fun subscribeComments() {
        commentsJob?.cancel()
        val id = itemID
        commentsJob = scope.launch { store.commentsFlow(id).collect { _comments.value = it } }
    }

    /// Whether a closed item offers Reopen. Not on a consent ask's mirror:
    /// the journal answers that reopen with 409, because the ask itself is
    /// settled.
    val canReopen: Boolean
        get() = _item.value?.isConsentAsk != true

    /// The resolutions the person can close this item with, primary first.
    /// Only outcomes they can honestly claim: a question is answered by
    /// *replying* (the journal hands it back to the agent, which closes it as
    /// answered once it has acted), so "Answered" is offered only once they
    /// have actually replied — before that the only honest close is to
    /// dismiss it. An open decision is already in force, so reversing it
    /// leads. A reply still in the outbox counts: it is the user's, and it
    /// will land — unless the journal refused it.
    val availableResolutions: List<ItemResolution>
        get() = resolutions(
            _item.value?.kind,
            userHasReplied = _pendingComments.value.any { !it.isRejected } ||
                _comments.value.any { it.author == ItemAuthor.USER && it.kind == TrackerComment.Kind.COMMENT },
        )

    /// Uploads [attachments] in order, each streamed from its staged copy on
    /// disk, so no attachment is ever held in memory whole. `null` (with
    /// [error] set) when any of them fails.
    @JvmName("uploadStaged")
    private suspend fun upload(attachments: List<StagedAttachment>): List<TrackerAttachment>? {
        val uploaded = mutableListOf<TrackerAttachment>()
        for (a in attachments) {
            uploaded += upload(a.file, a.filename, a.mimeType) ?: return null
        }
        return uploaded
    }

    private suspend fun upload(file: File, name: String, mime: String): TrackerAttachment? = try {
        val size = withContext(Dispatchers.IO) { file.length() }
        val ref = api.uploadMedia(file, mime)
        TrackerAttachment(blobRef = ref, mime = mime, name = name, size = size)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        _error.value = "Couldn't upload an attachment: ${error.message ?: error}"
        null
    }

    /// Runs a send on the view model's own (session-lifetime) [scope] and
    /// waits for it. The caller is the screen, whose composition scope dies
    /// the moment the user navigates away — and the draft and tray have
    /// already cleared by then — so a send tied to it would be cancelled
    /// mid-upload and the reply lost (Bugbot). Cancelling the caller only
    /// stops the wait; the send carries on until the comment is in the
    /// outbox. Started undispatched so the draft/tray clear in the same tick
    /// as the tap, exactly as before.
    private suspend fun runSend(send: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { send() }.join()
    }

    /// Sends the draft and the tray as ONE comment; see [sendComposer].
    /// No-op with neither.
    suspend fun submitComment() {
        if (draft.trim().isEmpty() && _stagedAttachments.value.isEmpty()) return
        runSend { sendComposer(leading = null) }
    }

    /// Sends the draft (as the body) and the tray, with [leading] (a voice
    /// note) as the first attachment when given — the chat composer's
    /// `sendComposer` (matron-android#88).
    ///
    /// Always runs via [runSend], so leaving the screen mid-send doesn't
    /// cancel it. The draft and tray clear as the send starts, so the field is free for
    /// the next reply. Attachments upload first, then the comment is enqueued
    /// (the local id is minted here, not by [ItemsSyncing] — the outbox row
    /// needs it before the enqueue call returns so the pending-comments flow
    /// can show it); once enqueued the outbox holds it durably and retries on
    /// its own, so the staged copies go. If the send fails, or is cancelled
    /// (only the session ending does that), before that, every attachment — the voice note included — goes back in
    /// the tray ahead of anything picked meanwhile, and the text comes back
    /// unless the user has typed something new.
    private suspend fun sendComposer(leading: StagedAttachment?) {
        // An edited queued reply replaces the held original, so the original
        // is withdrawn first; if that fails the edit stays in the reply box
        // (and a voice note goes back in the tray) to try again.
        // The edit's text isn't in the box until its hold has landed: nothing
        // goes yet, and a voice note goes back in the tray rather than being lost.
        if (_editHoldPending.value) {
            if (leading != null) tray = listOf(leading) + _stagedAttachments.value
            _error.value = "That reply is still being held for editing. Send again in a moment."
            return
        }
        if (_editingQueuedReplyID.value != null) {
            val failure = withdrawEditedOriginal()
            if (failure != null) {
                if (leading != null) tray = listOf(leading) + _stagedAttachments.value
                _error.value = failure
                return
            }
        }
        val pending = draft
        val attachments = listOfNotNull(leading) + _stagedAttachments.value
        val replaces = _replacingCommentID.value
        _replacingCommentID.value = null
        draft = ""
        tray = emptyList()
        // Recorded under the outbox local id until it is queued or back in
        // the tray, so a reply still uploading when the app is killed comes
        // back on the next visit ([recoverStrandedSends]).
        val sendID = UUID.randomUUID().toString()
        unsentReplies?.beginSend(unsentPlace, sendID, UnsentReply(pending, attachments), unsentEpoch)
        _isBusy.value = true
        var sent = false
        try {
            val uploaded = upload(attachments) ?: return
            unsentReplies?.markQueuing(unsentPlace, sendID, unsentEpoch)
            sync.enqueueComment(itemID, sendID, pending.trim(), uploaded, replaces = replaces)
            sent = true
            attachments.forEach { it.deleteStagedCopy() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        } finally {
            if (!sent) {
                tray = attachments + _stagedAttachments.value
                if (draft.isEmpty()) draft = pending
                // Still an edit only when its own text came back to the box.
                if (replaces != null && _replacingCommentID.value == null && draft == pending) _replacingCommentID.value = replaces
            }
            unsentReplies?.endSend(unsentPlace, sendID, unsentEpoch)
            _isBusy.value = false
        }
    }

    /// Stages each picked file into the tray rather than posting it. An
    /// unreadable file is reported via [error] and stages nothing.
    /// A file over [AttachmentLimits.MAX_BYTES] is refused with
    /// [AttachmentLimits.oversizeMessage] before anything is copied.
    suspend fun attachFiles(files: List<File>) {
        for (file in files) {
            if (AttachmentLimits.isOversize(withContext(Dispatchers.IO) { file.length() })) {
                _error.value = AttachmentLimits.oversizeMessage(file.name)
                continue
            }
            try {
                val staged = withContext(Dispatchers.IO) { StagedAttachment.stage(file, stagingDirectory) }
                tray = _stagedAttachments.value + staged
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = "Couldn't attach that file: ${error.message ?: error}"
            }
        }
    }

    /// The Retry under a reply the journal refused: it is sent again now
    /// (re-split first if it carries more attachments than one comment may).
    suspend fun retryComment(localID: String) = runOutboxAction { sync.retryComment(localID) }

    /// The Discard under a reply the journal refused: it leaves the thread.
    suspend fun discardComment(localID: String) = runOutboxAction { sync.discardComment(localID) }

    private suspend fun runOutboxAction(action: suspend () -> Unit) {
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        }
    }

    /// Drops one attachment from the tray (its ✕) and deletes its copy.
    fun removeAttachment(id: String) {
        val attachment = _stagedAttachments.value.firstOrNull { it.id == id } ?: return
        tray = _stagedAttachments.value.filterNot { it.id == id }
        attachment.deleteStagedCopy()
    }

    /// Answers a follow-up question with one of the buttons its comment
    /// offers: a comment whose body is the label, addressed to that comment.
    /// Queued through the outbox like any reply, so a tap made offline lands
    /// when the connection is back. Ignored while the item is closed (the
    /// buttons are a record there), for a label the comment does not offer,
    /// and when that label is already the answer — on its way in the outbox,
    /// or landed.
    ///
    /// Taps run one at a time and each reads the outbox and the thread from
    /// the store, not from this view model's flows: those only catch up
    /// after the write, so a quick second tap on the same button would pass
    /// a check made against them and queue the answer twice.
    suspend fun tapAction(comment: TrackerComment, label: String) {
        tapMutex.withLock {
            if (_item.value?.state != ItemState.OPEN) return
            if (label !in comment.actions) return
            val queued = runCatching { store.itemOutboxRows(itemID) }.getOrElse { _pendingComments.value }
            val pending = pendingTaps(queued)[comment.id]
            if (pending == label) return
            if (pending == null) {
                val stored = runCatching { store.comments(itemID) }.getOrNull()?.firstOrNull { it.id == comment.id } ?: comment
                if (stored.chosenAction == label) return
            }
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), label, emptyList(), action = label, replyTo = comment.id)
        }
    }
    private val tapMutex = Mutex()

    /// A tap on one of the ITEM's own buttons — a notice's Seen, an agent's
    /// Go: a comment whose body is the label, with no `reply_to`. The same
    /// outbox path and the same guards as [tapAction]. A notice's Seen is
    /// closed by the journal in the same write, and the closed item the
    /// drain lands takes it out of For you.
    suspend fun tapItemAction(label: String) {
        tapMutex.withLock {
            val current = _item.value ?: return
            if (current.state != ItemState.OPEN) return
            if (label !in current.actions) return
            val queued = runCatching { store.itemOutboxRows(itemID) }.getOrElse { _pendingComments.value }
            val pending = pendingItemTap(queued)
            if (pending == label) return
            if (pending == null && current.chosenAction == label) return
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), label, emptyList(), action = label, replyTo = null)
        }
    }

    /// Sends a recorded voice note together with whatever the composer
    /// holds, as ONE comment: the draft as its body, the recording
    /// (`voice-note.m4a`, `audio/mp4`) as the first attachment, then the
    /// tray. With an empty draft and tray it is a voice-only comment.
    ///
    /// The recording is staged like any attachment, so a failed send puts it
    /// back in the tray for the user to send again; only once it is staged is
    /// the temp file deleted. An empty/unreadable recording is reported and
    /// cleaned up — there's nothing worth retrying. If staging itself fails
    /// (a full disk), the recording goes out on its own straight from the
    /// temp file, leaving the draft and tray alone.
    suspend fun sendVoiceNote(file: File) = runSend { sendVoiceNoteNow(file) }

    private suspend fun sendVoiceNoteNow(file: File) {
        val size = runCatching { file.length() }.getOrDefault(0L)
        if (!file.isFile || size == 0L) {
            _error.value = "Voice note was empty."
            file.delete()
            return
        }
        val voiceNote = try {
            withContext(Dispatchers.IO) {
                StagedAttachment.stage(file, stagingDirectory, filename = VOICE_NOTE_NAME, mimeType = VOICE_NOTE_MIME)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            sendUnstagedVoiceNote(file)
            return
        }
        runCatching { file.delete() }
        sendComposer(leading = voiceNote)
    }

    /// The pre-staging voice-only send. On failure the file is deliberately
    /// left in place — deleting a recording nobody could recover is permanent
    /// data loss.
    private suspend fun sendUnstagedVoiceNote(file: File) {
        _isBusy.value = true
        try {
            if (!withContext(Dispatchers.IO) { file.canRead() }) {
                _error.value = "Couldn't read the voice note."
                return
            }
            val uploaded = upload(file, VOICE_NOTE_NAME, VOICE_NOTE_MIME) ?: return
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), "", listOf(uploaded))
            file.delete()
        } finally {
            _isBusy.value = false
        }
    }

    // MARK: Spawn consent

    /// The spawn consent ask this item mirrors, `null` unless the item carries
    /// a `matron://consent/spawn/<id>` link. Re-derived whenever the item or
    /// the origin conversation's consent rows change: the card itself may sync
    /// after the item was opened, and the journal closes the item when the
    /// ask is answered.
    private val _spawnConsent = MutableStateFlow<ItemSpawnConsent?>(null)
    val spawnConsent: StateFlow<ItemSpawnConsent?> = _spawnConsent.asStateFlow()

    private var consentRows: List<JournalEvent> = emptyList()
    private var consentConvoID: String? = null
    private var consentJob: Job? = null

    /// This device's answer in flight, failed, or settled before the journal's
    /// outcome row lands. In memory only: an interrupted send must come back
    /// answerable, and the real resolution comes from the store.
    private var spawnTransient: AgentSpawnCardState? = null

    /// Follows the origin conversation's consent rows for as long as the item
    /// is a spawn consent ask. Keyed on the conversation: an item update that
    /// leaves the origin alone keeps the subscription.
    private fun subscribeConsentRows() {
        val current = _item.value
        val convoID = if (current?.spawnConsentRequestID == null) null else current.originConvoID.takeIf { it.isNotEmpty() }
        if (convoID == consentConvoID && (convoID == null || consentJob != null)) return
        consentJob?.cancel(); consentJob = null
        consentConvoID = convoID
        consentRows = emptyList()
        if (convoID == null) return
        consentJob = scope.launch {
            store.consentEventsFlow(convoID).collect { rows ->
                consentRows = rows
                refreshSpawnConsent()
            }
        }
    }

    // MARK: Queued replies

    /// The user's replies the agent hasn't got yet, by comment id: parked on
    /// the session's busy queue, or cancelled / never delivered. A reply that
    /// isn't here reached the agent.
    private val _queuedReplies = MutableStateFlow<Map<String, QueuedReplyState>>(emptyMap())
    val queuedReplies: StateFlow<Map<String, QueuedReplyState>> = _queuedReplies.asStateFlow()

    /// Queued replies whose card can be edited from here
    /// ([ItemQueuedReplies.editable]).
    private val _editableQueuedReplies = MutableStateFlow<Set<String>>(emptySet())
    val editableQueuedReplies: StateFlow<Set<String>> = _editableQueuedReplies.asStateFlow()

    /// The queued reply being edited in the reply box, held on the agent's
    /// queue until the edited reply is sent or the edit is cancelled.
    private val _editingQueuedReplyID = MutableStateFlow<String?>(null)
    val editingQueuedReplyID: StateFlow<String?> = _editingQueuedReplyID.asStateFlow()

    /// Edit's hold is on its way to the bridge: the reply shows as being
    /// edited, but nothing else may answer its card until the hold lands
    /// (a Cancel edit sent first could be overtaken by the hold).
    private val _editHoldPending = MutableStateFlow(false)
    val editHoldPending: StateFlow<Boolean> = _editHoldPending.asStateFlow()

    /// The cancelled reply the text in the reply box is an edit of: the next
    /// reply sent stands in for it (`replaces`), so the thread shows one
    /// reply, not the cancelled copy and its edit.
    private val _replacingCommentID = MutableStateFlow<String?>(null)
    val replacingCommentID: StateFlow<String?> = _replacingCommentID.asStateFlow()

    /// Whether the queue state, and the buttons that answer it, apply here.
    val answersQueuedReplies: Boolean get() = queuedRelease != null

    private var queuedRows: List<JournalEvent> = emptyList()
    private var queuedConvoID: String? = null
    private var queuedJob: Job? = null

    /// This device's Send now and Cancel taps in flight ([QueuedReplyState.Sending],
    /// [QueuedReplyState.Cancelling]) or refused ([QueuedReplyState.SendFailed]),
    /// by comment id. In memory only: a tap that never reached the bridge must
    /// come back tappable, and the real outcome is the bridge's release row.
    private val queuedTransient = HashMap<String, QueuedReplyState>()

    /// The current Send now or Cancel attempt per comment: a timeout only
    /// fails the attempt that started it, never a later tap on the same reply.
    private val queuedAttempts = HashMap<String, Any>()

    /// What the reply box held before Edit put the reply's text in it.
    private var preEditDraft = ""

    /// Whether Edit has put the reply's text in the box yet: not while its
    /// hold is still on its way.
    private var editTextInBox = false

    /// Follows the owner conversation's queue cards: the conversation the
    /// item's turns are delivered in.
    private fun subscribeQueuedRows(convoID: String?) {
        if (convoID == queuedConvoID && queuedJob != null) return
        queuedConvoID = convoID
        queuedJob?.cancel(); queuedJob = null
        if (convoID.isNullOrEmpty() || queuedRelease == null) {
            queuedRows = emptyList()
            refreshQueuedReplies()
            return
        }
        queuedJob = scope.launch {
            store.queuedReleaseEventsFlow(convoID).collect { rows ->
                queuedRows = rows
                refreshQueuedReplies()
            }
        }
    }

    /// Rebuilds [spawnConsent] from the item and the consent rows. The card's
    /// facts come from the ask's own `permission_request` payload; the
    /// resolved state from its `spawn_outcome`, the last one winning.
    private fun refreshSpawnConsent() {
        val current = _item.value
        val requestID = current?.spawnConsentRequestID
        if (requestID == null) {
            _spawnConsent.value = null
            return
        }
        var request: AgentSpawnRequest? = null
        var outcome: SpawnOutcome? = null
        for (row in consentRows) {
            when (row.type) {
                JournalEventType.PERMISSION_REQUEST -> if (request == null) {
                    AgentSpawnRequest.parse(row.payload)?.takeIf { it.requestId == requestID }?.let { request = it }
                }
                JournalEventType.SPAWN_OUTCOME ->
                    SpawnOutcome.parse(row.payload)?.takeIf { it.requestId == requestID }?.let { outcome = it }
            }
        }
        _spawnConsent.value = ItemSpawnConsent(
            requestID, request,
            spawnState(outcome, itemIsOpen = current.state == ItemState.OPEN, transient = spawnTransient, canAnswer = agentSpawn != null),
        )
    }

    /// Answers the spawn ask, exactly as the chat card answers it. Only against
    /// the card's own payload: no card, no answer. An accepted answer settles
    /// the card with what the user just did until the journal's outcome row
    /// lands; a 409 (answered elsewhere, or expired) settles it as no longer
    /// waiting and refetches the item; any other error leaves it answerable.
    /// Runs on the view model's scope, so leaving the page mid-answer doesn't
    /// strand the card on "sending".
    suspend fun answerSpawn(approve: Boolean) = runSend {
        val answerer = agentSpawn ?: return@runSend
        val consent = _spawnConsent.value ?: return@runSend
        val request = consent.request ?: return@runSend
        when (consent.state) {
            AgentSpawnCardState.Idle, is AgentSpawnCardState.Failed -> Unit
            else -> return@runSend
        }
        spawnTransient = AgentSpawnCardState.Sending
        refreshSpawnConsent()
        try {
            answerer.answerAgentSpawn(request.requestId, if (approve) AgentSpawnDecision.APPROVE else AgentSpawnDecision.DENY)
            spawnTransient = if (approve) {
                // Approved: the session starts once its box is up, and the
                // journal's outcome row says when. Drawn as the card's
                // "waiting for the session to start" until then.
                AgentSpawnCardState.AnsweredByCoordinator(approved = true)
            } else {
                // A decline is final at the answer; the journal's own
                // `declined` outcome follows.
                AgentSpawnCardState.Resolved(SpawnOutcome(request.requestId, "declined"))
            }
            refreshSpawnConsent()
            sync.refreshItem(itemID)
        } catch (cancel: CancellationException) {
            spawnTransient = null
            refreshSpawnConsent()
            throw cancel
        } catch (_: JournalApiError.Conflict) {
            spawnTransient = AgentSpawnCardState.Unavailable
            refreshSpawnConsent()
            sync.refreshItem(itemID)
        } catch (error: Throwable) {
            spawnTransient = AgentSpawnCardState.Failed(ChatViewModel.describeAgentSpawnError(error))
            refreshSpawnConsent()
        }
    }

    private fun refreshQueuedReplies() {
        _editableQueuedReplies.value = ItemQueuedReplies.editable(queuedRows, itemID)
        val derived = ItemQueuedReplies.derive(queuedRows, itemID)
        // A release (or the card vanishing) settles any tap made here.
        queuedTransient.keys.retainAll { derived[it] is QueuedReplyState.Queued }
        val next = derived + queuedTransient
        _queuedReplies.value = next
        updateWorkingIndicator()
        // The reply being edited left the queue some other way (sent from the
        // conversation, or its hold timed out and it went): the edit is now
        // just text in the box, and sending it would be a new reply.
        val editing = _editingQueuedReplyID.value ?: return
        when (next[editing]) {
            is QueuedReplyState.Queued, is QueuedReplyState.SendFailed -> Unit
            QueuedReplyState.Cancelled -> {
                // Cancelled from the conversation or another device: it was
                // withdrawn all the same, so the edit still takes its place.
                val original = _comments.value.firstOrNull { it.id == editing }?.body.orEmpty()
                if (!editTextInBox) restoreToDraft(original)
                if (editTextInBox || original.isNotBlank()) _replacingCommentID.value = editing
                endEdit()
                _error.value = "That reply was cancelled before the edit was sent. Your edit is still in the reply box."
            }
            else -> {
                endEdit()
                _error.value = "That reply left the queue before the edit was sent. Your edit is still in the reply box."
            }
        }
    }

    private fun endEdit() {
        _editingQueuedReplyID.value = null
        editTextInBox = false
    }

    /// Send now on a reply waiting on the busy queue: answers its card
    /// exactly as a tap on the card in the conversation would.
    suspend fun sendQueuedReplyNow(commentID: String) = runSend {
        answerQueuedCard(
            commentID, QueuedReplyState.Sending, { ItemQueuedReplies.sendNowChoice(it) },
            unconfirmed = "The session hasn't confirmed it. Try again, or check the conversation.",
        )
    }

    /// Cancel on a reply waiting on the busy queue: the card's own Cancel,
    /// which withdraws just this reply. The comment stays in the thread,
    /// marked cancelled once the bridge's release lands.
    suspend fun cancelQueuedReply(commentID: String) = runSend {
        answerQueuedCard(
            commentID, QueuedReplyState.Cancelling, { ItemQueuedReplies.CANCEL_CHOICE },
            unconfirmed = "The session hasn't confirmed the cancel. Try again, or check the conversation.",
        )
    }

    /// Answers a queued reply's card with [choice] (given whether the card can
    /// release one reply alone), showing [pending] until the bridge's release
    /// row settles it, or, after the confirm timeout, handing the reply back
    /// as still queued with [unconfirmed].
    private suspend fun answerQueuedCard(
        commentID: String,
        pending: QueuedReplyState,
        choice: (Boolean) -> String,
        unconfirmed: String,
    ) {
        val card = _queuedReplies.value[commentID]?.card ?: return
        val release = queuedRelease ?: return
        if (_editingQueuedReplyID.value == commentID) return
        val attempt = Any()
        queuedAttempts[commentID] = attempt
        queuedTransient[commentID] = pending
        refreshQueuedReplies()
        fun failed(reason: String) = QueuedReplyState.SendFailed(card.convoID, card.targetSeq, card.offersSendOne, reason)
        try {
            release.sendQueuedRelease(card.convoID, card.targetSeq, choice(card.offersSendOne))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            if (queuedAttempts[commentID] !== attempt) return
            queuedTransient[commentID] = failed("Couldn't reach the journal. Try again.")
            refreshQueuedReplies()
            return
        }
        delay(sendNowConfirmTimeoutMillis)
        // Still this attempt, and nothing settled it: a release or a later
        // tap leaves it alone.
        if (queuedAttempts[commentID] !== attempt || queuedTransient[commentID] != pending) return
        queuedTransient[commentID] = failed(unconfirmed)
        refreshQueuedReplies()
    }

    /// Edit on a reply waiting on the busy queue: hold it there, then put its
    /// text in the reply box (what was typed there comes back on Cancel
    /// edit). Sending the reply replaces the original.
    suspend fun editQueuedReply(commentID: String) = runSend {
        // One edit at a time, and not over a resend already in the box.
        if (_editingQueuedReplyID.value != null || _replacingCommentID.value != null) return@runSend
        if (commentID !in _editableQueuedReplies.value) return@runSend
        val comment = _comments.value.firstOrNull { it.id == commentID } ?: return@runSend
        val release = queuedRelease ?: return@runSend
        val card = _queuedReplies.value[commentID]?.card ?: return@runSend
        // Marked as being edited at the tap, so its Send now and Cancel go
        // away while the hold is on its way and can't send the original.
        _editingQueuedReplyID.value = commentID
        _editHoldPending.value = true
        // The composer waits for the hold too: its text is about to change.
        _isBusy.value = true
        editTextInBox = false
        preEditDraft = draft
        try {
            release.sendQueuedRelease(card.convoID, card.targetSeq, ItemQueuedReplies.EDIT_CHOICE)
        } catch (cancel: CancellationException) {
            if (_editingQueuedReplyID.value == commentID) endEdit()
            throw cancel
        } catch (_: Throwable) {
            if (_editingQueuedReplyID.value == commentID) endEdit()
            _error.value = "Couldn't hold that reply for editing. Try again."
            return@runSend
        } finally {
            _editHoldPending.value = false
            _isBusy.value = false
        }
        // Released (or the edit cancelled) while the hold was on its way.
        if (_editingQueuedReplyID.value != commentID) return@runSend
        if (_queuedReplies.value[commentID]?.canSendNow != true) {
            endEdit()
            return@runSend
        }
        draft = comment.body
        editTextInBox = true
    }

    /// Cancel edit: the held reply goes as it was, and the reply box gets
    /// back what was typed before Edit.
    suspend fun cancelQueuedReplyEdit() = runSend {
        val commentID = _editingQueuedReplyID.value ?: return@runSend
        // Not while the hold is still on its way: the release could reach the
        // bridge first and leave the reply held with nothing editing it.
        if (_editHoldPending.value) return@runSend
        draft = preEditDraft
        preEditDraft = ""
        endEdit()
        val release = queuedRelease ?: return@runSend
        val card = _queuedReplies.value[commentID]?.card ?: return@runSend
        try {
            release.sendQueuedRelease(card.convoID, card.targetSeq, ItemQueuedReplies.EDIT_CANCEL_CHOICE)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            _error.value = "Couldn't release that reply. It goes as it was within a few minutes."
        }
    }

    /// Sending an edited reply: cancel the held original first, and wait for
    /// the bridge's `cancel` release, which is what withdraws it, before the
    /// edit goes. Posting on the strength of the request alone could leave
    /// both replies to reach the agent if the cancel never lands. Returns a
    /// failure's message, leaving the edit in the reply box and the reply
    /// still being edited, so Send can be tried again.
    private suspend fun withdrawEditedOriginal(): String? {
        val commentID = _editingQueuedReplyID.value ?: return null
        val card = _queuedReplies.value[commentID]?.card
        if (card == null) {
            endEdit()
            return "That reply left the queue before the edit was sent. Your edit is still in the reply box."
        }
        val release = queuedRelease ?: return "Couldn't replace the queued reply."
        _isBusy.value = true
        try {
            try {
                release.sendQueuedRelease(card.convoID, card.targetSeq, ItemQueuedReplies.CANCEL_CHOICE)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                return "Couldn't replace the queued reply. Try again."
            }
            // Not "being edited" while the cancel settles: its release would
            // otherwise read as the reply leaving the queue mid-edit.
            _editingQueuedReplyID.value = null
            val attempt = Any()
            queuedAttempts[commentID] = attempt
            queuedTransient[commentID] = QueuedReplyState.Cancelling
            refreshQueuedReplies()
            val deadline = System.nanoTime() + sendNowConfirmTimeoutMillis * 1_000_000
            while (true) {
                when (ItemQueuedReplies.derive(queuedRows, itemID)[commentID]) {
                    QueuedReplyState.Cancelled -> {
                        // Withdrawn: the edit replaces it, in the thread too.
                        preEditDraft = ""; editTextInBox = false
                        _replacingCommentID.value = commentID
                        return null
                    }
                    QueuedReplyState.NotDelivered -> {
                        // Never going to be delivered, but not withdrawn: the
                        // edit goes as a reply of its own.
                        preEditDraft = ""; editTextInBox = false
                        return null
                    }
                    null -> {
                        editTextInBox = false
                        return "That reply reached the agent before the edit was sent. Your edit is still in the reply box."
                    }
                    else -> Unit
                }
                if (System.nanoTime() >= deadline || queuedAttempts[commentID] !== attempt) break
                delay(100)
            }
            // Unconfirmed: the original may still be queued (and held), so the
            // edit must not go yet.
            if (queuedTransient[commentID] == QueuedReplyState.Cancelling) {
                queuedTransient[commentID] = QueuedReplyState.SendFailed(
                    card.convoID, card.targetSeq, card.offersSendOne,
                    "The session hasn't confirmed the cancel. Try again, or check the conversation.",
                )
            }
            _editingQueuedReplyID.value = commentID
            refreshQueuedReplies()
            return "The session hasn't confirmed it replaced the queued reply. Your edit is still in the reply box; try again."
        } finally {
            _isBusy.value = false
        }
    }

    /// Edit and resend on a reply that never reached the agent (cancelled, or
    /// its session ended first): its text goes into the reply box to edit and
    /// send. A cancelled reply's resend takes its place in the thread; one
    /// whose session ended stays, marked as not sent.
    fun editAndResend(commentID: String) {
        // Not while a queued reply is being edited: the box holds that edit.
        if (_editingQueuedReplyID.value != null) return
        val state = _queuedReplies.value[commentID]
        if (state?.canResend != true) return
        val comment = _comments.value.firstOrNull { it.id == commentID } ?: return
        restoreToDraft(comment.body)
        // A cancelled reply was withdrawn, so its resend can stand in for it.
        // Only with its text in the box to send.
        if (state == QueuedReplyState.Cancelled && comment.body.isNotBlank()) _replacingCommentID.value = commentID
    }

    /// Puts a withdrawn reply's text into the reply box, after whatever is
    /// already typed there, never over it, and not again if the box already
    /// ends with it (a second tap).
    private fun restoreToDraft(text: String) {
        val trimmed = text.trim()
        val typed = draft.trim()
        if (trimmed.isEmpty() || typed.endsWith(trimmed)) return
        draft = if (typed.isEmpty()) trimmed else "$typed\n\n$trimmed"
    }

    suspend fun close(resolution: ItemResolution, comment: String? = null) =
        run { api.closeItem(itemID, resolution, comment) }

    suspend fun reopen() = run { api.reopenItem(itemID, null) }

    suspend fun reverse() = close(ItemResolution.REVERSED, null)

    fun dismissError() {
        _error.value = null
    }

    /// Surfaces a host-side failure (a recorder that won't start, an
    /// unreadable pick) through the same banner as the VM's own errors.
    fun reportError(message: String) {
        _error.value = message
    }

    /// Runs a mutating call and lands whatever item it returned before the
    /// refetch. The journal answers close/reopen with the updated item, and
    /// that answer is the only part of the round trip that can't fail
    /// silently: `refreshItem` swallows its own failures, so a close whose
    /// refetch then failed used to leave the thread looking open — and the
    /// retry that invited hit a conflict with nothing local to explain it.
    /// The refetch still runs afterwards; it is what brings the comment
    /// thread (including the close's own system comment) down.
    private suspend fun run(op: suspend () -> TrackerItem?) {
        _isBusy.value = true
        try {
            val updated = op()
            if (updated != null) {
                sync.applyItem(updated)
                // Mirrored into this screen's own state too, so the item
                // reads correctly even if the local write itself failed; the
                // store's flow is still the source of truth from here on.
                _item.value = updated
            }
            sync.refreshItem(itemID)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        } finally {
            _isBusy.value = false
        }
    }

    companion object {
        /// Where a spawn ask is, in order of authority: the journal's
        /// `spawn_outcome` row; then, on a closed item with no outcome yet,
        /// this device's own settled answer or else "no longer waiting" (the
        /// journal closes the item the moment the ask is answered, before the
        /// session has started); then the answer in flight; then answerable
        /// when an answerer is wired, read-only otherwise.
        fun spawnState(
            outcome: SpawnOutcome?,
            itemIsOpen: Boolean,
            transient: AgentSpawnCardState?,
            canAnswer: Boolean,
        ): AgentSpawnCardState {
            if (outcome != null) return AgentSpawnCardState.Resolved(outcome)
            if (!itemIsOpen) {
                return when (transient) {
                    is AgentSpawnCardState.Resolved, is AgentSpawnCardState.AnsweredByCoordinator -> transient
                    else -> AgentSpawnCardState.Unavailable
                }
            }
            if (transient != null) return transient
            return if (canAnswer) AgentSpawnCardState.Idle else AgentSpawnCardState.Unavailable
        }

        private const val VOICE_NOTE_NAME = "voice-note.m4a"
        private const val VOICE_NOTE_MIME = "audio/mp4"

        /// The taps still in the outbox, as asking-comment id → label (the
        /// latest wins, as it will on the journal). The thread marks these as
        /// chosen straight away instead of waiting for the round trip. A tap
        /// the journal refused is not an answer.
        fun pendingTaps(pending: List<ItemOutboxEntity>): Map<String, String> = buildMap {
            for (row in pending) {
                if (row.isRejected) continue
                val payload = runCatching { MatronJson.decodeFromString(ItemsSync.CommentPayload.serializer(), row.payloadJson) }.getOrNull() ?: continue
                val action = payload.action ?: continue
                val replyTo = payload.replyTo ?: continue
                put(replyTo, action)
            }
        }

        /// The cancelled replies an edit still in the outbox stands in for: the
        /// thread leaves them out already, as it will once the edit lands.
        fun pendingReplaces(pending: List<ItemOutboxEntity>): Set<String> = pending.filterNot { it.isRejected }.mapNotNullTo(HashSet()) { row ->
            runCatching { MatronJson.decodeFromString(ItemsSync.CommentPayload.serializer(), row.payloadJson) }.getOrNull()?.replaces
        }

        /// The latest tap on the item's own buttons still in the outbox (a
        /// row with an `action` and no `replyTo`), or `null`. A refused tap
        /// does not count.
        fun pendingItemTap(pending: List<ItemOutboxEntity>): String? = pending.filterNot { it.isRejected }.mapNotNull { row ->
            val payload = runCatching { MatronJson.decodeFromString(ItemsSync.CommentPayload.serializer(), row.payloadJson) }.getOrNull()
            payload?.action?.takeIf { payload.replyTo == null }
        }.lastOrNull()

        /// A notice offers no close of its own: its Seen button is the one
        /// way to finish it (the journal closes it as done on the tap).
        fun resolutions(kind: ItemKind?, userHasReplied: Boolean): List<ItemResolution> = when (kind) {
            ItemKind.TASK -> listOf(ItemResolution.DONE, ItemResolution.CANCELLED)
            ItemKind.QUESTION -> if (userHasReplied) listOf(ItemResolution.ANSWERED, ItemResolution.CANCELLED) else listOf(ItemResolution.CANCELLED)
            ItemKind.DECISION -> listOf(ItemResolution.REVERSED, ItemResolution.DECIDED, ItemResolution.CANCELLED)
            ItemKind.NOTICE, null -> emptyList()
        }
    }
}
