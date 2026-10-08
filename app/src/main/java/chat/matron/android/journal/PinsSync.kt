package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.viewmodels.KeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// The surface the pin screens depend on. [PinsSync] implements it; tests
/// fake it. Every write resolves to an error message in words, or null on
/// success (the list then comes from the journal's answer).
interface PinsSyncing {
    /// The user's pins in position order; `null` while this journal is not
    /// known to have pins (an older journal, or not heard yet with nothing
    /// cached) — every pin surface then shows nothing.
    val pins: StateFlow<List<ConvoPin>?>
    /// The journal's cap, from the last `/pins` answer.
    val limit: StateFlow<Int>

    /// `GET /pins` — the list and the limit (the hello carries no limit).
    suspend fun refresh()
    suspend fun save(convoID: String, label: String, emoji: String): String?
    suspend fun move(convoID: String, toConvoID: String): String?
    suspend fun remove(convoID: String): String?
    suspend fun shift(convoID: String, direction: Pins.Direction): String?
    suspend fun acceptSuccessor(pin: ConvoPin): String?
    suspend fun dismissSuccessor(pin: ConvoPin): String?
}

/// The user's pinned desk chats (journal "Pinned desk chats"), in memory
/// with a small preference-store cache so a cold start draws the Pinned
/// section before the socket's `hello_ok` replaces it. Reads come from the
/// engine's [JournalSyncEngine.pinsUpdates] (hello, snapshot, live `pins`
/// frames); writes go straight to the journal and adopt its answer. Ported
/// from matron-web's client pin methods (the browser-local import there has
/// no Android counterpart: this app never had local pins).
class PinsSync(
    private val api: PinsProviding,
    private val updates: () -> Flow<List<ConvoPin>?>,
    private val cache: KeyValueStore? = null,
    private val userID: String = "",
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PinsSyncing {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    private val _pins = MutableStateFlow(cache?.getString(cacheKey(userID))?.let(Pins::decodeList))
    override val pins: StateFlow<List<ConvoPin>?> = _pins.asStateFlow()
    private val _limit = MutableStateFlow(Pins.DEFAULT_LIMIT)
    override val limit: StateFlow<Int> = _limit.asStateFlow()

    // Guarded by `lock`.
    private var updatesJob: Job? = null
    private var stopped = false

    fun start() {
        synchronized(lock) {
            stopped = false
            if (updatesJob != null) return
            updatesJob = scope.launch { updates().collect { adopt(it) } }
        }
    }

    suspend fun stop() {
        val job = synchronized(lock) {
            stopped = true
            updatesJob.also { updatesJob = null }
        }
        job?.cancel()
        job?.join()
    }

    override suspend fun refresh() {
        try {
            val list = api.pins()
            adopt(list.pins, list.limit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            adopt(null)
        } catch (e: Throwable) {
            MatronDebug.breadcrumb("PinsSync: GET /pins failed: $e")
        }
    }

    override suspend fun save(convoID: String, label: String, emoji: String): String? =
        write { api.putPin(convoID, Pins.clampLabel(label), Pins.clampEmoji(emoji)) }

    override suspend fun move(convoID: String, toConvoID: String): String? =
        write { api.movePin(convoID, toConvoID) }

    override suspend fun remove(convoID: String): String? = write { api.deletePin(convoID) }

    override suspend fun shift(convoID: String, direction: Pins.Direction): String? {
        val order = Pins.movedOrder(_pins.value.orEmpty(), convoID, direction) ?: return null
        return write { api.reorderPins(order) }
    }

    override suspend fun acceptSuccessor(pin: ConvoPin): String? {
        val successor = pin.successor ?: return null
        return move(pin.convoID, successor.convoID)
    }

    override suspend fun dismissSuccessor(pin: ConvoPin): String? {
        val successor = pin.successor ?: return null
        return write { api.dismissPinSuccessor(pin.convoID, successor.convoID) }
    }

    private suspend fun write(request: suspend () -> PinList): String? = try {
        val list = request()
        adopt(list.pins, list.limit)
        null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Throwable) {
        errorMessage(e)
    }

    /// Adopts the journal's list (null = this journal has no pins) and
    /// caches it for the next start. A stopped sync (sign-out) adopts
    /// nothing, so a late answer cannot repopulate the cleared cache.
    private fun adopt(pins: List<ConvoPin>?, limit: Int? = null) {
        synchronized(lock) {
            if (stopped) return
            // Cache first: whoever sees the new list may already rely on
            // the next start drawing it.
            cache?.let { store ->
                if (pins == null) store.remove(cacheKey(userID)) else store.setString(cacheKey(userID), Pins.encodeList(pins))
            }
            limit?.let { _limit.value = it }
            _pins.value = pins
        }
    }

    companion object {
        fun cacheKey(userID: String): String = "pins.cache.$userID"

        /// Removes the cached list (sign-out teardown of per-account state).
        fun clear(userID: String, store: KeyValueStore) = store.remove(cacheKey(userID))

        /// A failed write in words: the journal's refusals carry their own
        /// ([PinRequestError]); anything else reads as a connection problem.
        fun errorMessage(error: Throwable): String = when (error) {
            is PinRequestError -> error.message ?: "Couldn't update pins."
            is JournalApiError -> error.message ?: "Couldn't update pins."
            else -> "Couldn't update pins."
        }
    }
}
