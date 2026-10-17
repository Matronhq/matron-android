package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// One change in the journal's archive feed (protocol "Archived
/// conversations"): a conversation archived at [archivedAt] (ms), or
/// unarchived when that is `null`. [auto] is true when the journal's idle
/// sweep archived it rather than the user.
data class ArchiveChange(val convoID: String, val archivedAt: Long?, val auto: Boolean = false) {
    companion object {
        /// One `{convo_id, archived_at, auto}` object, or `null` when it lacks
        /// an id or carries an `archived_at` that is neither a number nor null.
        fun decode(raw: JsonElement): ArchiveChange? {
            val obj = raw as? JsonObject ?: return null
            val id = obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
            val archivedAt = when (val at = obj["archived_at"]) {
                null, is JsonNull -> null
                is JsonPrimitive -> {
                    if (at.isString) return null
                    at.content.toLongOrNull() ?: at.content.toDoubleOrNull()?.toLong() ?: return null
                }
                else -> return null
            }
            return ArchiveChange(id, archivedAt, auto = obj.boolOrNull("auto") ?: false)
        }
    }
}

/// `{changes, at}`: what `hello_ok.archive`, an `archive` frame and
/// `GET /archive` carry. [at] is the value to send as `archive_since` next
/// time.
data class ArchiveDelta(val changes: List<ArchiveChange>, val at: Long) {
    companion object {
        /// `null` when [raw] is not an object with a `changes` array — a
        /// journal without archiving sends no `archive` key at all. Malformed
        /// changes are dropped; a missing or bad `at` reads as 0.
        fun decode(raw: JsonElement?): ArchiveDelta? {
            val obj = raw as? JsonObject ?: return null
            val changes = obj["changes"] as? JsonArray ?: return null
            val at = obj.longOrNull("at")?.takeIf { it >= 0 } ?: 0
            return ArchiveDelta(changes.mapNotNull(ArchiveChange::decode), at)
        }
    }
}

/// `POST /archive`. An interface so [ArchiveSync] tests fake the journal;
/// [JournalApi] implements it.
interface ArchiveWriting {
    /// Archives (or unarchives) up to [ArchiveSync.BATCH_LIMIT] ids at once.
    suspend fun setArchived(convoIDs: List<String>, archived: Boolean)
}

/// One conversation's archive state, kept while a change of the user's is
/// going out, so a refused write puts back exactly what was there:
/// [archivedAt], and the purge marks of the conversation and its sub-chats.
data class ArchiveState(val archivedAt: Long?, val purgedAt: Map<String, Long?>)

/// The store side of [ArchiveSync]. [JournalStore] implements it; tests may
/// use a real in-memory store.
interface ArchiveStoring {
    suspend fun archiveSince(): Long?
    suspend fun setArchiveSince(at: Long)
    suspend fun setArchived(convoIDs: List<String>, archived: Boolean, now: Long = System.currentTimeMillis())
    suspend fun applyArchiveChanges(changes: List<ArchiveChange>, advancingSinceTo: Long?)
    suspend fun adoptArchiveSet(changes: List<ArchiveChange>): List<String>
    suspend fun archiveStates(convoIDs: List<String>): Map<String, ArchiveState>
    suspend fun restoreArchiveStates(states: Map<String, ArchiveState>)
}

/// The user's archived conversations, kept in step with the journal. Port of
/// matron-apple's `ArchiveSync`.
///
/// The local mirror's `archived_at` column is what the app reads. On a
/// journal that syncs the archive it is a cache of the journal's set, kept
/// current by `hello_ok.archive` and live `archive` frames, and every
/// Archive/Unarchive is also sent with `POST /archive`. A journal without
/// archiving never answers `archive_since`, and the archive stays on this
/// device, as the old "Leave" did.
///
/// The cursor is the store's `archive_since` meta value. No cursor means this
/// device has not synced the archive yet: the hello asks for the whole set,
/// the ids archived only here (the old hidden rows, moved over by a
/// migration) are carried over to the journal once, and only then is the
/// cursor stored, so a carry-over that fails is tried again next connect.
///
/// Writes go out one `POST` at a time, always with the latest choice for each
/// conversation, so the journal ends where the user did. A refused write
/// puts the row back, but only while it is still the user's latest choice.
class ArchiveSync(
    private val store: ArchiveStoring,
    private val writer: ArchiveWriting,
    private val now: () -> Long = { System.currentTimeMillis() },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    /// Guards everything below, and the store writes made with it, so a
    /// frame and a tap never interleave their reads and writes.
    private val mutex = Mutex()

    /// Whether this journal syncs the archive: learnt from each `hello_ok`,
    /// and assumed from a stored cursor until the first one (`null` = not
    /// read yet), so an Archive tapped before the socket is up still reaches
    /// a journal this device has synced with.
    private var syncs: Boolean? = null
    /// The state the user last chose for each conversation whose write has
    /// not landed yet.
    private val wanted = LinkedHashMap<String, Boolean>()
    /// Each pending conversation's state before the user's first unsent
    /// change: what a refused write restores.
    private val before = HashMap<String, ArchiveState>()
    /// The first sync's carry-over still to send: the ids archived only on
    /// this device, and the cursor to store once they are on the journal.
    private var carryOver: Pair<List<String>, Long>? = null
    /// The one job that sends: the carry-over first, then the user's
    /// choices. One sender, so no two `POST`s for a conversation race.
    private var worker: Job? = null
    private var stopped = false

    /// The `archive_since` to send in `hello`: the stored cursor, or 0 for
    /// the whole set.
    suspend fun helloArchiveSince(): Long = runCatching { store.archiveSince() }.getOrNull() ?: 0

    /// Archive or Unarchive, from the user. Shown at once; sent to the
    /// journal when it syncs the archive.
    suspend fun setArchived(convoIDs: List<String>, archived: Boolean) {
        if (convoIDs.isEmpty()) return
        mutex.withLock {
            try {
                if (syncsLocked()) {
                    val fresh = convoIDs.filter { it !in before }
                    if (fresh.isNotEmpty()) before.putAll(store.archiveStates(fresh))
                }
                store.setArchived(convoIDs, archived, now())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                MatronDebug.breadcrumb("ArchiveSync: archive write failed: $e")
                return
            }
            if (!syncsLocked()) return
            for (id in convoIDs) wanted[id] = archived
            startWorkerLocked()
        }
    }

    /// `hello_ok`'s `archive`, or `null` when the journal sent none (it does
    /// not sync the archive: the archive stays on this device).
    suspend fun applyHello(delta: ArchiveDelta?) {
        mutex.withLock {
            if (delta == null) {
                syncs = false
                return
            }
            syncs = true
            val synced = runCatching { store.archiveSince() }.getOrNull() != null
            if (synced) {
                applyLocked(delta)
                return
            }
            // First sync on this device: adopt the journal's set, keep what
            // is archived only here, and send that over before storing a
            // cursor.
            try {
                val toImport = store.adoptArchiveSet(delta.changes.filter { it.convoID !in wanted })
                carryOver = toImport to delta.at
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Throwable) {
                MatronDebug.breadcrumb("ArchiveSync: archive adopt failed: $e")
                return
            }
            startWorkerLocked()
        }
    }

    /// An `archive` frame, or `hello_ok`'s changes since the stored cursor.
    /// A conversation with a write of the user's still going out keeps the
    /// user's choice: that write is what the journal will end on.
    suspend fun apply(delta: ArchiveDelta) {
        mutex.withLock { applyLocked(delta) }
    }

    private suspend fun applyLocked(delta: ArchiveDelta) {
        syncs = true
        try {
            store.applyArchiveChanges(delta.changes.filter { it.convoID !in wanted }, delta.at)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            MatronDebug.breadcrumb("ArchiveSync: archive frame not applied: $e")
        }
    }

    /// Sign-out: no further writes go out.
    suspend fun stop() {
        val job = mutex.withLock {
            stopped = true
            wanted.clear()
            before.clear()
            carryOver = null
            worker.also { worker = null }
        }
        job?.cancel()
        job?.join()
    }

    /// Returns once every write already chosen has been sent or refused.
    /// For tests.
    internal suspend fun waitUntilIdle() {
        while (true) {
            val job = mutex.withLock { worker } ?: return
            job.join()
        }
    }

    private suspend fun syncsLocked(): Boolean =
        syncs ?: (runCatching { store.archiveSince() }.getOrNull() != null).also { syncs = it }

    private fun startWorkerLocked() {
        if (stopped || worker != null) return
        worker = scope.launch { drain() }
    }

    private sealed interface Step {
        data class CarryOver(val ids: List<String>, val at: Long) : Step
        data class Send(val batch: List<String>, val state: Boolean) : Step
    }

    /// Sends the carry-over, then the pending choices, one batch of
    /// conversations wanting the same state at a time, until none is left.
    private suspend fun drain() {
        while (true) {
            val step = mutex.withLock {
                val pending = carryOver
                when {
                    stopped -> null
                    pending != null -> {
                        carryOver = null
                        Step.CarryOver(pending.first, pending.second)
                    }
                    wanted.isNotEmpty() -> {
                        val state = wanted.values.first()
                        Step.Send(wanted.filterValues { it == state }.keys.sorted().take(BATCH_LIMIT), state)
                    }
                    else -> null
                }.also { if (it == null) worker = null }
            } ?: return
            when (step) {
                is Step.CarryOver -> sendCarryOver(step.ids, step.at)
                is Step.Send -> send(step.batch, step.state)
            }
        }
    }

    /// A conversation the user has changed since is left to the user's own
    /// write, which follows.
    private suspend fun sendCarryOver(ids: List<String>, at: Long) {
        try {
            for (chunk in ids.chunked(BATCH_LIMIT)) {
                val batch = mutex.withLock { chunk.filter { it !in wanted } }
                if (batch.isNotEmpty()) writer.setArchived(batch, archived = true)
            }
            store.setArchiveSince(at)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            MatronDebug.breadcrumb("ArchiveSync: carry-over failed, retried next connect: $e")
        }
    }

    private suspend fun send(batch: List<String>, state: Boolean) {
        try {
            writer.setArchived(batch, archived = state)
            mutex.withLock {
                for (id in batch) {
                    if (wanted[id] == state) {
                        wanted.remove(id)
                        before.remove(id)
                    } else if (id in wanted) {
                        // The user has chosen again since: what a refusal of
                        // that later write restores is now what the journal
                        // just accepted.
                        before[id] = accepted(before[id], state)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            MatronDebug.breadcrumb("ArchiveSync: POST /archive failed: $e")
            mutex.withLock {
                val refused = batch.filter { wanted[it] == state }
                refused.forEach { wanted.remove(it) }
                val states = refused.mapNotNull { id -> before.remove(id)?.let { id to it } }.toMap()
                runCatching { store.restoreArchiveStates(states) }
                runCatching { store.setArchived(refused.filter { it !in states }, !state, now()) }
            }
        }
    }

    /// The state the journal holds after accepting [archived] over [prev]:
    /// archiving an archived conversation keeps its first time, and an
    /// unarchive clears the purge marks.
    private fun accepted(prev: ArchiveState?, archived: Boolean): ArchiveState = if (archived) {
        ArchiveState(prev?.archivedAt ?: now(), prev?.purgedAt.orEmpty())
    } else {
        ArchiveState(null, prev?.purgedAt.orEmpty().mapValues { null })
    }

    companion object {
        /// The journal takes at most this many ids per `POST /archive`.
        const val BATCH_LIMIT = 500
    }
}
