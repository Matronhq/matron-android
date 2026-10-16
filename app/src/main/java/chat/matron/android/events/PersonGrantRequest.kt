package chat.matron.android.events

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// A person-to-person grant card: one of the user's agents asking to share a
/// mission with a contact, or to hand a mission (or a project) over to one —
/// or, on the other side, a contact offering one. The journal publishes it as
/// a `permission_request` event whose payload carries `kind:
/// "mission_share"` or `kind: "mission_handover"` (protocol.md "Contacts and
/// grants": *Cards, mirrors and who answers*, *Hand-over*).
///
/// Not an `AskUserEvent`: the answer is `POST /grants/:id/answer`, over HTTP,
/// never a `prompt_reply` in the timeline. Only a client device may answer.
///
/// Parsing is lenient: every display field may be missing and the card still
/// draws with a neutral fallback. Only [grantId] is required — without it
/// the card could not be answered, and drawing buttons that would 404 is
/// exactly the dead-tap failure this card replaces.
data class PersonGrantRequest(
    val kind: Kind,
    val direction: Direction,
    val grantId: String,
    /// `read`, `contribute` or `owner` (a hand-over). Null when absent.
    val level: String? = null,
    val ownerName: String? = null,
    val granteeName: String? = null,
    val subjectKind: SubjectKind = SubjectKind.MISSION,
    val subjectTitle: String? = null,
    val subjectNum: Int? = null,
    /// The agent-written hand-over brief (markdown). Null on a share.
    val brief: String? = null,
    val preview: Preview = Preview(),
    /// The asking agent's device name, on the owner's card only.
    val fromName: String? = null,
    /// On a hand-over, what the owner keeps on the recipient's copy
    /// afterwards. [KeepLevel.READ] when the payload does not say.
    val keepLevel: KeepLevel = KeepLevel.READ,
) {
    enum class Kind(val wire: String) {
        SHARE("mission_share"),
        HANDOVER("mission_handover"),
        ;

        companion object {
            fun parse(raw: String?): Kind? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// `out`: the owner's card (their agent asked). `in`: the recipient's.
    enum class Direction(val wire: String) {
        OUT("out"),
        IN("in"),
    }

    enum class SubjectKind(val wire: String) {
        MISSION("mission"),
        PROJECT("project"),
    }

    /// One file the hand-over would copy, as the owner's card lists it.
    data class Attachment(
        val mediaId: String,
        val name: String,
        val mime: String? = null,
        val size: Long? = null,
        val itemNum: Int? = null,
    )

    /// What would cross. Each count is null when the card did not carry it.
    /// On the owner's hand-over card the files are listed ([attachments]);
    /// on every other card only their number is known ([attachmentCount]).
    data class Preview(
        val missions: Int? = null,
        val milestones: Int? = null,
        val items: Int? = null,
        val comments: Int? = null,
        val attachmentCount: Int? = null,
        val attachments: List<Attachment> = emptyList(),
    ) {
        /// "1 mission · 4 milestones · 6 items · 12 comments · 3 files", or
        /// null when the card carried no counts at all.
        val countsLine: String?
            get() {
                val parts = listOfNotNull(
                    missions?.let { plural(it, "mission") },
                    milestones?.let { plural(it, "milestone") },
                    items?.let { plural(it, "item") },
                    comments?.let { plural(it, "comment") },
                    attachmentCount?.let { plural(it, "file") },
                )
                return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
    }

    val isHandover: Boolean get() = kind == Kind.HANDOVER

    private val subjectNoun: String get() = if (subjectKind == SubjectKind.PROJECT) "project" else "mission"

    /// The subject as the headline names it: its title, else "a mission" /
    /// "a project".
    val subjectLabel: String get() = subjectTitle ?: "a $subjectNoun"

    private val granteeLabel: String get() = granteeName ?: "a contact"
    private val ownerLabel: String get() = ownerName ?: "Someone"

    /// The card's title line.
    val title: String
        get() = when (kind) {
            Kind.HANDOVER -> when (direction) {
                Direction.OUT -> "Hand over a $subjectNoun"
                Direction.IN -> "${subjectNoun.replaceFirstChar { it.uppercase() }} hand-over"
            }
            Kind.SHARE -> when (direction) {
                Direction.OUT -> "Share a mission"
                Direction.IN -> "Mission share"
            }
        }

    /// One sentence saying who asks what.
    val headline: String
        get() = when (kind) {
            Kind.HANDOVER -> when (direction) {
                Direction.OUT -> "Your agent asks to hand $subjectLabel to $granteeLabel."
                Direction.IN -> "$ownerLabel wants to hand you $subjectLabel."
            }
            Kind.SHARE -> when (direction) {
                Direction.OUT -> "Your agent asks to share $subjectLabel with $granteeLabel."
                Direction.IN -> "$ownerLabel wants to share $subjectLabel with you."
            }
        }

    /// "can contribute" / "read-only" for a share; null for a hand-over and
    /// for a level this client does not know.
    val levelLabel: String? get() = if (isHandover) null else shareLevelLabel(level)

    /// The approving button's label: the owner approves, the recipient accepts.
    val approveLabel: String get() = if (direction == Direction.OUT) "Approve" else "Accept"

    /// Whether answering this card carries a keep level: only the owner's
    /// hand-over card has the switch (the journal ignores it elsewhere).
    val sendsKeepLevel: Boolean get() = isHandover && direction == Direction.OUT

    /// The recipient's line saying what the owner keeps after the
    /// hand-over; null on every other card.
    val recipientKeepLine: String?
        get() = if (!isHandover || direction != Direction.IN) null else when (keepLevel) {
            KeepLevel.CONTRIBUTE -> "$ownerLabel stays on as a contributor; you can end that any time."
            KeepLevel.READ -> "$ownerLabel keeps read access."
        }

    /// Media ids of the listed files, in card order.
    val attachmentIds: List<String> get() = preview.attachments.map { it.mediaId }

    companion object {
        /// The owner's hint under a hand-over card: a reply on the mirror item
        /// reaches the asking agent (protocol.md *Hand-over*).
        const val OWNER_HINT = "Reply on the tracker item to send a note back to your agent."

        /// Parses a `permission_request` payload, or null if it is not a
        /// mission share or hand-over card with a grant id.
        fun parse(payload: JsonObject): PersonGrantRequest? {
            val kind = Kind.parse(payload.stringOrNull("kind")) ?: return null
            val grantId = nonEmpty(payload.stringOrNull("grant_id")) ?: return null
            val previewObj = payload.objectOrNull("preview")
            val attachmentList = previewObj?.arrayOrNull("attachments")
            val direction = when (payload.stringOrNull("direction")) {
                "out" -> Direction.OUT
                "in" -> Direction.IN
                // No direction: only the owner's card names the asking device
                // or lists the files.
                else -> if (payload.longOrNull("from_device_id") != null || attachmentList != null) Direction.OUT else Direction.IN
            }
            // A hand-over names its subject `subject`; a share, `mission`.
            val subject = payload.objectOrNull("subject") ?: payload.objectOrNull("mission")
            val attachments = attachmentList?.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = nonEmpty(obj.stringOrNull("media_id")) ?: return@mapNotNull null
                Attachment(
                    mediaId = id,
                    name = nonEmpty(obj.stringOrNull("name")) ?: "file",
                    mime = nonEmpty(obj.stringOrNull("mime")),
                    size = obj.longOrNull("size"),
                    itemNum = obj.intOrNull("item_num"),
                )
            }.orEmpty()
            val attachmentCount = when {
                attachmentList != null -> attachments.size
                else -> previewObj?.intOrNull("attachments")
            }
            return PersonGrantRequest(
                kind = kind,
                direction = direction,
                grantId = grantId,
                level = nonEmpty(payload.stringOrNull("level")),
                ownerName = payload.objectOrNull("owner")?.stringOrNull("name")?.let(::nonEmpty),
                granteeName = payload.objectOrNull("grantee")?.stringOrNull("name")?.let(::nonEmpty),
                subjectKind = if (subject?.stringOrNull("kind") == "project") SubjectKind.PROJECT else SubjectKind.MISSION,
                subjectTitle = subject?.stringOrNull("title")?.let(::nonEmpty),
                subjectNum = subject?.intOrNull("num"),
                brief = nonEmpty(payload.stringOrNull("brief")),
                preview = Preview(
                    missions = previewObj?.intOrNull("missions"),
                    milestones = previewObj?.intOrNull("milestones"),
                    items = previewObj?.intOrNull("items"),
                    comments = previewObj?.intOrNull("comments"),
                    attachmentCount = attachmentCount,
                    attachments = attachments,
                ),
                fromName = nonEmpty(payload.stringOrNull("from_name")),
                keepLevel = KeepLevel.parse(payload.stringOrNull("keep_level")),
            )
        }

        /// Whether a `permission_request` payload is one of the
        /// person-to-person kinds (`contact_request`, `mission_share`,
        /// `session_share`, `person_room`, `mission_handover`). None of them
        /// is answered by `prompt_reply`, so none may fall through to the
        /// generic Allow/Deny prompt.
        fun isPersonKind(payload: JsonObject): Boolean = payload.stringOrNull("kind") in PERSON_KINDS

        private val PERSON_KINDS = setOf("contact_request", "mission_share", "session_share", "person_room", "mission_handover")

        private fun nonEmpty(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/// How a share's level reads: `contribute` → "can contribute", `read` →
/// "read-only"; null for anything else.
fun shareLevelLabel(level: String?): String? = when (level) {
    "contribute" -> "can contribute"
    "read" -> "read-only"
    else -> null
}

private fun plural(n: Int, noun: String): String = "$n $noun${if (n == 1) "" else "s"}"

/// The user's answer to a grant card. Both sides send `approve` or
/// `decline` (the recipient's Accept is an `approve`).
enum class GrantDecision(val wire: String) {
    APPROVE("approve"),
    DECLINE("decline"),
}

/// What the owner keeps on the recipient's copy after a hand-over: read
/// access, or the right to keep contributing.
enum class KeepLevel(val wire: String) {
    READ("read"),
    CONTRIBUTE("contribute"),
    ;

    companion object {
        /// Anything but `contribute` (including a missing value) is [READ].
        fun parse(raw: String?): KeepLevel = if (raw == CONTRIBUTE.wire) CONTRIBUTE else READ
    }
}

/// The switch on the owner's hand-over card.
const val KEEP_CONTRIBUTING_LABEL = "Keep contributing after the hand-over"

/// The one-line hint under the switch.
fun keepLevelHint(level: KeepLevel): String = when (level) {
    KeepLevel.READ -> "You keep read access to their copy"
    KeepLevel.CONTRIBUTE -> "You can keep adding milestones, items and comments"
}

/// The body of `POST /grants/:id/answer`. [excludeMedia] — the files the
/// owner unticked on a hand-over — rides only on an approval, and only when
/// it names at least one file. [keepLevel] — the owner's switch — rides on
/// every approval that passes one, and never on a decline.
fun grantAnswerBody(
    decision: GrantDecision,
    excludeMedia: List<String> = emptyList(),
    keepLevel: KeepLevel? = null,
): JsonObject = buildJsonObject {
    put("decision", decision.wire)
    if (decision == GrantDecision.APPROVE && excludeMedia.isNotEmpty()) {
        put("exclude_media", buildJsonArray { excludeMedia.forEach { add(JsonPrimitive(it)) } })
    }
    if (decision == GrantDecision.APPROVE && keepLevel != null) {
        put("keep_level", keepLevel.wire)
    }
}

/// Where one grant card is in its life. An answer made on this device is
/// remembered locally ([Answered]); a 404/409 means it was answered elsewhere
/// or has expired ([Unavailable]).
sealed class GrantCardState {
    data object Idle : GrantCardState()
    data object Sending : GrantCardState()
    data class Answered(val approved: Boolean) : GrantCardState()
    data class Failed(val message: String) : GrantCardState()
    data object Unavailable : GrantCardState()
}

/// The resolved line under an answered card.
fun grantAnsweredText(request: PersonGrantRequest, approved: Boolean): String = when {
    !approved -> "Declined"
    request.direction == PersonGrantRequest.Direction.IN -> "Accepted"
    else -> request.granteeName?.let { "Approved — waiting for $it to accept" } ?: "Approved"
}
