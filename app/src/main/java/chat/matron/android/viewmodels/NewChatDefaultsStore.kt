package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NewChatDefaults
import chat.matron.android.journal.NewChatDefaultsProviding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Settings → New chats: the user's default model and effort for new chats
/// (journal `GET`/`PUT /defaults`). Every bridge applies them to a Claude
/// chat started without a model or effort of its own — which is what New
/// Chat's "Default" row sends — so a pick here changes what that row runs
/// on, on every box that can run it. Ported from matron-apple's
/// `NewChatDefaultsStore`.
///
/// Reads `GET /defaults` when Settings opens and follows live `defaults`
/// frames while it shows ([updates]); the frame has no replay, so the read
/// on each open catches one missed meanwhile.
///
/// Shown state is the journal's latest value (`server`: the last `GET`,
/// frame or `PUT` answer) with the picks not yet stored laid on top. Saves
/// are single-flight per key: at most one `PUT` per key is in flight, and a
/// pick made meanwhile only replaces the waiting value. When the `PUT`
/// settles its answer applies to its own key only, and if a newer pick is
/// waiting one more `PUT` sends it — so an older answer never lands over a
/// newer pick. A failed `PUT` drops its pick (the key goes back to the
/// journal's value, with an error) and a newer pick waiting is still sent.
///
/// While a key's `PUT` is in flight, a live frame's value for that key is
/// held back: the answer to the request sent wins, and a refused one falls
/// back to the latest held value (as Devices does for box defaults). The
/// frame's other key applies at once.
///
/// Saves run on the store's own (session) scope, so leaving Settings while
/// one is in flight does not cancel it.
///
/// A save error belongs to its key and stays until that key's next pick or
/// successful save. A load error stays until the next successful read,
/// frame or save.
class NewChatDefaultsStore(
    private val api: NewChatDefaultsProviding,
    private val updates: () -> Flow<NewChatDefaults>,
    private val scope: CoroutineScope,
) {
    private val _defaults = MutableStateFlow<NewChatDefaults?>(null)
    /// `null` until the first `GET /defaults` answers.
    val defaults: StateFlow<NewChatDefaults?> = _defaults.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    /// `null` until the journal answers; `false` once `GET /defaults` 404s.
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    /// Each key's failed save, then a failed read; `null` when nothing has.
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /// The journal's latest value: the last `GET`, frame or `PUT` answer.
    private var server: NewChatDefaults? = null
    /// Picks not stored yet, per key (a `null` value = Box default): shown
    /// on top of [server] until their key's `PUT` answers.
    private val pending = mutableMapOf<NewChatDefaults.Key, String?>()
    /// Keys with a `PUT` loop running.
    private val saving = mutableSetOf<NewChatDefaults.Key>()
    private val saveErrors = mutableMapOf<NewChatDefaults.Key, String>()
    private var loadError: String? = null
    /// Bumped by every `GET` sent, pick, frame and `PUT` answer: a `GET`
    /// answer from before the latest of them is older news and is dropped.
    private var seq = 0
    /// The latest frame value per key that landed while that key's `PUT`
    /// ran (see the class note). A present key with a `null` value is a
    /// held "Box default".
    private val heldFrame = mutableMapOf<NewChatDefaults.Key, String?>()
    private var jobs = listOf<Job>()

    val isStarted: Boolean get() = jobs.isNotEmpty()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            // Subscribed before the GET goes out, so a change that lands
            // while it is in flight is not lost behind its answer.
            scope.launch { updates().collect { applyLive(it) } },
            scope.launch { refresh() },
        )
    }

    /// Stops listening. A save in flight carries on; a `GET` in flight is
    /// dropped.
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        seq += 1
    }

    /// `GET /defaults`. A 404 means the journal predates per-user defaults;
    /// any other failure keeps what is shown and says so.
    suspend fun refresh() {
        seq += 1
        val sentSeq = seq
        try {
            val answer = api.newChatDefaults()
            if (seq != sentSeq) return
            server = answer
            _isSupported.value = true
            loadError = null
            show()
        } catch (e: CancellationException) {
            throw e
        } catch (_: JournalApiError.NotFound) {
            if (seq != sentSeq) return
            _isSupported.value = false
            loadError = null
            show()
        } catch (e: Exception) {
            if (seq != sentSeq) return
            loadError = "Couldn't load the defaults: ${e.message ?: e}"
            show()
        }
    }

    /// Picks [value] (`null` = Box default) for [key]: shown at once, then
    /// `PUT`. Nothing happens before the first read has answered. While the
    /// key already has a `PUT` in flight this only replaces the waiting
    /// value. The `PUT` runs on the store's scope, not the caller's.
    fun set(key: NewChatDefaults.Key, value: String?) {
        if (server == null || _defaults.value?.get(key) == value) return
        // A read already in flight predates this pick: drop its answer.
        seq += 1
        pending[key] = value
        saveErrors.remove(key)
        show()
        if (key !in saving) scope.launch { drain(key) }
    }

    /// One key's `PUT` loop: sends the waiting pick, applies the answer, and
    /// goes round again while a newer pick waits. At most one per key.
    private suspend fun drain(key: NewChatDefaults.Key) {
        if (key in saving) return
        saving += key
        try {
            while (pending.containsKey(key)) {
                val desired = pending[key]
                try {
                    val stored = api.setNewChatDefault(key, desired)
                    seq += 1
                    // The answer to the request we sent wins over any frame
                    // held back meanwhile.
                    heldFrame.remove(key)
                    // The answer's own key, in the journal's spelling. Its
                    // other key is never newer than a frame already applied.
                    server = server?.with(key, stored[key]) ?: stored
                    if (pending.containsKey(key) && pending[key] == desired) pending.remove(key)
                    saveErrors.remove(key)
                    loadError = null
                    show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (pending.containsKey(key) && pending[key] == desired) pending.remove(key)
                    // Nothing was written: the latest frame held back is the
                    // journal's word for this key.
                    if (heldFrame.containsKey(key)) server = server?.with(key, heldFrame.remove(key))
                    saveErrors[key] = "Couldn't save the ${key.errorName}: ${e.message ?: e}"
                    show()
                }
            }
        } finally {
            saving -= key
            heldFrame.remove(key)
        }
    }

    /// A live `defaults` frame: the full new state from any device or agent,
    /// including the echo of this device's own save. Picks still waiting
    /// stay on top until their own `PUT` answers.
    fun applyLive(answer: NewChatDefaults) {
        seq += 1
        var next = answer
        for (key in NewChatDefaults.Key.entries) {
            if (key in saving) {
                heldFrame[key] = answer[key]
                next = next.with(key, server?.get(key))
            }
        }
        server = next
        _isSupported.value = true
        loadError = null
        show()
    }

    /// The journal's latest value with the waiting picks on top.
    private fun show() {
        val messages = NewChatDefaults.Key.entries.mapNotNull { saveErrors[it] } + listOfNotNull(loadError)
        _errorMessage.value = messages.takeIf { it.isNotEmpty() }?.joinToString("\n")
        var value = server ?: return
        for ((key, pick) in pending) value = value.with(key, pick)
        _defaults.value = value
    }

    companion object {
        const val HELP_TEXT =
            "Used when a new chat starts without a choice of its own, which is what New Chat's Default model does. " +
                "Box default uses each box's setting."
        const val UNSUPPORTED_TEXT = "This journal does not support default settings yet."

        /// The picker's options for [key] with a stored [value]. One outside
        /// the list (a full claude-* name another app or an agent wrote) is
        /// added under its own name, so the picker shows what is really set.
        fun choices(key: NewChatDefaults.Key, value: String?): List<BoxDefaultsChoices.Choice> {
            val base = when (key) {
                NewChatDefaults.Key.MODEL -> BoxDefaultsChoices.claudeModelChoices
                NewChatDefaults.Key.EFFORT -> BoxDefaultsChoices.claudeEffortChoices
            }
            if (value == null || base.any { it.value == value }) return base
            return base + BoxDefaultsChoices.Choice(value, value)
        }
    }
}
