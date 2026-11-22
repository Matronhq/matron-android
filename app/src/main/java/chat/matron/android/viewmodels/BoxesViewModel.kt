package chat.matron.android.viewmodels

import android.util.Log
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RosterSession
import chat.matron.android.journal.RosterSnapshot
import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// What the Boxes screen reads: New Chat's RPC slice, plus the roster for
/// each box's sessions and whether a disconnected box can be woken.
interface BoxesProviding : AgentRPCProviding {
    suspend fun roster(): RosterSnapshot
}

/// One row of the Boxes screen.
data class BoxRow(
    val device: DeviceDTO,
    val state: BoxConnectionState,
    val capacity: BoxCapacity?,
    val freshness: AgentCapacityFreshness,
    /// The sessions to list under this box, busiest first.
    val sessions: List<RosterSession>,
    val isNearLimit: Boolean,
    val isMostSpare: Boolean,
    val defaultsText: String?,
    /// When the usage numbers were read (epoch ms); null when there are none.
    val readingAtMs: Long?,
    /// A wake under way or just failed; null otherwise.
    val wake: NewChatViewModel.RowWake?,
) {
    val id: Long get() = device.id

    /// The live-session count to show. A sleeping box runs nothing, so its
    /// last count would be false rather than merely old.
    val liveCount: Int? get() = if (state == BoxConnectionState.Online) capacity?.liveSessions else null

    /// A session can be started here: the box is up or can be woken.
    val canStartSession: Boolean get() = state != BoxConnectionState.Offline
}

/// The figures across the top of the Boxes screen.
data class BoxesSummary(
    val total: Int,
    val online: Int,
    val asleep: Int,
    val offline: Int,
    val spare: AllowanceSpare?,
    /// Names of the boxes on the most-spare login.
    val spareBoxNames: List<String>,
) {
    /// "21 boxes · 19 online · 2 asleep"; a zero count is left out.
    val countsText: String
        get() = buildList {
            add("$total ${if (total == 1) "box" else "boxes"}")
            if (online > 0) add("$online online")
            if (asleep > 0) add("$asleep asleep")
            if (offline > 0) add("$offline offline")
        }.joinToString(" · ")

    companion object {
        /// "a, b, c and 2 more".
        fun namesText(names: List<String>, limit: Int = 3): String =
            if (names.size <= limit) names.joinToString(", ")
            else names.take(limit).joinToString(", ") + " and ${names.size - limit} more"
    }
}

/// Every paired box with its state, usage and sessions. Reads what the
/// journal already holds, so opening the screen asks no box anything;
/// `box_status` frames, a reconnect and a slow poll keep it current while it
/// shows. Port of matron-apple's `BoxesViewModel` (without box locks, which
/// this app does not have yet).
class BoxesViewModel(
    private val api: BoxesProviding,
    capacityCache: BoxCapacityCaching,
    private val pollSleep: suspend () -> Unit = { delay(POLL_INTERVAL_MS) },
) {
    /// Everything the rows are built from, as one value so a screen redraws
    /// on any change.
    data class State(
        val devices: List<DeviceDTO> = emptyList(),
        val reports: Map<Long, BoxStatus> = emptyMap(),
        /// Null until the first roster answer. Until then a disconnected box
        /// reads as asleep, as New Chat treats it.
        val roster: RosterSnapshot? = null,
        val hasLoaded: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /// Runs the Wake button's loop, the same one New Chat's list uses.
    private val waker = NewChatViewModel(api, capacityCache)
    private val _wakingRows = MutableStateFlow<Set<Long>>(emptySet())
    /// Boxes with a wake under way, until the reload after it has landed:
    /// the wake loop alone ends before the reload marks the box online, and
    /// the row would offer Wake again in between.
    val wakingRows: StateFlow<Set<Long>> = _wakingRows.asStateFlow()
    val rowWakeFailed: StateFlow<Set<Long>> get() = waker.rowWakeFailed

    /// Load generations: [load] can run from the poll, a reconnect, Wake,
    /// pull-to-refresh and first appear at once. Only the newest started load
    /// that has finished is applied, so an older answer landing last can't
    /// rewind a box that just came online.
    private val loadsStarted = java.util.concurrent.atomic.AtomicLong(0)
    private var loadApplied = 0L

    suspend fun load() {
        val generation = loadsStarted.incrementAndGet()
        try {
            val agents = api.devices().filter { it.kind == "agent" }
            val snapshot = try {
                api.roster()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                null
            }
            if (!claimApply(generation)) return
            _state.update { held ->
                var reports = held.reports
                for (agent in agents) agent.status?.let { reports = adopt(reports, it, agent.id) }
                held.copy(
                    devices = agents,
                    reports = reports,
                    roster = snapshot ?: held.roster,
                    hasLoaded = true,
                    errorMessage = null,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            runCatching { Log.e(TAG, "boxes load failed", error) }
            if (!claimApply(generation)) return
            _state.update { it.copy(hasLoaded = true, errorMessage = "Couldn't load your boxes. Try again.") }
        }
    }

    /// Whether a load of [generation] may write its result: not once a newer
    /// one has.
    @Synchronized
    private fun claimApply(generation: Long): Boolean {
        if (generation < loadApplied) return false
        loadApplied = generation
        return true
    }

    /// Applies live reports and refreshes on a reconnect and on a slow poll
    /// (which is how a box coming or going, or a new session, shows up).
    /// Runs until the calling coroutine is cancelled.
    suspend fun watch() = coroutineScope {
        launch {
            api.boxStatusUpdates().collect { (id, status) ->
                _state.update { it.copy(reports = adopt(it.reports, status, id)) }
            }
        }
        launch {
            var wasRunning: Boolean? = null
            api.connectionState().collect { state ->
                val isRunning = state == SyncConnectionState.Running
                val reconnected = wasRunning == false && isRunning
                wasRunning = isRunning
                if (reconnected) load()
            }
        }
        launch {
            while (isActive) {
                pollSleep()
                if (!isActive) break
                load()
            }
        }
    }

    suspend fun wake(device: DeviceDTO) {
        if (device.id in _wakingRows.value) return
        _wakingRows.update { it + device.id }
        try {
            waker.wake(device)
            load()
        } finally {
            _wakingRows.update { it - device.id }
        }
    }

    /// Online, then asleep, then offline, each A to Z: the rows stay put as
    /// their numbers change.
    fun rows(
        state: State = _state.value,
        waking: Set<Long> = wakingRows.value,
        failed: Set<Long> = rowWakeFailed.value,
        nowMs: Long = System.currentTimeMillis(),
    ): List<BoxRow> {
        val readings = state.devices.mapNotNull { device ->
            val capacity = state.reports[device.id]?.capacity ?: return@mapNotNull null
            BoxAllowanceReading(device.id, capacity.accountEmail, capacity.limitLines)
        }
        val spare = BoxAllowances.mostSpare(readings, nowMs)
        return state.devices
            .map { row(it, state, spare, waking, failed, nowMs) }
            .sortedWith(compareBy<BoxRow> { it.state.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.device.name })
    }

    fun summary(rows: List<BoxRow>, nowMs: Long = System.currentTimeMillis()): BoxesSummary {
        val readings = rows.mapNotNull { row ->
            row.capacity?.let { BoxAllowanceReading(row.id, it.accountEmail, it.limitLines) }
        }
        val spare = BoxAllowances.mostSpare(readings, nowMs)
        return BoxesSummary(
            total = rows.size,
            online = rows.count { it.state == BoxConnectionState.Online },
            asleep = rows.count { it.state == BoxConnectionState.Asleep },
            offline = rows.count { it.state == BoxConnectionState.Offline },
            spare = spare,
            spareBoxNames = rows.filter { spare?.boxIDs?.contains(it.id) == true }.map { it.device.name },
        )
    }

    private fun row(
        device: DeviceDTO,
        state: State,
        spare: AllowanceSpare?,
        waking: Set<Long>,
        failed: Set<Long>,
        nowMs: Long,
    ): BoxRow {
        val report = state.reports[device.id]
        val capacity = report?.capacity
        val connection = when {
            device.connected -> BoxConnectionState.Online
            state.roster == null || device.id in state.roster.wakeableBoxes -> BoxConnectionState.Asleep
            else -> BoxConnectionState.Offline
        }
        val freshness = report?.let {
            if (device.connected) AgentCapacityFreshness.Live else AgentCapacityFreshness.Offline(it.reportedAtMs)
        } ?: AgentCapacityFreshness.Live
        val hasLines = capacity?.limitLines?.isNotEmpty() == true
        return BoxRow(
            device = device,
            state = connection,
            capacity = capacity,
            freshness = freshness,
            sessions = shownSessions(
                state.roster?.sessionsByBox?.get(device.id).orEmpty(),
                liveCount = capacity?.liveSessions,
                isOnline = connection == BoxConnectionState.Online,
                nowMs = nowMs,
            ),
            isNearLimit = !freshness.isStale && BoxAllowances.isNearLimit(capacity?.limitLines.orEmpty(), nowMs),
            isMostSpare = spare?.boxIDs?.contains(device.id) == true,
            defaultsText = defaultsText(device, capacity),
            readingAtMs = if (hasLines) capacity?.limitsAsOfMs ?: report?.reportedAtMs else null,
            // `rowWake` says Asleep for every disconnected box; the row only
            // cares about a wake under way or one that failed.
            wake = NewChatViewModel.rowWake(device, waking, failed)
                ?.takeUnless { it == NewChatViewModel.RowWake.Asleep },
        )
    }

    companion object {
        private const val TAG = "BoxesViewModel"
        const val POLL_INTERVAL_MS = 60_000L

        /// Latest report wins, so a frame that beat a roster read is kept.
        internal fun adopt(reports: Map<Long, BoxStatus>, status: BoxStatus, boxID: Long): Map<Long, BoxStatus> {
            val held = reports[boxID]
            if (held != null && held.reportedAtMs >= status.reportedAtMs) return reports
            return reports + (boxID to status)
        }

        /// The bridge's own resolved defaults first (they include what the
        /// box's environment chose), else what the journal holds for the box.
        internal fun defaultsText(device: DeviceDTO, capacity: BoxCapacity?): String? {
            capacity?.defaults?.let { return BoxAllowances.defaultsText(it.agent, it.model, it.effort) }
            val agent = device.defaults?.agent ?: return null
            return BoxAllowances.defaultsText(agent, device.defaults.model, device.defaults.effort)
        }

        /// The journal marks a session `waiting` even after its process has
        /// gone, so the open list alone overstates what is live. Running ones
        /// always show; waiting ones fill up to the box's own live count,
        /// most recent first. A box that reports no count shows the last
        /// hour's.
        internal fun shownSessions(
            sessions: List<RosterSession>,
            liveCount: Int?,
            isOnline: Boolean,
            nowMs: Long,
        ): List<RosterSession> {
            if (!isOnline) return emptyList()
            val byRecency = sessions.sortedByDescending { it.lastActivityMs ?: Long.MIN_VALUE }
            val running = byRecency.filter { it.isRunning }
            val waiting = byRecency.filter { !it.isRunning }
            if (liveCount == null) {
                return running + waiting.filter { s -> s.lastActivityMs?.let { nowMs - it < 3_600_000L } ?: false }
            }
            return running + waiting.take(maxOf(0, liveCount - running.size))
        }
    }
}
