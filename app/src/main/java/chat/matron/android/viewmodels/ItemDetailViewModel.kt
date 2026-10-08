package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.StagedAttachment
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/// One attachment about to be uploaded for a comment: raw bytes plus the
/// name and MIME type the journal blob will carry.
private class OutgoingAttachment(val data: ByteArray, val name: String, val mime: String)

/// Backs the item detail screen: one item, its comments, and the local
/// outbox of comments still in flight. Writes go through [ItemsSyncing],
/// which owns the outbox and refetch coalescing — this view model calls
/// `refreshItem` after every mutating action without worrying about
/// de-duping concurrent calls. Ported from matron-apple's `ItemDetailViewModel`.
///
/// Picked photos/files are staged into a tray ([stagedAttachments], copies
/// under [stagingDirectory] — the chat composer's [StagedAttachment]) and
/// leave with the draft as one comment, never on their own at pick time.
class ItemDetailViewModel(
    val itemID: String,
    private val store: ItemsStoreReading,
    private val api: ItemsProviding,
    private val sync: ItemsSyncing,
    private val scope: CoroutineScope,
    private val stagingDirectory: File,
) {
    private val _item = MutableStateFlow<TrackerItem?>(null)
    val item: StateFlow<TrackerItem?> = _item.asStateFlow()

    private val _comments = MutableStateFlow<List<TrackerComment>>(emptyList())
    val comments: StateFlow<List<TrackerComment>> = _comments.asStateFlow()

    private val _pendingComments = MutableStateFlow<List<ItemOutboxEntity>>(emptyList())
    val pendingComments: StateFlow<List<ItemOutboxEntity>> = _pendingComments.asStateFlow()

    /// The reply being composed. The field writes it on every keystroke; it is
    /// also a flow ([draftFlow]) because a send can fail after the screen has
    /// gone and come back, and the field must then pick the restored text up.
    private val _draft = MutableStateFlow("")
    val draftFlow: StateFlow<String> = _draft.asStateFlow()
    var draft: String
        get() = _draft.value
        set(value) { _draft.value = value }

    /// The tray: attachments picked but not yet sent, in send order.
    private val _stagedAttachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val stagedAttachments: StateFlow<List<StagedAttachment>> = _stagedAttachments.asStateFlow()

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

    private val jobs = mutableListOf<Job>()
    private var commentsJob: Job? = null
    private var refreshJob: Job? = null

    fun start() {
        stop()
        val id = itemID
        jobs += scope.launch { store.itemFlow(id).collect { _item.value = it } }
        subscribeComments()
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
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        commentsJob?.cancel(); commentsJob = null
        refreshJob?.cancel(); refreshJob = null
    }

    private fun subscribeComments() {
        commentsJob?.cancel()
        val id = itemID
        commentsJob = scope.launch { store.commentsFlow(id).collect { _comments.value = it } }
    }

    /// The resolutions the person can close this item with, primary first.
    /// Only outcomes they can honestly claim: a question is answered by
    /// *replying* (the journal hands it back to the agent, which closes it as
    /// answered once it has acted), so "Answered" is offered only once they
    /// have actually replied — before that the only honest close is to
    /// dismiss it. An open decision is already in force, so reversing it
    /// leads. A reply still in the outbox counts: it is the user's, and it
    /// will land.
    val availableResolutions: List<ItemResolution>
        get() = resolutions(
            _item.value?.kind,
            userHasReplied = _pendingComments.value.isNotEmpty() ||
                _comments.value.any { it.author == ItemAuthor.USER && it.kind == TrackerComment.Kind.COMMENT },
        )

    /// Uploads [attachments] in order, reading each staged copy off the main
    /// thread just before it goes — one at a time, so at most one
    /// attachment's bytes are in memory however full the tray is. `null`
    /// (with [error] set) when any of them fails.
    @JvmName("uploadStaged")
    private suspend fun upload(attachments: List<StagedAttachment>): List<TrackerAttachment>? {
        val uploaded = mutableListOf<TrackerAttachment>()
        for (a in attachments) {
            val data = try {
                withContext(Dispatchers.IO) { a.file.readBytes() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = "Couldn't read an attachment: ${error.message ?: error}"
                return null
            }
            uploaded += upload(OutgoingAttachment(data, a.filename, a.mimeType)) ?: return null
        }
        return uploaded
    }

    private suspend fun upload(a: OutgoingAttachment): TrackerAttachment? = try {
        val ref = api.uploadMedia(a.data, a.mime)
        TrackerAttachment(blobRef = ref, mime = a.mime, name = a.name, size = a.data.size.toLong())
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
        val pending = draft
        val attachments = listOfNotNull(leading) + _stagedAttachments.value
        draft = ""
        _stagedAttachments.value = emptyList()
        _isBusy.value = true
        var sent = false
        try {
            val uploaded = upload(attachments) ?: return
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), pending.trim(), uploaded)
            sent = true
            attachments.forEach { it.deleteStagedCopy() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        } finally {
            if (!sent) {
                _stagedAttachments.value = attachments + _stagedAttachments.value
                if (draft.isEmpty()) draft = pending
            }
            _isBusy.value = false
        }
    }

    /// Stages each picked file into the tray rather than posting it. An
    /// unreadable file is reported via [error] and stages nothing.
    suspend fun attachFiles(files: List<File>) {
        for (file in files) {
            if (_stagedAttachments.value.size >= MAX_COMMENT_ATTACHMENTS) {
                _error.value = "A reply carries at most $MAX_COMMENT_ATTACHMENTS attachments."
                return
            }
            try {
                val staged = withContext(Dispatchers.IO) { StagedAttachment.stage(file, stagingDirectory) }
                _stagedAttachments.value = _stagedAttachments.value + staged
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _error.value = "Couldn't attach that file: ${error.message ?: error}"
            }
        }
    }

    /// Drops one attachment from the tray (its ✕) and deletes its copy.
    fun removeAttachment(id: String) {
        val attachment = _stagedAttachments.value.firstOrNull { it.id == id } ?: return
        _stagedAttachments.value = _stagedAttachments.value.filterNot { it.id == id }
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
        // A full tray leaves no room for the note on the same comment: it goes alone.
        if (_stagedAttachments.value.size >= MAX_COMMENT_ATTACHMENTS) {
            sendUnstagedVoiceNote(file)
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
            val data = runCatching { withContext(Dispatchers.IO) { file.readBytes() } }.getOrNull()
            if (data == null) {
                _error.value = "Couldn't read the voice note."
                return
            }
            val uploaded = upload(OutgoingAttachment(data, VOICE_NOTE_NAME, VOICE_NOTE_MIME)) ?: return
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), "", listOf(uploaded))
            file.delete()
        } finally {
            _isBusy.value = false
        }
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
        private const val VOICE_NOTE_NAME = "voice-note.m4a"
        private const val VOICE_NOTE_MIME = "audio/mp4"

        /// The journal takes at most this many attachments on one comment.
        const val MAX_COMMENT_ATTACHMENTS = 20

        /// The taps still in the outbox, as asking-comment id → label (the
        /// latest wins, as it will on the journal). The thread marks these as
        /// chosen straight away instead of waiting for the round trip.
        fun pendingTaps(pending: List<ItemOutboxEntity>): Map<String, String> = buildMap {
            for (row in pending) {
                val payload = runCatching { MatronJson.decodeFromString(ItemsSync.CommentPayload.serializer(), row.payloadJson) }.getOrNull() ?: continue
                val action = payload.action ?: continue
                val replyTo = payload.replyTo ?: continue
                put(replyTo, action)
            }
        }

        /// The latest tap on the item's own buttons still in the outbox (a
        /// row with an `action` and no `replyTo`), or `null`.
        fun pendingItemTap(pending: List<ItemOutboxEntity>): String? = pending.mapNotNull { row ->
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
