package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineRules
import kotlinx.serialization.json.JsonObject

// The `/routines` routes' shapes (protocol "Coordinator routines"). The
// routes live on [JournalApi] (its request plumbing is private), which
// implements [RoutinesProviding]; the decoders and the status mapping are
// pure so `RoutinesApiTest` can pin each one without a server. Ported from
// matron-apple's `JournalAPI+Routines.swift`.

/// Why a routines request failed, in the words the Routines screen shows.
/// [Unsupported] is the journal answering 404 on the collection — it
/// predates `/routines` — which the screen shows as its own empty state.
sealed class RoutinesError(message: String) : Exception(message) {
    data object Unsupported : RoutinesError("This journal doesn't have routines yet. Update the journal to use them.")

    /// 409 `blocked_by: name`: a create whose name is taken.
    data object NameTaken : RoutinesError("A routine with that name already exists. Choose another name.")

    /// 409 `blocked_by: cap`: a create past the journal's per-user cap.
    data object TooMany : RoutinesError(
        "You already have ${RoutineRules.MAX_ROUTINES} routines, the most the journal keeps. Delete one first.",
    )

    /// 404 on one routine: deleted elsewhere since the list loaded.
    data object NotFound : RoutinesError("That routine no longer exists. It may have been deleted on another device.")

    /// 400: the journal refused a field. It doesn't say which, so the
    /// message names the rules the editor can't check itself.
    data object Rejected : RoutinesError(
        "The journal refused that routine. A schedule must fire at least ${RoutineRules.MIN_GAP_MINUTES} minutes apart " +
            "and keep firing, the zone must be a real time zone, and a routine has either a schedule or a trigger, never both.",
    )

    /// 403 `approval_required`: the account has an approval key and the
    /// journal wants this change signed with it. This build cannot sign yet,
    /// so it says so instead of failing without a reason.
    data object ApprovalRequired : RoutinesError(
        "This change needs approval with your approval key, and this app can't sign approvals yet. " +
            "Make the change from a device that can.",
    )

    class Other(message: String) : RoutinesError(message)
}

/// `POST /routines/:key/run`'s answer: fired, or why not.
sealed interface RoutineRunResult {
    data object Accepted : RoutineRunResult

    /// `no_coordinator`, `busy`, `no_firer`, or a fire outcome.
    data class NotDelivered(val reason: String) : RoutineRunResult

    /// What to tell the user, `null` once it was accepted.
    val message: String?
        get() = when (this) {
            Accepted -> null
            is NotDelivered -> when (reason) {
                "no_coordinator" -> "Not sent: there is no Coordinator to run it."
                "busy" -> "Not sent: the journal is busy firing other routines. Try again in a moment."
                else -> "Not sent ($reason)."
            }
        }
}

/// The journal routines surface the app uses. The app writes with its
/// client token, so no `convo_id`.
interface RoutinesProviding {
    suspend fun listRoutines(): List<Routine>

    /// `POST /routines`. Throws [RoutinesError.NameTaken] / [RoutinesError.TooMany] on 409.
    suspend fun createRoutine(name: String, draft: RoutineDraft): Routine

    /// `PATCH /routines/:key`.
    suspend fun updateRoutine(id: String, draft: RoutineDraft): Routine

    /// `PATCH /routines/:key` with `enabled` alone: pause or resume.
    suspend fun setRoutineEnabled(id: String, enabled: Boolean): Routine

    /// `DELETE /routines/:key`.
    suspend fun deleteRoutine(id: String)

    /// `POST /routines/:key/run` (202).
    suspend fun runRoutine(id: String): RoutineRunResult
}

object RoutinesDecoding {
    /// Lenient on single rows (a trigger kind this build doesn't know is
    /// logged and dropped); a missing `routines` key is malformed.
    fun routines(obj: JsonObject): List<Routine> {
        val rows = obj.arrayOrNull("routines") ?: throw RoutinesError.Other("The journal sent a malformed routines list.")
        return rows.objects().mapNotNull { row ->
            Routine.fromJson(row).also {
                if (it == null) MatronDebug.breadcrumb("RoutinesApi: dropped malformed routine row id=${row.stringOrNull("id") ?: "?"}")
            }
        }
    }

    fun routine(obj: JsonObject): Routine =
        obj.objectOrNull("routine")?.let(Routine::fromJson) ?: throw RoutinesError.Other("The journal sent a malformed routine.")

    fun runResult(obj: JsonObject): RoutineRunResult =
        if (obj.boolOrNull("accepted") == true) RoutineRunResult.Accepted
        else RoutineRunResult.NotDelivered(obj.stringOrNull("reason") ?: "unknown")

    /// The statuses the routines contract names become [RoutinesError]; the
    /// rest keep the shared mapping's wording ([fallback]). `404` means "no
    /// such route" on the collection and "no such routine" on one.
    fun error(status: Int, data: ByteArray, collection: Boolean, fallback: () -> String): RoutinesError {
        val obj = parseJsonObjectOrNull(String(data, Charsets.UTF_8))
        return when {
            status == 404 -> if (collection) RoutinesError.Unsupported else RoutinesError.NotFound
            status == 409 && obj?.stringOrNull("blocked_by") == "cap" -> RoutinesError.TooMany
            status == 409 -> RoutinesError.NameTaken
            status == 400 && obj?.stringOrNull("error")?.startsWith("approval") == true ->
                RoutinesError.Other("The journal refused the approval for this change. Try again.")
            status == 400 -> RoutinesError.Rejected
            status == 403 && obj?.stringOrNull("error") == "approval_required" -> RoutinesError.ApprovalRequired
            else -> RoutinesError.Other(fallback())
        }
    }
}
