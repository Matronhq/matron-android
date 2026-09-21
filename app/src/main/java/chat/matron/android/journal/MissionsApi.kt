package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.models.Milestone
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.serialization.json.JsonObject

// The `/missions` routes' request/response shapes (protocol.md "Missions &
// milestones → Routes"). Ported from matron-apple's
// `Journal/JournalAPI+Missions.swift`; the route implementations live on
// [JournalApi] (its request plumbing is private), which implements
// [MissionsProviding].

data class MissionsListQuery(
    /// Omitted means "both states" — the journal has no `state=any`.
    val state: MissionState? = null,
    /// `?since=<ms>`. The journal matches the STORED `updated_at`, so a
    /// hidden milestone can make a mission match; the row that comes back is
    /// fully sieved either way (protocol, "Accepted exception").
    val since: Instant? = null,
) {
    val queryItems: List<Pair<String, String>>
        get() = buildList {
            state?.let { add("state" to it.wire) }
            since?.let { add("since" to it.toEpochMilli().toString()) }
        }
}

/// What `GET /missions/:id` returns. [conversations] has no local equivalent
/// anywhere else — the snapshot never says which conversations a mission
/// owns — so this is the only source for the mission page's chat list.
data class MissionDetail(
    val mission: Mission,
    val milestones: List<Milestone>,
    val items: List<TrackerItem>,
    val conversations: List<MissionConversation>,
)

/// [MissionsDecoding.missions]' result: the rows that decoded, plus the ids
/// of any that didn't. A dropped row's id still names a real, previously
/// cached mission; `MissionsSync` folds [droppedIDs] into the same protected
/// set it keeps detail-refreshed ids in, so a decode failure on this device
/// (a field this build doesn't understand yet, say) can never masquerade as
/// "the server stopped returning it" and get the authoritative replace to
/// delete it.
data class MissionsListDecode(val missions: List<Mission>, val droppedIDs: List<String>)

/// The read surface the apps need, plus the one write they are allowed: a
/// USER close. Creating, joining, renaming and moving items are agent-only
/// (bridge tools) and deliberately absent. [JournalApi] implements it.
interface MissionsProviding {
    suspend fun listMissions(query: MissionsListQuery): MissionsListDecode
    suspend fun mission(id: String): MissionDetail
    suspend fun milestones(convoID: String): List<Milestone>
    suspend fun closeMission(id: String, summary: String): Mission
}

/// The response decoders, separate from the HTTP plumbing so
/// `MissionsApiTest` can pin each shape without a server round trip.
object MissionsDecoding {
    /// Lenient on individual rows on purpose: one malformed row must not
    /// blank the entire list (`items` / `milestones` / `conversations`
    /// already behave this way). The drop is logged, and its id — when the
    /// row has one — is returned in [MissionsListDecode.droppedIDs] so the
    /// caller can protect it from being read as "gone".
    ///
    /// The TOP-LEVEL `missions` key is a different failure mode: absent or
    /// not an array means the response itself is malformed, not merely one
    /// bad row, and an authoritative replace must not treat that as "the
    /// server says there are now zero missions" — so this throws instead of
    /// defaulting to empty. A present-but-empty array is a legitimate "no
    /// missions" answer and still decodes.
    fun missions(obj: JsonObject): MissionsListDecode {
        val rows = obj.arrayOrNull("missions") ?: throw JournalApiError.Transport("malformed missions response")
        val missions = mutableListOf<Mission>()
        val dropped = mutableListOf<String>()
        for (element in rows) {
            val row = element as? JsonObject
            val mission = row?.let(Mission::fromJson)
            if (mission != null) { missions.add(mission); continue }
            val id = row?.stringOrNull("id")
            MatronDebug.breadcrumb("MissionsApi: dropped malformed mission row id=${id ?: "?"} num=${row?.intOrNull("num") ?: "?"}")
            if (id != null) dropped.add(id)
        }
        return MissionsListDecode(missions, dropped)
    }

    fun mission(obj: JsonObject): Mission =
        obj.objectOrNull("mission")?.let(Mission::fromJson) ?: throw JournalApiError.Transport("malformed mission response")

    fun detail(obj: JsonObject): MissionDetail = MissionDetail(
        mission = mission(obj),
        milestones = obj.arrayOrNull("milestones")?.objects()?.mapNotNull(Milestone::fromJson) ?: emptyList(),
        items = obj.arrayOrNull("items")?.objects()?.mapNotNull(TrackerItem::fromJson) ?: emptyList(),
        conversations = obj.arrayOrNull("conversations")?.objects()?.mapNotNull(MissionConversation::fromJson) ?: emptyList(),
    )

    fun milestones(obj: JsonObject): List<Milestone> =
        obj.arrayOrNull("milestones")?.objects()?.mapNotNull(Milestone::fromJson) ?: emptyList()
}
