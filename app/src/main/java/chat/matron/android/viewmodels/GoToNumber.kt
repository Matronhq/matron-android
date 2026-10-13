package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.NumberLookup
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// "Go to #N": items, missions, milestones and projects share one per-user
// number space, so the user can type a number and land on whatever it names.
// Everything here is plain Kotlin — parsing, the two-step resolution and the
// dialog's state — so the "Go to number" dialog and the global search share
// one rule set and both are testable without a device.

/// Copy for the dialog and the inline messages; asserted in tests.
object GoToNumberCopy {
    const val TITLE = "Go to number"
    const val FIELD_LABEL = "Number"
    const val GO = "Go"
    /// The confirm button while a lookup is out.
    const val GOING = "Looking…"
    const val CANCEL = "Cancel"
    const val INVALID = "Enter a number, like 412."

    fun notFound(num: Int) = "No #$num"
    fun lookupFailed(num: Int) = "Couldn't look up #$num"
    fun lookupFailed(num: Int, detail: String) = "Couldn't look up #$num — $detail"
    fun cantOpen(num: Int) = "Couldn't open #$num"
    fun cantOpen(num: Int, detail: String) = "Couldn't open #$num — $detail"
    fun project(num: Int) = "#$num is a project. Projects aren't available in this app yet."
    fun missionsUnsupported(num: Int) = "#$num is a mission. Missions aren't available on this server."

    /// A search row's heading: `#412 · Item`.
    fun hitHeading(hit: NumberHit) = "#${hit.num} · ${hit.kind.label}"
}

/// Reads what the user typed as a number: `412` or `#412`, surrounding
/// whitespace allowed. Anything else — words, a sign, a space after the `#`,
/// zero, a value past `Int` — is `null`. Strict on purpose: the global
/// search calls this on every query, and a loose parse would turn ordinary
/// text that merely contains digits into a journal lookup.
object GoToNumberParser {
    fun parse(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (digits.isEmpty() || digits.length > 10 || !digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()?.takeIf { it > 0 }
    }
}

/// What a number turned out to be, before anything is opened. [num] is the
/// number to show and to re-read the local store by (the journal's own, when
/// it names one); [id] is the target's id and [missionID] the mission page
/// a milestone lives on.
data class NumberHit(
    val kind: NumberKind,
    val num: Int,
    val title: String? = null,
    val id: String,
    val missionID: String? = null,
)

/// Where a resolved number opens. A milestone has no page of its own, so it
/// resolves to its mission.
sealed interface GoToTarget {
    data class Item(val itemID: String) : GoToTarget
    data class Mission(val missionID: String) : GoToTarget
}

/// Resolves a number in two steps, so the search can show WHAT a number is
/// without paying to open it:
///
/// 1. [find] — the local store first (offline, instant), then one journal
///    lookup on a miss.
/// 2. [open] — make sure the target is on this device (an item through
///    [TrackerItemLinkResolver], a mission through one `refreshMission`)
///    and say where to navigate, or why not.
///
/// Every dependency is a lambda so tests hand-roll them; [AppDependencies]
/// wires the session's store, API and syncs.
class GoToNumberResolver(
    private val localItem: suspend (Int) -> TrackerItem?,
    private val localMission: suspend (Int) -> Mission?,
    private val lookup: suspend (Int) -> NumberLookup,
    private val resolveItem: suspend (Int) -> TrackerItemLinkResolver.Resolution,
    private val missionCached: suspend (String) -> Boolean,
    private val refreshMission: suspend (String) -> MissionsRefreshOutcome,
    private val missionsSupported: () -> Boolean,
) {
    sealed interface Found {
        data class Hit(val hit: NumberHit) : Found
        /// The journal answered: no such number.
        data object Missing : Found
        /// The journal could not be asked, so the number may well exist.
        data class Failed(val message: String) : Found
    }

    sealed interface Opened {
        data class Target(val target: GoToTarget) : Opened
        /// Stay put and show this inline.
        data class Message(val text: String) : Opened
    }

    suspend fun find(num: Int): Found {
        // A throwing local read is treated as a miss, not a failure: the
        // journal can still answer, and its answer is the authoritative one.
        localRead { localItem(num) }?.let {
            return Found.Hit(NumberHit(NumberKind.ITEM, it.num, it.title.takeIf(String::isNotBlank), it.id))
        }
        localRead { localMission(num) }?.let {
            return Found.Hit(NumberHit(NumberKind.MISSION, it.num, it.title.takeIf(String::isNotBlank), it.id))
        }
        return try {
            val found = lookup(num)
            Found.Hit(NumberHit(found.kind, found.num ?: num, found.title, found.id, found.missionID))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: JournalApiError.NotFound) {
            Found.Missing
        } catch (e: Exception) {
            Found.Failed(e.message?.takeIf { it.isNotBlank() }?.let { GoToNumberCopy.lookupFailed(num, it) } ?: GoToNumberCopy.lookupFailed(num))
        }
    }

    suspend fun open(hit: NumberHit): Opened = when (hit.kind) {
        // By NUMBER through the link resolver, even though the lookup named
        // an id: its refresh is what puts the row on this device, and the
        // detail page reads the row from the store.
        NumberKind.ITEM -> when (val resolution = resolveItem(hit.num)) {
            is TrackerItemLinkResolver.Resolution.Open -> Opened.Target(GoToTarget.Item(resolution.itemID))
            else -> Opened.Message(resolution.alertMessage(hit.num) ?: GoToNumberCopy.cantOpen(hit.num))
        }
        NumberKind.MISSION -> openMission(hit.num, hit.id, named = true)
        NumberKind.MILESTONE -> hit.missionID?.let { openMission(hit.num, it, named = false) }
            ?: Opened.Message(GoToNumberCopy.cantOpen(hit.num))
        NumberKind.PROJECT -> Opened.Message(GoToNumberCopy.project(hit.num))
    }

    /// [named] — the number IS the mission (the unsupported message may say
    /// so); a milestone's number is not.
    private suspend fun openMission(num: Int, missionID: String, named: Boolean): Opened {
        if (!missionsSupported()) {
            return Opened.Message(if (named) GoToNumberCopy.missionsUnsupported(num) else GoToNumberCopy.cantOpen(num))
        }
        if (isCached(missionID)) return Opened.Target(GoToTarget.Mission(missionID))
        return when (val outcome = refreshMission(missionID)) {
            is MissionsRefreshOutcome.Failed -> Opened.Message(GoToNumberCopy.cantOpen(num, outcome.message))
            MissionsRefreshOutcome.Unsupported ->
                Opened.Message(if (named) GoToNumberCopy.missionsUnsupported(num) else GoToNumberCopy.cantOpen(num))
            // The page reads the mission from the store, so a refresh that
            // left nothing there (sign-out mid-pass) has nothing to show.
            MissionsRefreshOutcome.Succeeded, MissionsRefreshOutcome.Stopped ->
                if (isCached(missionID)) {
                    Opened.Target(GoToTarget.Mission(missionID))
                } else {
                    Opened.Message(GoToNumberCopy.cantOpen(num))
                }
        }
    }

    private suspend fun isCached(missionID: String): Boolean = localRead { missionCached(missionID) } == true

    /// `null` on a miss or a throwing read. Cancellation is never swallowed:
    /// a dismissed dialog or a superseded search must abort, not carry on to
    /// a network call nobody is waiting for.
    private suspend fun <T> localRead(read: suspend () -> T?): T? = try {
        read()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        null
    }
}

/// The "Go to number" dialog's state: the typed text, a busy flag while a
/// lookup is out, and the one inline message. [go] returns `true` once
/// [navigate] has run, which is the dialog's cue to dismiss; on anything
/// else the dialog stays open with [message] under the field, so a typo
/// costs one correction rather than a second alert to dismiss.
class GoToNumberViewModel(
    private val resolver: GoToNumberResolver,
    private val navigate: (GoToTarget) -> Unit,
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /// Editing clears the message: it described the text that was there.
    fun setText(value: String) {
        if (value == _text.value) return
        _text.value = value
        _message.value = null
    }

    suspend fun go(): Boolean {
        if (_isBusy.value) return false
        val num = GoToNumberParser.parse(_text.value)
        if (num == null) {
            _message.value = GoToNumberCopy.INVALID
            return false
        }
        _isBusy.value = true
        _message.value = null
        try {
            val hit = when (val found = resolver.find(num)) {
                is GoToNumberResolver.Found.Hit -> found.hit
                GoToNumberResolver.Found.Missing -> return fail(GoToNumberCopy.notFound(num))
                is GoToNumberResolver.Found.Failed -> return fail(found.message)
            }
            return when (val opened = resolver.open(hit)) {
                is GoToNumberResolver.Opened.Target -> { navigate(opened.target); true }
                is GoToNumberResolver.Opened.Message -> fail(opened.text)
            }
        } finally {
            _isBusy.value = false
        }
    }

    private fun fail(text: String): Boolean {
        _message.value = text
        return false
    }
}
