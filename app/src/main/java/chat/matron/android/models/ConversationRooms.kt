package chat.matron.android.models

import chat.matron.android.chat.SessionTag
import chat.matron.android.journal.db.ConversationEntity
import java.time.Instant

/// An agent-chat room as the rooms surfaces see it: the room conversation
/// plus the conversations taking part in it (each participant agent's own
/// session, journal-ordered). Only rooms whose participant conversations are
/// known are ever built. Port of matron-apple's `MissionRoom`.
data class MissionRoom(
    val id: String,
    /// The room's title, session short and room marker already peeled off.
    val title: String,
    /// The store's `session_state` for the room.
    val sessionState: String,
    val lastActivity: Instant?,
    val participantConvoIDs: List<String>,
) {
    companion object {
        /// Stored room rows → rooms: rows whose participant list is empty or
        /// unreadable dropped, titles cleaned (an empty clean title falls
        /// back to the id). Activity is `last_activity_ts`, else
        /// `created_at` when set.
        fun fromRows(rows: List<ConversationEntity>): List<MissionRoom> = rows.mapNotNull { row ->
            val ids = row.participantConvoIDs
            if (ids.isEmpty()) return@mapNotNull null
            val title = roomTitle(row.title)
            val activity = row.lastActivityTS ?: row.createdAt.takeIf { it > 0 }
            MissionRoom(
                id = row.id,
                title = title.ifEmpty { row.id },
                sessionState = row.sessionState,
                lastActivity = activity?.let(Instant::ofEpochMilli),
                participantConvoIDs = ids,
            )
        }

        /// A stored conversation title as the rooms surfaces draw it: the
        /// session short and the room marker peeled off.
        fun roomTitle(stored: String): String =
            SessionTag.titleBesideRoomTag(SessionTag.splitTitle(stored).second)
    }
}

/// Running green / waiting amber / done grey: a room's `session_state`
/// as its dot reads it. Anything that is not `running` or `done` reads as
/// waiting, the store's own default. Port of `DashboardSessionState`.
enum class RoomSessionState {
    RUNNING, WAITING, DONE;

    val label: String
        get() = when (this) {
            RUNNING -> "Running"
            WAITING -> "Waiting"
            DONE -> "Done"
        }

    companion object {
        fun from(sessionState: String): RoomSessionState = when (sessionState) {
            "running" -> RUNNING
            "done" -> DONE
            else -> WAITING
        }
    }
}

/// One agent-chat room a conversation takes part in, as that conversation's
/// own header lists it ("Rooms · n"). The title is the room's, clean.
data class ConversationRoom(
    val id: String,
    val title: String,
    val state: RoomSessionState,
)

/// Which rooms a conversation lists: every room it is a participant of —
/// the starter and everyone who joined alike — newest activity first
/// (never-active last, then id for a stable order). A room is never a room
/// of itself. Port of matron-apple's `ConversationRoomsRule`.
object ConversationRoomsRule {
    fun rooms(of: String, among: List<MissionRoom>): List<ConversationRoom> =
        among.filter { it.id != of && of in it.participantConvoIDs }
            .sortedWith(
                compareBy<MissionRoom, Instant?>(nullsLast(reverseOrder())) { it.lastActivity }
                    .thenBy { it.id },
            )
            .map { ConversationRoom(it.id, it.title, RoomSessionState.from(it.sessionState)) }
}
