package chat.matron.android.events

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.MilestoneKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/// The `milestone` journal event (protocol: "Marker events"). Its own `seq`
/// is the anchor — the row that renders it IS the jump target — so the
/// mapper keeps the event seq alongside it.
///
/// [missionTitle] is OPTIONAL on purpose. The journal drops it at write time
/// whenever the mission's origin conversation is private-owned and the
/// conversation being written to is not, so an ordinary agent replaying that
/// conversation never reads the private mission's name. [missionNum] always
/// survives; render [missionLabel], never `missionTitle ?: ""`. Ported from
/// matron-apple's `Events/MissionMarkerEvent.swift`.
data class MilestoneMarkerEvent(
    val milestoneID: String,
    val num: Int,
    val kind: MilestoneKind,
    val title: String,
    val body: String = "",
    val missionID: String,
    val missionNum: Int,
    val missionTitle: String? = null,
    val by: ItemAuthor = ItemAuthor.AGENT,
) {
    /// How to name the mission in the UI: its title when the marker carried
    /// one, otherwise its number. Never an empty string.
    val missionLabel: String get() = missionTitle?.takeIf { it.isNotEmpty() } ?: "#$missionNum"

    companion object {
        fun parse(payload: JsonObject): MilestoneMarkerEvent? {
            val milestoneID = payload.stringOrNull("milestone_id") ?: return null
            val num = payload.intOrNull("num") ?: return null
            val kind = MilestoneKind.fromWire(payload.stringOrNull("kind")) ?: return null
            val title = payload.stringOrNull("title") ?: return null
            val missionID = payload.stringOrNull("mission_id") ?: return null
            val missionNum = payload.intOrNull("mission_num") ?: return null
            val by = ItemAuthor.fromWire(payload.stringOrNull("by")) ?: return null
            return MilestoneMarkerEvent(
                milestoneID = milestoneID, num = num, kind = kind, title = title,
                body = payload.stringOrNull("body") ?: "", missionID = missionID, missionNum = missionNum,
                missionTitle = payload.stringOrNull("mission_title"), by = by,
            )
        }
    }
}

/// The `mission` journal event. Purely an invalidation signal plus a
/// one-line inline notice — the apps re-read the mission over HTTP rather
/// than trusting anything here beyond the number and the action.
data class MissionMarkerEvent(
    val missionID: String,
    val num: Int,
    /// Optional for the same boundary reason as [MilestoneMarkerEvent.missionTitle].
    val title: String? = null,
    val action: Action,
    val by: ItemAuthor = ItemAuthor.AGENT,
    /// Only on a user-forced close: the numbers of items still open at the
    /// time, hidden ones included (the user's own record of their override).
    val openItemNums: List<Int> = emptyList(),
) {
    enum class Action(val wire: String) {
        CREATED("created"), JOINED("joined"), UPDATED("updated"), CLOSED("closed");

        companion object {
            fun fromWire(raw: String?): Action? = entries.firstOrNull { it.wire == raw }
        }
    }

    val missionLabel: String get() = title?.takeIf { it.isNotEmpty() } ?: "#$num"

    companion object {
        fun parse(payload: JsonObject): MissionMarkerEvent? {
            val missionID = payload.stringOrNull("mission_id") ?: return null
            val num = payload.intOrNull("num") ?: return null
            val action = Action.fromWire(payload.stringOrNull("action")) ?: return null
            val by = ItemAuthor.fromWire(payload.stringOrNull("by")) ?: return null
            return MissionMarkerEvent(
                missionID = missionID, num = num, title = payload.stringOrNull("title"), action = action, by = by,
                openItemNums = payload.arrayOrNull("open_item_nums")
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList(),
            )
        }
    }
}

/// Both marker types on one stream — `MissionsSync` reacts to either by
/// refetching the same mission, so a single feed keeps the engine's
/// publishing site and the sync's subscription simple.
sealed interface MissionMarker {
    val missionID: String

    data class Mission(val event: MissionMarkerEvent) : MissionMarker {
        override val missionID: String get() = event.missionID
    }

    data class Milestone(val event: MilestoneMarkerEvent) : MissionMarker {
        override val missionID: String get() = event.missionID
    }
}
