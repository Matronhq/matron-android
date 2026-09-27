package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// One attachment about to be uploaded for a comment: raw bytes plus the
/// name and MIME type the journal blob will carry.
data class OutgoingAttachment(val data: ByteArray, val name: String, val mime: String)

/// Backs the item detail screen: one item, its comments, and the local
/// outbox of comments still in flight. Writes go through [ItemsSyncing],
/// which owns the outbox and refetch coalescing — this view model calls
/// `refreshItem` after every mutating action without worrying about
/// de-duping concurrent calls. Ported from matron-apple's `ItemDetailViewModel`.
class ItemDetailViewModel(
    val itemID: String,
    private val store: ItemsStoreReading,
    private val api: ItemsProviding,
    private val sync: ItemsSyncing,
    private val scope: CoroutineScope,
) {
    private val _item = MutableStateFlow<TrackerItem?>(null)
    val item: StateFlow<TrackerItem?> = _item.asStateFlow()

    private val _comments = MutableStateFlow<List<TrackerComment>>(emptyList())
    val comments: StateFlow<List<TrackerComment>> = _comments.asStateFlow()

    private val _pendingComments = MutableStateFlow<List<ItemOutboxEntity>>(emptyList())
    val pendingComments: StateFlow<List<ItemOutboxEntity>> = _pendingComments.asStateFlow()

    /// The reply being composed. Plain state (not a flow) like
    /// `ComposerViewModel.input`: the field is the source of truth while typing.
    var draft: String = ""

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

    private suspend fun upload(attachments: List<OutgoingAttachment>): List<TrackerAttachment>? {
        val uploaded = mutableListOf<TrackerAttachment>()
        try {
            for (a in attachments) {
                val ref = api.uploadMedia(a.data, a.mime)
                uploaded += TrackerAttachment(blobRef = ref, mime = a.mime, name = a.name, size = a.data.size.toLong())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = "Couldn't upload an attachment: ${error.message ?: error}"
            return null
        }
        return uploaded
    }

    /// Uploads attachments first, then enqueues the comment (the local id is
    /// minted here, not by [ItemsSyncing] — the outbox row needs it before the
    /// enqueue call returns so the pending-comments flow can show it). The
    /// draft is cleared on enqueue, not restored on failure: the outbox holds
    /// the text durably and retries on its own.
    suspend fun submitComment(attachments: List<OutgoingAttachment> = emptyList()) {
        val text = draft.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        _isBusy.value = true
        try {
            val uploaded = upload(attachments) ?: return
            draft = ""
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), text, uploaded)
        } finally {
            _isBusy.value = false
        }
    }

    /// "Attach a file/photo" — distinct from [submitComment]: this uploads
    /// and enqueues an attachment-only comment (body `""`) without ever
    /// reading or clearing [draft], so attaching never posts whatever
    /// half-written text is sitting in the field. [sendVoiceNote] is one such
    /// caller — a voice note is always an attachment-only comment. Returns
    /// whether the upload succeeded.
    suspend fun submitAttachments(attachments: List<OutgoingAttachment>): Boolean {
        if (attachments.isEmpty()) return true
        _isBusy.value = true
        try {
            val uploaded = upload(attachments) ?: return false
            sync.enqueueComment(itemID, UUID.randomUUID().toString(), "", uploaded)
            return true
        } finally {
            _isBusy.value = false
        }
    }

    /// Deletes the recording file only once [submitAttachments] reports the
    /// upload actually succeeded — an upload failure must not destroy the
    /// only copy of the recording. An empty/unreadable recording also cleans
    /// up: unlike an upload failure there's nothing worth retrying.
    suspend fun sendVoiceNote(file: File) {
        val data = runCatching { file.readBytes() }.getOrNull()
        if (data == null || data.isEmpty()) {
            _error.value = "Voice note was empty."
            file.delete()
            return
        }
        val ok = submitAttachments(listOf(OutgoingAttachment(data, "voice-note.m4a", "audio/mp4")))
        if (ok) file.delete()
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
        fun resolutions(kind: ItemKind?, userHasReplied: Boolean): List<ItemResolution> = when (kind) {
            ItemKind.TASK -> listOf(ItemResolution.DONE, ItemResolution.CANCELLED)
            ItemKind.QUESTION -> if (userHasReplied) listOf(ItemResolution.ANSWERED, ItemResolution.CANCELLED) else listOf(ItemResolution.CANCELLED)
            ItemKind.DECISION -> listOf(ItemResolution.REVERSED, ItemResolution.DECIDED, ItemResolution.CANCELLED)
            null -> emptyList()
        }
    }
}
