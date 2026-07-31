package chat.matron.android.chat

import chat.matron.android.journal.ActivityUpdate
import chat.matron.android.journal.ClientOp
import chat.matron.android.journal.EphemeralUpdate
import chat.matron.android.journal.body
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.JournalSyncError
import chat.matron.android.journal.db.OutboxEntity
import chat.matron.android.journal.MediaKind
import chat.matron.android.journal.previewText
import chat.matron.android.journal.ToolStreamUpdate
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TimelineSendState
import chat.matron.android.models.UserSession
import chat.matron.android.search.SearchService
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

private fun maxInstant(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b

/// [TimelineService] over the local journal mirror, one instance per open room.
/// Ported from matron-apple's `JournalTimelineService`.
///
/// `items()` merges three inputs into a single snapshot stream: store events
/// mapped through [JournalTimelineMapper], streaming/tool-stream ephemeral
/// overlays, and pending-send echoes. All three are coalesced on
/// [OverlayState] so mutation from the ephemeral fan-out and from `sendText`
/// can't race.
///
/// Pending-send echoes are a projection of the durable outbox
/// ([JournalStore.outboxFlow]): rows are created by `sendText` →
/// [JournalSyncEngine.sendMessage], survive relaunch, and are deleted by the
/// store when the own-text journal frame confirms delivery. `suppressedSendIDs`
/// bridges the gap between the events observation and the outbox observation
/// firing: the same reconcile pass that surfaces the confirming row hides its
/// echo, so the row and its echo can never render together in one snapshot.
class JournalTimelineService(
    private val convoID: String,
    private val store: JournalStore,
    private val engine: JournalSyncEngine,
    private val api: JournalApi,
    session: UserSession,
    private val search: SearchService? = null,
    overlayStaleness: Duration = 30.seconds,
    private val sweepInterval: Duration = 10.seconds,
    toolStreamStaleness: Duration = 600.seconds,
) : TimelineService {
    private val ownSender: String = "user:${session.userID}"
    private val overlay = OverlayState(staleness = overlayStaleness, toolStaleness = toolStreamStaleness)

    /// Detached scope for the teardown `viewing: null` send, which must outlive
    /// the (cancelling) collector scope so it still reaches the socket.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /// Streaming overlays + local echoes, isolated behind one monitor [lock] so
    /// the ephemeral fan-out and `sendText` serialize like the Apple `actor`.
    /// Every mutation is synchronous; no I/O happens while holding the lock.
    class OverlayState(
        private val staleness: Duration,
        private val toolStaleness: Duration = 600.seconds,
    ) {
        data class Streaming(val text: String, val updated: Instant)
        data class Activity(val label: String, val updated: Instant)

        /// One live tool-output stream, keyed by message_ref. Positions are
        /// UTF-8 BYTE offsets; `bytes[0]` sits at absolute offset [startOffset].
        class ToolStream(
            var tool: String?,
            var command: String?,
            var bytes: ByteArray,
            var startOffset: Int,
            var headTruncated: Boolean,
            var updated: Instant,
        )

        private val lock = Any()
        private val streaming = mutableMapOf<String, Streaming>()
        private var eventsList: List<JournalEvent> = emptyList()
        private val mappedCache = mutableMapOf<Long, TimelineItem>()
        private val unmappable = mutableSetOf<Long>()
        private var activity: Activity? = null

        /// Latest outbox rows for this conversation (queued + failed, oldest
        /// first) — the durable replacement for the old in-memory echo array.
        private var outboxRowsList: List<OutboxEntity> = emptyList()

        /// Rows hidden at render time because a reconcile pass already saw
        /// their confirming own-text journal row (the store's DB delete lands
        /// a beat later; without this the delivered message and its echo would
        /// double-render for a frame).
        private val suppressedSendIDs = mutableSetOf<String>()

        /// Last-known engine connection state — drives the queued/sending
        /// glyph on pending sends (see [sendState]).
        private var syncState: SyncConnectionState = SyncConnectionState.Connecting

        private val toolStreams = mutableMapOf<String, ToolStream>()
        private val retiredToolRefs = mutableListOf<String>()
        private val resyncRequested = mutableMapOf<String, Instant>()
        private var lastReconciledSeq: Long = 0

        /// Seq high-water of rows already persisted when the room opened.
        /// Rows at or below it are history, not arrivals — they can never
        /// retire an echo, even on the very first reconcile (bugbot "Echo
        /// cleared by history replay": an old own message whose body matches
        /// an in-flight echo must not swallow its "sending" row). Set once by
        /// the service before the store subscription starts; the direct-use
        /// tests leave it at 0, preserving retire-on-first-reconcile there.
        private var baselineSeq: Long = 0
        private var baselineSeeded = false

        fun seedBaseline(seq: Long) = synchronized(lock) {
            if (!baselineSeeded) { baselineSeq = seq; baselineSeeded = true }
        }

        // MARK: Reads (return snapshots for the items() emit)

        val events: List<JournalEvent> get() = synchronized(lock) { eventsList }

        fun setEvents(events: List<JournalEvent>) = synchronized(lock) { eventsList = events }

        /// Replaces the outbox projection with the observation's latest rows.
        /// Suppression markers for rows the store has since deleted are
        /// dropped so the set can't grow unbounded.
        fun setOutbox(rows: List<OutboxEntity>) = synchronized(lock) {
            outboxRowsList = rows
            suppressedSendIDs.retainAll(rows.map { it.localID }.toSet())
        }

        fun setSyncState(state: SyncConnectionState) = synchronized(lock) { syncState = state }

        /// The pending sends `emit()` renders: every outbox row whose
        /// confirming journal row hasn't been seen yet.
        fun visibleSends(): List<OutboxEntity> = synchronized(lock) {
            outboxRowsList.filter { !suppressedSendIDs.contains(it.localID) }
        }

        /// Glyph state for one pending send. `Connecting` covers journal
        /// catch-up on a LIVE socket — the connect-flush has already put
        /// attempted rows on the wire there, so they show `Sending`, not
        /// "waiting to send when online" (bugbot "Queued label while already
        /// on the wire"). A never-attempted row during `Connecting` genuinely
        /// hasn't left, and everything is `Queued` while `Offline` (backoff).
        fun sendState(row: OutboxEntity): TimelineSendState = synchronized(lock) {
            if (row.state == OutboxEntity.STATE_FAILED) return TimelineSendState.Failed("Not delivered")
            when (syncState) {
                is SyncConnectionState.Running -> TimelineSendState.Sending
                is SyncConnectionState.Connecting ->
                    if (row.attempts > 0) TimelineSendState.Sending else TimelineSendState.Queued
                is SyncConnectionState.Offline -> TimelineSendState.Queued
            }
        }

        fun streamingSorted(): List<Pair<String, Streaming>> =
            synchronized(lock) { streaming.toList().sortedBy { it.first } }

        fun toolStreamsSorted(): List<Pair<String, ToolStream>> =
            synchronized(lock) { toolStreams.toList().sortedBy { it.first } }

        fun activitySnapshot(): Activity? = synchronized(lock) { activity }

        /// Maps the cached events through [JournalTimelineMapper], reusing
        /// memoized results (journal events are immutable once written).
        fun mappedItems(ownSender: String, serverURL: HttpUrl): List<TimelineItem> = synchronized(lock) {
            val items = ArrayList<TimelineItem>(eventsList.size)
            for (event in eventsList) {
                val cached = mappedCache[event.seq]
                when {
                    cached != null -> items.add(cached)
                    unmappable.contains(event.seq) -> Unit
                    else -> {
                        val item = JournalTimelineMapper.timelineItem(event, ownSender, serverURL)
                        if (item != null) {
                            mappedCache[event.seq] = item
                            items.add(item)
                        } else {
                            unmappable.add(event.seq)
                        }
                    }
                }
            }
            // Bound the memo: after a mirror wipe the event list shrinks and old
            // seqs may never come back.
            if (mappedCache.size + unmappable.size > eventsList.size + 256) {
                val live = eventsList.map { it.seq }.toSet()
                mappedCache.keys.retainAll(live)
                unmappable.retainAll(live)
            }
            items
        }

        // MARK: Mutations

        /// Applies one tool_stream frame. Returns true when the caller should
        /// re-send `viewing` (missing bytes or missing meta).
        fun applyToolStream(update: ToolStreamUpdate): Boolean = synchronized(lock) {
            val ref = update.messageRef
            if (retiredToolRefs.contains(ref)) return@synchronized false
            when (val e = update.event) {
                is ToolStreamUpdate.Event.Append -> {
                    val chunkBytes = e.chunk.toByteArray(Charsets.UTF_8)
                    val stream = toolStreams[ref]
                    if (stream == null) {
                        if (e.offset != 0) return@synchronized resyncDue(ref) // mid-join
                        toolStreams[ref] = ToolStream(null, null, chunkBytes, 0, false, Instant.now())
                        return@synchronized resyncDue(ref) // appends carry no meta
                    }
                    val end = stream.startOffset + stream.bytes.size
                    when {
                        e.offset == end -> stream.bytes = stream.bytes + chunkBytes
                        e.offset < end -> {
                            val overlap = end - e.offset
                            if (overlap >= chunkBytes.size) return@synchronized false // fully-duplicate
                            stream.bytes = stream.bytes + chunkBytes.copyOfRange(overlap, chunkBytes.size)
                        }
                        else -> return@synchronized resyncDue(ref) // gap
                    }
                    stream.updated = Instant.now()
                    false
                }
                is ToolStreamUpdate.Event.Sync -> {
                    toolStreams[ref] = ToolStream(
                        e.tool, e.command, e.content.toByteArray(Charsets.UTF_8),
                        e.offset, e.headTruncated, Instant.now(),
                    )
                    false
                }
                is ToolStreamUpdate.Event.End -> {
                    // Permanent, like a durable-row retirement: a late/reordered
                    // frame for this ref must not re-create the tile.
                    toolStreams.remove(ref)
                    retire(ref)
                    false
                }
            }
        }

        private fun retire(ref: String) {
            if (retiredToolRefs.contains(ref)) return
            retiredToolRefs.add(ref)
            if (retiredToolRefs.size > 64) retiredToolRefs.removeAt(0)
        }

        private fun resyncDue(ref: String): Boolean {
            val last = resyncRequested[ref]
            if (last != null && Instant.now().toEpochMilli() - last.toEpochMilli() < 2000) return false
            resyncRequested[ref] = Instant.now()
            return true
        }

        fun applyEphemeral(update: EphemeralUpdate) = synchronized(lock) {
            val current = streaming[update.messageRef]?.text ?: ""
            val text = when (val change = update.change) {
                is EphemeralUpdate.Change.Replace -> change.text
                is EphemeralUpdate.Change.Delta -> current + change.text
            }
            streaming[update.messageRef] = Streaming(text, Instant.now())
            Unit
        }

        fun applyActivity(update: ActivityUpdate) = synchronized(lock) {
            val label = JournalTimelineMapper.activityLabel(update.state, update.detail)
            activity = if (label != null) Activity(label, Instant.now()) else null
        }

        /// High-water mark of seqs already walked. Echo retirement must only
        /// react to rows ARRIVING, not to the full list re-walked on every emit.
        fun reconcile(events: List<JournalEvent>, ownSender: String) = synchronized(lock) {
            val newSeqFloor = maxOf(lastReconciledSeq, baselineSeq)
            for (event in events) {
                val ref = event.payload.stringOrNull("message_ref")
                if (ref != null) {
                    streaming.remove(ref)
                    toolStreams.remove(ref)
                    resyncRequested.remove(ref)
                    retire(ref)
                }
                // Finalize de-dup fallback: an agent text row whose body equals a
                // live overlay's accumulated text IS that stream's finalized form.
                if (event.sender != ownSender && event.type == JournalEventType.TEXT) {
                    val body = event.body()
                    if (body != null) {
                        streaming.entries.filter { it.value.text == body }.map { it.key }
                            .forEach { streaming.remove(it) }
                    }
                }
                // Only rows arriving in THIS reconcile (seq > floor) may
                // suppress an echo. The store deletes the outbox row on this
                // same frame (inside applyJournal's transaction) but that
                // delete arrives via a separate observation — suppress the
                // echo HERE, in the same pass that surfaces the row, so they
                // never double-render. `attempts > 0` mirrors
                // outboxDeleteFirstMatching: a never-attempted row can't be
                // the send this row confirms (e.g. the same text sent from
                // another device while this one queued offline) — hiding it
                // here while the store keeps the row would deliver a message
                // the user watched disappear (bugbot "UI suppresses without
                // outbox delete"). Preference mirrors the store's delete:
                // oldest queued copy first (a delivered copy's ack can't
                // retire an undelivered one); when only a failed copy matches,
                // this own-row IS its successful retry landing.
                if (event.seq > newSeqFloor && event.sender == ownSender && event.type == JournalEventType.TEXT) {
                    val body = event.body()
                    if (body != null) {
                        val candidates = outboxRowsList.filter {
                            !suppressedSendIDs.contains(it.localID) && it.body == body && it.attempts > 0
                        }
                        val match = candidates.firstOrNull { it.state == OutboxEntity.STATE_QUEUED }
                            ?: candidates.firstOrNull()
                        if (match != null) suppressedSendIDs.add(match.localID)
                    }
                }
                lastReconciledSeq = maxOf(lastReconciledSeq, event.seq)
            }
            val cutoff = Instant.now().minusMillis(staleness.inWholeMilliseconds)
            streaming.entries.retainAll { it.value.updated.isAfter(cutoff) }
            // Pending sends are deliberately NOT staleness-swept: an outbox
            // row is a durable at-least-once send (2026-07-13 phone incident —
            // a send on a dead socket must never evaporate). It leaves the
            // timeline only via delivery confirmation, explicit discard, or
            // sign-out.
            activity?.let { if (!it.updated.isAfter(cutoff)) activity = null }
            // Tool streams are exempt from the short text cutoff (a quiet build
            // step produces nothing for minutes); their own long staleness backs it.
            val toolCutoff = Instant.now().minusMillis(toolStaleness.inWholeMilliseconds)
            toolStreams.entries.retainAll { it.value.updated.isAfter(toolCutoff) }
            resyncRequested.entries.retainAll { it.value.isAfter(toolCutoff) }
        }

    }

    override fun items(): Flow<List<TimelineItem>> = callbackFlow {
        val serverURL = api.serverURL
        val ticks = Channel<Unit>(Channel.CONFLATED)
        fun signal() { ticks.trySend(Unit) }

        suspend fun emit() {
            val events = overlay.events
            overlay.reconcile(events, ownSender)
            val items = overlay.mappedItems(ownSender, serverURL).toMutableList()
            val lastTS = items.lastOrNull()?.timestamp ?: Instant.now()
            for ((ref, entry) in overlay.streamingSorted()) {
                items.add(JournalTimelineMapper.streamingItem(ref, entry.text, maxInstant(lastTS, entry.updated)))
            }
            for ((ref, stream) in overlay.toolStreamsSorted()) {
                items.add(
                    JournalTimelineMapper.toolStreamItem(
                        messageRef = ref,
                        command = stream.command,
                        text = JournalTimelineMapper.toolStreamText(stream.bytes),
                        headTruncated = stream.headTruncated,
                        convoTS = maxInstant(lastTS, stream.updated),
                    )
                )
            }
            for (row in overlay.visibleSends()) {
                items.add(
                    TimelineItem(
                        id = "echo:${row.localID}",
                        sender = ownSender,
                        timestamp = row.created,
                        kind = TimelineItem.Kind.Text(row.body, null),
                        isOwn = true,
                        sendState = overlay.sendState(row),
                    )
                )
            }
            // Activity indicator sits below every other row, dated to the last
            // row's timestamp so it stays in that row's day bucket.
            overlay.activitySnapshot()?.let { current ->
                val ts = items.lastOrNull()?.timestamp ?: current.updated
                items.add(JournalTimelineMapper.activityItem(current.label, ts))
            }
            send(items)
        }

        // A single consumer performs read-store -> reconcile -> emit strictly
        // serially; conflated ticks coalesce any signals piling up mid-emit.
        val emitJob = launch { for (t in ticks) emit() }
        // Fire viewing concurrently — the local mirror is the source of truth for
        // what to draw, so the first paint isn't held hostage to a round trip.
        val viewingJob = launch { engine.setViewing(convoID) }
        val storeJob = launch {
            // Baseline BEFORE the first flow emission: persisted rows are
            // history and must never retire echoes (see seedBaseline).
            overlay.seedBaseline(runCatching { store.maxSeq(convoID) }.getOrNull() ?: 0L)
            store.eventsFlow(convoID).collect { events ->
                overlay.setEvents(events)
                signal()
            }
            close()
        }
        val ephemeralJob = launch {
            engine.ephemerals(convoID).collect { overlay.applyEphemeral(it); signal() }
        }
        val activityJob = launch {
            engine.activities(convoID).collect { overlay.applyActivity(it); signal() }
        }
        val toolStreamJob = launch {
            engine.toolStreams(convoID).collect {
                // Client-side resync: re-sending viewing makes the server re-emit
                // a full-scrollback sync per active stream.
                if (overlay.applyToolStream(it)) engine.setViewing(convoID)
                signal()
            }
        }
        // Pending sends: the outbox observation delivers the current rows on
        // subscribe (so queued messages survive relaunch / room re-open) and
        // re-fires on enqueue, retry, and delivery-confirmed delete.
        val outboxJob = launch {
            store.outboxFlow(convoID).collect { rows ->
                overlay.setOutbox(rows)
                signal()
            }
        }
        // Connection state drives the queued ("waiting to send") vs sending
        // glyph on pending sends.
        val onlineJob = launch {
            engine.stateStream.collect { state ->
                overlay.setSyncState(state)
                signal()
            }
        }
        // Guarantees a re-emit at least every sweepInterval so reconcile's
        // staleness cutoff always gets a chance to prune a stalled overlay.
        val sweepJob = launch {
            while (isActive) {
                delay(sweepInterval)
                signal()
            }
        }
        awaitClose {
            viewingJob.cancel()
            storeJob.cancel()
            ephemeralJob.cancel()
            activityJob.cancel()
            toolStreamJob.cancel()
            outboxJob.cancel()
            onlineJob.cancel()
            sweepJob.cancel()
            emitJob.cancel()
            ticks.close()
            scope.launch { runCatching { engine.setViewing(null) } }
        }
    }

    override suspend fun sendText(body: String, inReplyTo: String?) {
        val target = inReplyTo?.toLongOrNull()
        if (target != null) {
            engine.sendOp(ClientOp.PromptReply(convoID, target, choice = null, text = body))
            return
        }
        // Durable queue-and-flush: the outbox row IS the local echo (it
        // arrives in `items()` via the outbox observation, as `Sending` when
        // online or `Queued` when not) and survives offline, relaunch, and
        // mirror wipes until the journal frame confirms delivery. Being
        // offline is not an error any more — only a store write failure
        // throws, so the composer can keep the user's text.
        engine.sendMessage(convoID, body, UUID.randomUUID().toString())
    }

    /// Tap-to-retry on a pending/failed own-message: requeues a failed outbox
    /// row and forces a send attempt (or a reconnect nudge when offline).
    /// [itemID] is the echo row's id, `echo:<localID>`.
    override suspend fun retrySend(itemID: String) {
        if (!itemID.startsWith("echo:")) return
        engine.retryOutboxItem(itemID.removePrefix("echo:"))
    }

    /// Removes an unsent (queued or failed) own-message the user chose to
    /// discard. No-op for anything that isn't a pending-send echo.
    override suspend fun discardSend(itemID: String) {
        if (!itemID.startsWith("echo:")) return
        engine.discardOutboxItem(itemID.removePrefix("echo:"))
    }

    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {
        // A prompt's timeline id is its journal seq; anything non-numeric must
        // fail loudly rather than send target_seq 0.
        val targetSeq = inReplyTo.toLongOrNull() ?: throw JournalChatError.InvalidPromptReference(inReplyTo)
        engine.sendOp(
            ClientOp.PromptReply(convoID, targetSeq, choice = selectedValues.joinToString(", "), text = null)
        )
    }

    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) =
        sendMedia(data, filename, mimeType, type = MediaKind.IMAGE, caption = caption, progress = null)

    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) =
        sendMedia(data, filename, mimeType, type = MediaKind.FILE, caption = caption, progress = null)

    override suspend fun sendImage(
        data: ByteArray, filename: String, mimeType: String, caption: String?, progress: ((Double) -> Unit)?,
    ) = sendMedia(data, filename, mimeType, type = MediaKind.IMAGE, caption = caption, progress = progress)

    override suspend fun sendFile(
        data: ByteArray, filename: String, mimeType: String, caption: String?, progress: ((Double) -> Unit)?,
    ) = sendMedia(data, filename, mimeType, type = MediaKind.FILE, caption = caption, progress = progress)

    private suspend fun sendMedia(
        data: ByteArray, filename: String, mimeType: String, type: MediaKind, caption: String?,
        progress: ((Double) -> Unit)?,
    ) {
        val blobRef = api.uploadMedia(data, mimeType, progress)
        engine.sendOp(
            ClientOp.SendMedia(
                convoID = convoID, type = type, blobRef = blobRef, name = filename,
                contentType = mimeType, size = data.size, caption = caption,
                localID = UUID.randomUUID().toString(),
            )
        )
    }

    override suspend fun paginateBackward(requestSize: Int): Boolean {
        val before = store.minSeq(convoID)
        val events = api.messages(convoID, before, requestSize)
        val newOnes = events.filter { before == null || it.seq < before }
        store.insertHistory(newOnes)
        search?.let { s ->
            for (event in newOnes) {
                val body = event.previewText()
                if (!body.isNullOrEmpty()) {
                    runCatching { s.index(event.convoID, event.seq.toString(), event.sender, event.ts, body) }
                }
            }
        }
        return newOnes.isNotEmpty()
    }

    override fun sessionStatus(): Flow<SessionStatusUpdate> = engine.sessionStatus(convoID)

    override fun connectionState(): Flow<SyncConnectionState> = engine.stateStream

    override suspend fun markAsRead() {
        val maxSeq = store.maxSeq(convoID) ?: return
        try {
            engine.sendOp(ClientOp.ReadMarker(convoID, maxSeq))
        } catch (e: JournalSyncError) {
            // Best-effort; the next markAsRead after reconnect converges devices.
            if (e !is JournalSyncError.Offline) throw e
        }
    }
}
