package chat.matron.android.features.share

import android.net.Uri
import chat.matron.android.journal.JournalSyncError
import chat.matron.android.models.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// What the share screen needs from the rest of the app, behind a seam so a
/// test can stand in for the session, the store and the network.
interface ShareEnvironment {
    /// The signed-in session, or null when the app is signed out.
    suspend fun restoreSession(): UserSession?

    /// The conversations to offer, as the app's own (local) chat list has
    /// them; a new list each time it changes.
    fun targets(session: UserSession): Flow<List<ShareTarget>>

    /// Copies one shared item into app-private storage.
    suspend fun load(uri: Uri): SharedFile

    fun transport(session: UserSession): ShareTransport
}

/// Drives the share screen: what was shared, where it is going, and the
/// send. Ported from matron-apple's `ShareViewModel`.
///
/// The app lock is not this model's business: the screen draws the lock
/// shield instead of anything this model holds while the lock is engaged
/// (as the main app does), so the copy of the shared files, which must
/// happen while the read grant lasts, can run behind it.
class ShareViewModel(
    private val content: ShareIntentContent,
    private val environment: ShareEnvironment,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        /// Reading the shared items.
        data object Loading : Phase
        data object SignedOut : Phase
        data object Ready : Phase
        data class Sending(val progress: ShareProgress) : Phase
        data object Sent : Phase
    }

    /// Where Send will put the share.
    sealed interface Destination {
        data class Conversation(val id: String) : Destination
        /// A conversation of its own, started on a box and folder the user
        /// picks when they press Send.
        data object NewConversation : Destination
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _files = MutableStateFlow<List<SharedFile>>(emptyList())
    val files: StateFlow<List<SharedFile>> = _files.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _targets = MutableStateFlow<List<ShareTarget>>(emptyList())
    val targets: StateFlow<List<ShareTarget>> = _targets.asStateFlow()

    /// True until the first conversation list has arrived.
    private val _isLoadingTargets = MutableStateFlow(true)
    val isLoadingTargets: StateFlow<Boolean> = _isLoadingTargets.asStateFlow()

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    /// True while the box-and-folder chooser for a new conversation is up.
    private val _isChoosingNewConversation = MutableStateFlow(false)
    val isChoosingNewConversation: StateFlow<Boolean> = _isChoosingNewConversation.asStateFlow()

    /// A failure to show: an item that could not be read, or a send that did
    /// not go through. The screen stays open either way.
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var session: UserSession? = null
    private var transport: ShareTransport? = null
    private var sender: ShareSender? = null

    /// The signed-in session, for the new-conversation chooser.
    val signedInSession: UserSession? get() = session
    private var targetsJob: Job? = null

    /// A conversation the share started. A retry goes into it rather than
    /// starting another.
    private var startedConversation: ShareTarget? = null

    fun start() {
        if (session != null || _phase.value != Phase.Loading) return
        scope.launch {
            val restored = runCatching { environment.restoreSession() }.getOrNull()
            if (restored == null) {
                _phase.value = Phase.SignedOut
                return@launch
            }
            session = restored
            content.conversationID?.let { _destination.value = Destination.Conversation(it) }
            targetsJob = scope.launch {
                runCatching {
                    environment.targets(restored).collect { apply(it) }
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _isLoadingTargets.value = false
                    if (_targets.value.isEmpty()) _errorMessage.value = "Couldn't load your conversations."
                }
            }
            val loaded = mutableListOf<SharedFile>()
            for (uri in content.uris) {
                try {
                    loaded += environment.load(uri)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    if (_errorMessage.value == null) {
                        _errorMessage.value = error.message ?: ShareLoadError.Unreadable.message
                    }
                }
            }
            _files.value = loaded
            content.text?.let { _message.value = it }
            _phase.value = Phase.Ready
        }
    }

    /// Takes a new list, keeping the user's pick. With no pick yet the
    /// Coordinator is chosen, as the place things go by default.
    private fun apply(list: List<ShareTarget>) {
        val started = startedConversation
        val merged = if (started != null && list.none { it.id == started.id }) listOf(started) + list else list
        _targets.value = ShareTargets.ordered(merged)
        _isLoadingTargets.value = false
        if (_destination.value == null) {
            _targets.value.firstOrNull { it.isCoordinator }?.let { _destination.value = Destination.Conversation(it.id) }
        }
    }

    val visibleTargets: List<ShareTarget> get() = ShareTargets.filter(_targets.value, _query.value)

    /// Whether the picker offers a new conversation. A search is a search
    /// for one that exists, so the row goes while one is typed, unless it is
    /// the pick: what Send will do is always on screen.
    val offersNewConversation: Boolean
        get() = startedConversation == null &&
            (_destination.value == Destination.NewConversation || _query.value.isBlank())

    val canSend: Boolean
        get() = _phase.value == Phase.Ready && _destination.value != null &&
            (_files.value.isNotEmpty() || _message.value.isNotBlank())

    /// Files can be taken off until part of the share has gone: the rest
    /// then belong to the batch the first ones started.
    val canRemoveFiles: Boolean
        get() = _phase.value == Phase.Ready && sender?.hasSentAnything != true

    fun setMessage(text: String) {
        _message.value = text
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun select(conversationID: String) {
        if (_phase.value != Phase.Ready && _phase.value != Phase.Loading) return
        _destination.value = Destination.Conversation(conversationID)
    }

    fun selectNewConversation() {
        if (_phase.value != Phase.Ready && _phase.value != Phase.Loading) return
        if (startedConversation != null) return
        _destination.value = Destination.NewConversation
    }

    fun removeFile(id: String) {
        if (!canRemoveFiles) return
        val file = _files.value.firstOrNull { it.id == id } ?: return
        _files.value = _files.value - file
        file.delete()
    }

    /// Send: straight into the picked conversation, or, for a new one, up
    /// comes the chooser; the share goes once it has started one.
    fun send() {
        if (!canSend) return
        when (val target = _destination.value) {
            is Destination.Conversation -> sendTo(target.id)
            Destination.NewConversation -> openNewConversationChooser()
            null -> Unit
        }
    }

    /// The chooser asks the boxes over the journal connection, so that
    /// comes up first (the app may not have been running).
    private fun openNewConversationChooser() {
        val transport = transport() ?: return
        _errorMessage.value = null
        _phase.value = Phase.Sending(ShareProgress(ShareProgress.Step.CONNECTING, fileCount = _files.value.size))
        scope.launch {
            try {
                transport.connect()
                _phase.value = Phase.Ready
                _isChoosingNewConversation.value = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _phase.value = Phase.Ready
                _errorMessage.value = sendErrorMessage(error)
            }
        }
    }

    private fun transport(): ShareTransport? {
        val session = session ?: return null
        return transport ?: environment.transport(session).also { transport = it }
    }

    /// The chooser started [convoID]: the share goes there, and stays there
    /// for a retry.
    fun newConversationStarted(convoID: String) {
        _isChoosingNewConversation.value = false
        val started = ShareTarget(id = convoID, title = "New chat")
        startedConversation = started
        if (_targets.value.none { it.id == convoID }) _targets.value = listOf(started) + _targets.value
        _destination.value = Destination.Conversation(convoID)
        sendTo(convoID)
    }

    fun newConversationCancelled() {
        _isChoosingNewConversation.value = false
    }

    private fun sendTo(convoID: String) {
        val transport = transport() ?: return
        val sender = sender ?: ShareSender(transport).also { sender = it }
        val files = _files.value
        _errorMessage.value = null
        _phase.value = Phase.Sending(ShareProgress(ShareProgress.Step.CONNECTING, fileCount = files.size))
        scope.launch {
            try {
                sender.send(convoID, _message.value, files) { progress ->
                    val current = _phase.value as? Phase.Sending ?: return@send
                    if (progress.fraction >= current.progress.fraction) _phase.value = Phase.Sending(progress)
                }
                _phase.value = Phase.Sent
                cleanUp()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _phase.value = Phase.Ready
                _errorMessage.value = sendErrorMessage(error)
            }
        }
    }

    /// Deletes the copies of the shared files. Called once sent, and when
    /// the screen closes whichever way it closes.
    fun cleanUp() {
        _files.value.forEach { it.delete() }
    }

    /// The screen is gone: delete the copies and let go of the connection.
    fun close() {
        cleanUp()
        transport?.release()
    }

    companion object {
        fun sendErrorMessage(error: Throwable): String = when (error) {
            is JournalSyncError.Offline, is ShareConnectError ->
                "Couldn't reach Matron's server. Check your connection and try again."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Couldn't send. Try again."
        }
    }
}

/// The journal connection didn't come up in time to send.
class ShareConnectError : Exception("Couldn't reach Matron's server.")
