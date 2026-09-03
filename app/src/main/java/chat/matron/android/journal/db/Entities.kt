package chat.matron.android.journal.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject

/// Local mirror of one conversation summary. Table/column names match the
/// matron-apple GRDB schema exactly so the two clients describe the same store.
@Entity(tableName = "conversation", indices = [Index("parent_convo_id")])
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "session_state") val sessionState: String,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
    val snippet: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_activity_ts") val lastActivityTS: Long?,
    val muted: Boolean,
    val hidden: Boolean,
    @ColumnInfo(name = "read_up_to_seq") val readUpToSeq: Long,
    @ColumnInfo(name = "unread_count") val unreadCount: Int,
    /// Parent conversation id for a subagent child, else `null`. Set once at
    /// row creation and never repointed (server-side immutable). Drives the
    /// chat-list filter (`parent_convo_id IS NULL`) and `children(of:)`.
    @ColumnInfo(name = "parent_convo_id") val parentConvoID: String?,
    /// The agent box (journal device id) that manages this conversation, or
    /// `null` when the server has never recorded one (a row predating the
    /// column, or a server predating the field). Unlike [parentConvoID] this
    /// is mutable — resuming a session on another box legitimately repoints
    /// it. Drives the box chip in the chat list and header.
    @ColumnInfo(name = "agent_device_id") val agentDeviceID: Long? = null,
    /// JSON-encoded `[Long]` of every box in a multi-agent room (owner +
    /// joined participants, journal-ordered), else `null`. Stored as text so
    /// the column stays a plain additive migration; read through
    /// [participantIDs]. Replaced wholesale when the wire sends the key,
    /// untouched when it doesn't (see `ConvoSummaryDTO.participants`).
    val participants: String? = null,
) {
    /// Decoded [participants]. Empty for anything that is not a known
    /// multi-agent room (null column, or a value that fails to decode).
    val participantIDs: List<Long>
        get() {
            val raw = participants ?: return emptyList()
            return runCatching {
                MatronJson.decodeFromString(ListSerializer(Long.serializer()), raw)
            }.getOrElse { emptyList() }
        }

    companion object {
        fun encodeParticipants(ids: List<Long>): String? =
            runCatching {
                MatronJson.encodeToString(ListSerializer(Long.serializer()), ids)
            }.getOrNull()
    }
}

/// One of the user's agent boxes, id → name — the local mirror of the server's
/// `agents` snapshot list (see `JournalStore.replaceAgents`) plus live
/// `device_meta` renames. The chat list joins conversations against this to
/// label rows, and its COUNT is the "does this user have ≥2 boxes" chip gate.
@Entity(tableName = "agent")
data class AgentEntity(
    @PrimaryKey
    val id: Long,
    val name: String,
    /// User-chosen roster tag character, journal-held so every device shows
    /// the same letter (apple #158). NULL = automatic (derived from the name).
    @ColumnInfo(name = "tag_char") val tagChar: String? = null,
)

/// One durable journal row. `payload` is stored as a JSON TEXT string (the
/// Apple original uses a BLOB; TEXT is the decided Android representation).
@Entity(tableName = "event", indices = [Index("convo_id")])
data class EventEntity(
    @PrimaryKey
    val seq: Long,
    @ColumnInfo(name = "convo_id") val convoID: String,
    val ts: Long,
    val sender: String,
    val type: String,
    val payload: String,
) {
    fun toJournalEvent(): JournalEvent = JournalEvent(
        seq = seq,
        convoID = convoID,
        ts = Instant.ofEpochMilli(ts),
        sender = sender,
        type = type,
        payload = parseJsonObjectOrNull(payload) ?: JsonObject(emptyMap()),
    )

    companion object {
        fun from(event: JournalEvent): EventEntity = EventEntity(
            seq = event.seq,
            convoID = event.convoID,
            ts = event.ts.toEpochMilli(),
            sender = event.sender,
            type = event.type,
            payload = event.payload.toString(),
        )
    }
}

/// One TOC entry per bridge summary pass. Derived from `summary` journal
/// events; the event's own seq is the transcript anchor. Table/column names
/// match the matron-apple GRDB schema (`summary_entry`, added there in v4).
@Entity(tableName = "summary_entry", primaryKeys = ["convo_id", "seq"], indices = [Index("convo_id")])
data class SummaryEntryEntity(
    @ColumnInfo(name = "convo_id") val convoID: String,
    val seq: Long,
    val toc: String,
    val detail: String,
    /// Milliseconds since epoch, like every other Long timestamp column here.
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        /// `null` unless [event] is a `summary` frame with a usable (non-empty)
        /// `toc` — the same accept/skip contract as the Apple
        /// `SummaryEntryRecord(event:)` failable init.
        fun from(event: JournalEvent): SummaryEntryEntity? {
            if (event.type != JournalEventType.SUMMARY) return null
            val toc = event.payload.stringOrNull("toc")?.takeIf { it.isNotEmpty() } ?: return null
            return SummaryEntryEntity(
                convoID = event.convoID,
                seq = event.seq,
                toc = toc,
                detail = event.payload.stringOrNull("detail") ?: "",
                createdAt = event.ts.toEpochMilli(),
            )
        }
    }
}

/// Key/value scalar store (holds the sync cursor). Value is TEXT and parsed by
/// callers, matching GRDB's `Int64.fetchOne` coercion of a TEXT column.
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey
    val key: String,
    val value: String,
)

/// One unsent text message in the offline send queue. Rows are created by
/// `JournalSyncEngine.sendMessage`, flushed FIFO on (re)connect with the same
/// `local_id` every attempt (the server folds it into the row's idem_key, so
/// at-least-once resends are dedup-safe — protocol.md "Publishes and sends are
/// at-least-once"), and deleted only when the own-text journal frame confirming
/// delivery is applied. Table/column names match the matron-apple GRDB schema.
@Entity(tableName = "outbox", indices = [Index("convo_id")])
data class OutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_id") val localID: String,
    @ColumnInfo(name = "convo_id") val convoID: String,
    val body: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /// [STATE_QUEUED] (waiting for a connection / the next flush pass) or
    /// [STATE_FAILED] (rejected or given up — resent only via explicit retry).
    val state: String,
    val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
) {
    val created: Instant get() = Instant.ofEpochMilli(createdAt)

    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_FAILED = "failed"
    }
}
