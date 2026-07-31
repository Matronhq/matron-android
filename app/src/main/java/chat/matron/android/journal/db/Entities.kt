package chat.matron.android.journal.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
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
