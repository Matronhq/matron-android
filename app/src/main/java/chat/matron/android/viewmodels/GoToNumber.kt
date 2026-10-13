package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.NumberLookup
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    /// The confirm button while Go is resolving and opening.
    const val GOING = "Looking…"
    const val CANCEL = "Cancel"
    /// Read out for the progress mark while a typed number is looked up.
    const val LOOKING_UP = "Looking up"
    const val PROJECT_NOTE = "Projects aren't available in this app yet."

    fun nothingNumbered(num: Int) = "Nothing is numbered $num."
    fun lookupFailed(num: Int) = "Couldn't look up #$num. Try again in a moment."
    fun cantOpen(num: Int) = "Couldn't open #$num"
    fun cantOpen(num: Int, detail: String) = "Couldn't open #$num — $detail"
    fun project(num: Int) = "#$num is a project. $PROJECT_NOTE"
    fun missionsUnsupported(num: Int) = "#$num is a mission. Missions aren't available on this server."

    /// A search row's heading: `#412 · Item`.
    fun hitHeading(hit: NumberHit) = "#${hit.num} · ${hit.kind.label}"

    /// The dialog's result row: the kind, then the number.
    fun rowHeading(hit: NumberHit) = "${hit.kind.label} #${hit.num}"
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
            Found.Failed(GoToNumberCopy.lookupFailed(num))
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

/// What the dialog shows under the field for the number typed. [asked] is
/// the number the answer is FOR, which is not always the one the hit shows
/// (the journal may name another), so it is what a later edit is compared to.
sealed interface GoToNumberResult {
    val asked: Int

    /// Something carries the number: the row to tap.
    data class Hit(override val asked: Int, val hit: NumberHit) : GoToNumberResult {
        /// A project shows what it is but has no page here to open.
        val canOpen: Boolean get() = hit.kind != NumberKind.PROJECT
    }

    /// The journal answered: nothing carries it. Information, not an error.
    data class Missing(override val asked: Int) : GoToNumberResult

    /// The journal could not be asked. Go asks again.
    data class Failed(override val asked: Int, val message: String) : GoToNumberResult
}

/// The "Go to number" dialog's state. The number is looked up AS it is
/// typed: the dialog calls [lookUp] on every text change (cancelling the
/// call before it), and [result] fills in under the field once the text has
/// settled, so the user sees what a number is before choosing to open it.
///
/// [go] opens the result, resolving on the spot when the debounced lookup
/// has not answered yet, and returns `true` once [navigate] has run — the
/// dialog's cue to dismiss. A target that will not open leaves the dialog up
/// with [message] under the row.
///
/// [sleep] is the debounce wait, injectable so tests need no real time.
class GoToNumberViewModel(
    private val resolver: GoToNumberResolver,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val navigate: (GoToTarget) -> Unit,
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /// `null` while the text is not a number, or not answered yet.
    private val _result = MutableStateFlow<GoToNumberResult?>(null)
    val result: StateFlow<GoToNumberResult?> = _result.asStateFlow()

    /// A typed number is waiting out the debounce or its lookup.
    private val _isLooking = MutableStateFlow(false)
    val isLooking: StateFlow<Boolean> = _isLooking.asStateFlow()

    /// Go is resolving and opening.
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    /// Why the result would not open.
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /// Bumped whenever an in-flight [lookUp] stops being the one to listen
    /// to: an edit, a newer lookup, or Go taking over.
    private var generation = 0

    val canGo: Boolean get() = canGo(_text.value, _result.value, _isBusy.value)

    /// Editing drops whatever described the text that was there. A result
    /// survives only an edit that leaves the number itself unchanged.
    fun setText(value: String) {
        if (value == _text.value) return
        _text.value = value
        _message.value = null
        supersedeLookups()
        if (_result.value?.asked != GoToNumberParser.parse(value)) _result.value = null
    }

    /// Looks the typed number up once the text has stayed put for
    /// [debounceMs]. Text that is not a number shows nothing at all. An
    /// answer is applied only while it is still the newest lookup, so a late
    /// one for a number since edited away never shows — whether or not the
    /// caller cancelled it.
    suspend fun lookUp() {
        val num = GoToNumberParser.parse(_text.value) ?: return
        if (_isBusy.value || _result.value?.asked == num) return
        val mine = ++generation
        _isLooking.value = true
        try {
            sleep(debounceMs)
            // Superseded while waiting: nobody wants the answer, so the
            // journal is not asked for it.
            if (mine != generation) return
            val found = resolver.find(num)
            if (mine == generation) _result.value = found.asResult(num)
        } finally {
            if (mine == generation) _isLooking.value = false
        }
    }

    suspend fun go(): Boolean {
        if (!canGo) return false
        val num = GoToNumberParser.parse(_text.value) ?: return false
        _isBusy.value = true
        _message.value = null
        // Go answers for itself from here; a lookup still out is ignored.
        supersedeLookups()
        try {
            val known = (_result.value as? GoToNumberResult.Hit)?.takeIf { it.asked == num }
            val result = known ?: resolver.find(num).asResult(num).also { _result.value = it }
            if (result !is GoToNumberResult.Hit || !result.canOpen) return false
            return when (val opened = resolver.open(result.hit)) {
                is GoToNumberResolver.Opened.Target -> { navigate(opened.target); true }
                is GoToNumberResolver.Opened.Message -> { _message.value = opened.text; false }
            }
        } finally {
            _isBusy.value = false
        }
    }

    private fun supersedeLookups() {
        generation++
        _isLooking.value = false
    }

    private fun GoToNumberResolver.Found.asResult(num: Int): GoToNumberResult = when (this) {
        is GoToNumberResolver.Found.Hit -> GoToNumberResult.Hit(num, hit)
        GoToNumberResolver.Found.Missing -> GoToNumberResult.Missing(num)
        is GoToNumberResolver.Found.Failed -> GoToNumberResult.Failed(num, message)
    }

    companion object {
        const val DEBOUNCE_MS = 200L

        /// Go needs a number, and one that is not already known to have no
        /// page to open. It does not need the answer: Go resolves by itself.
        fun canGo(text: String, result: GoToNumberResult?, busy: Boolean): Boolean {
            val num = GoToNumberParser.parse(text) ?: return false
            if (busy) return false
            return !(result is GoToNumberResult.Hit && result.asked == num && !result.canOpen)
        }
    }
}
