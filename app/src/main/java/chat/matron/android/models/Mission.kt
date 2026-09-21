package chat.matron.android.models

import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

// Missions & milestones model layer (spec
// `2026-09-10-missions-milestones-design.md`, protocol.md "## Missions &
// milestones"). Ported from matron-apple's `Models/Mission.swift`. Wire
// decoding is `fromJson` on each type (the Swift failable `init?(json:)`): a
// row missing a required key, or carrying an enum value this client doesn't
// know, decodes to `null` and is skipped by the caller — never a crash, never
// a half-built row.

/// A mission's lifecycle. Mirrors the journal's `missions.state` CHECK.
enum class MissionState(val wire: String) {
    OPEN("open"), CLOSED("closed");

    companion object {
        fun fromWire(raw: String?): MissionState? = entries.firstOrNull { it.wire == raw }
    }
}

/// Why a milestone was posted. `user_input` is the one that answers Dan's
/// stated pain ("get back to my last input"); `progress` is the agent's own
/// checkpoint and has no cap.
enum class MilestoneKind(val wire: String) {
    USER_INPUT("user_input"), PROGRESS("progress");

    companion object {
        fun fromWire(raw: String?): MilestoneKind? = entries.firstOrNull { it.wire == raw }
    }
}

private fun msInstant(ms: Long?): Instant? = ms?.let { Instant.ofEpochMilli(it) }

/// The `last_milestone` summary the journal attaches to each `GET /missions`
/// row — enough for the list row without a second fetch. For a filtered
/// (ordinary agent) caller the journal sieves this; for a client device it is
/// the real newest one.
data class MissionLastMilestone(
    val num: Int,
    val title: String,
    val kind: MilestoneKind,
    val createdAt: Instant,
) {
    companion object {
        fun fromJson(json: JsonObject): MissionLastMilestone? {
            val num = json.intOrNull("num") ?: return null
            val kind = MilestoneKind.fromWire(json.stringOrNull("kind")) ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            return MissionLastMilestone(num = num, title = json.stringOrNull("title") ?: "", kind = kind, createdAt = createdAt)
        }
    }
}

/// One mission — the human-readable record of a piece of work, numbered from
/// the same per-user counter as items and milestones (`#61` names exactly one
/// thing). `idem_key` is internal to the journal and never on the wire.
data class Mission(
    val id: String,
    val num: Int,
    val state: MissionState = MissionState.OPEN,
    val title: String,
    val body: String = "",
    val closeSummary: String? = null,
    val closedBy: ItemAuthor? = null,
    /// Count of items still open when a user forced the close. Includes
    /// items this caller cannot see (protocol, "Accepted exception —
    /// numbers, never words"), so it can exceed [openItems].
    val closedOverOpenItems: Int = 0,
    val originConvoID: String,
    val originDeviceID: Long = 0,
    val createdBy: ItemAuthor = ItemAuthor.AGENT,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    /// The list's sort key. `null` for a mission with no milestones yet.
    val lastMilestoneAt: Instant? = null,
    val closedAt: Instant? = null,
    // Counts, present only on `GET /missions` rows; zero elsewhere.
    val openItems: Int = 0,
    val needsYou: Int = 0,
    val conversationCount: Int = 0,
    val milestoneCount: Int = 0,
    val lastMilestone: MissionLastMilestone? = null,
) {
    /// What the mission is called wherever a number alone would be opaque.
    val label: String get() = "#$num $title"

    companion object {
        fun fromJson(json: JsonObject): Mission? {
            val id = json.stringOrNull("id") ?: return null
            val num = json.intOrNull("num") ?: return null
            val state = MissionState.fromWire(json.stringOrNull("state")) ?: return null
            val title = json.stringOrNull("title") ?: return null
            val origin = json.stringOrNull("origin_convo_id") ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            val updatedAt = msInstant(json.longOrNull("updated_at")) ?: return null
            return Mission(
                id = id, num = num, state = state, title = title, body = json.stringOrNull("body") ?: "",
                closeSummary = json.stringOrNull("close_summary"),
                closedBy = ItemAuthor.fromWire(json.stringOrNull("closed_by")),
                closedOverOpenItems = json.intOrNull("closed_over_open_items") ?: 0,
                originConvoID = origin, originDeviceID = json.longOrNull("origin_device_id") ?: 0,
                createdBy = ItemAuthor.fromWire(json.stringOrNull("created_by")) ?: ItemAuthor.AGENT,
                createdAt = createdAt, updatedAt = updatedAt,
                lastMilestoneAt = msInstant(json.longOrNull("last_milestone_at")),
                closedAt = msInstant(json.longOrNull("closed_at")),
                openItems = json.intOrNull("open_items") ?: 0,
                needsYou = json.intOrNull("needs_you") ?: 0,
                conversationCount = json.intOrNull("conversations") ?: 0,
                milestoneCount = json.intOrNull("milestones") ?: 0,
                lastMilestone = json.objectOrNull("last_milestone")?.let(MissionLastMilestone::fromJson),
            )
        }
    }
}

/// One checkpoint. [seq] is the anchor: the `milestone` marker event's own
/// seq in [convoID], and the only way back to where it happened.
data class Milestone(
    val id: String,
    val missionID: String,
    val num: Int,
    val kind: MilestoneKind,
    val title: String,
    val body: String = "",
    val convoID: String,
    val seq: Long,
    val deviceID: Long = 0,
    val createdBy: ItemAuthor = ItemAuthor.AGENT,
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        fun fromJson(json: JsonObject): Milestone? {
            val id = json.stringOrNull("id") ?: return null
            val missionID = json.stringOrNull("mission_id") ?: return null
            val num = json.intOrNull("num") ?: return null
            val kind = MilestoneKind.fromWire(json.stringOrNull("kind")) ?: return null
            val title = json.stringOrNull("title") ?: return null
            val convoID = json.stringOrNull("convo_id") ?: return null
            // No seq, no anchor — and a milestone with no anchor is worse
            // than none (spec, "Milestone anchor").
            val seq = json.longOrNull("seq") ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            return Milestone(
                id = id, missionID = missionID, num = num, kind = kind, title = title,
                body = json.stringOrNull("body") ?: "", convoID = convoID, seq = seq,
                deviceID = json.longOrNull("device_id") ?: 0,
                createdBy = ItemAuthor.fromWire(json.stringOrNull("created_by")) ?: ItemAuthor.AGENT,
                createdAt = createdAt,
            )
        }
    }
}

/// A conversation belonging to a mission, as `GET /missions/:id` returns it.
/// Not a `ChatSummary`: it carries only what the mission page shows, and its
/// rows can name conversations this device has never synced.
data class MissionConversation(
    val id: String,
    val title: String,
    val box: String?,
    val state: String,
) {
    companion object {
        fun fromJson(json: JsonObject): MissionConversation? {
            val id = json.stringOrNull("id") ?: return null
            return MissionConversation(
                id = id, title = json.stringOrNull("title") ?: "", box = json.stringOrNull("box"),
                state = json.stringOrNull("state") ?: "",
            )
        }
    }
}

/// The halves `SessionTagText.run` and `SessionTagText.room` need, carried
/// as one value so a leaf view can draw a conversation's tag without
/// reaching for a store. Any half may be missing: single-box users have no
/// letter (the same gate `BoxChip` uses), and seed titles / pre-#224
/// conversations have no session short. [roomBoxNames]/[roomBoxShorts] are
/// parallel arrays (mirroring `ChatSummary.roomBoxNames`/`roomBoxShorts`),
/// empty unless the conversation is a multi-agent room with ≥2 distinct
/// boxes — the same gate `JournalChatService.roomTags` applies, so a caller
/// tries `SessionTagText.room` first (falling back to `.run`) exactly as the
/// chat list does. A `null` `SessionTagInputs` means "no tag at all" — never
/// an empty placeholder. Ported from matron-apple's `SessionTagInputs`.
data class SessionTagInputs(
    val boxLetter: String?,
    val boxName: String?,
    val sessionShort: String?,
    val roomBoxNames: List<String> = emptyList(),
    val roomBoxShorts: List<String> = emptyList(),
)
