package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.RPCReply
import chat.matron.android.journal.RPCRequestError
import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// The RPC slice New Chat needs, extracted so the view model tests against a
/// fake. Ported from matron-apple's `AgentRPCProviding` (whose `paramsData: Data`
/// becomes `paramsJson: String`, matching the Kotlin engine's `agentRequest`).
interface AgentRPCProviding {
    suspend fun devices(): List<DeviceDTO>
    suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply
}

/// Production adapter: the session's [JournalApi] (roster) + [JournalSyncEngine]
/// (RPC send/correlate, engine-default timeout).
class JournalAgentRPCService(
    private val api: JournalApi,
    private val engine: JournalSyncEngine,
) : AgentRPCProviding {
    override suspend fun devices(): List<DeviceDTO> = api.devices()
    override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply =
        engine.agentRequest(agentDeviceID, method, paramsJson)
}

/// One entry of a bridge's `recent_folders` answer. [lastUsed] (epoch ms) is
/// `null` for "available but never used here" — sorts last, reads "never used".
data class RecentFolder(val path: String, val lastUsed: Long?)

/// Drives the New Chat flow: connected-agent picker → recent-folders picker →
/// `start` RPC → the caller navigates to `convo_id`. Ported from matron-apple's
/// `NewChatViewModel`. Contract rules: `start` is single-flight (non-idempotent,
/// no relay dedup); a failed `recent_folders` degrades the picker only; timeout
/// and `agent_unreachable` read the same to the user.
class NewChatViewModel(
    private val api: AgentRPCProviding,
    // Not defaulted: the cache is namespaced per account, and a convenient
    // default here would be a silent app-global one (apple #164).
    private val capacityCache: BoxCapacityCaching,
    /// Injected clock (epoch ms), so tests can pin capture times.
    private val now: () -> Long = System::currentTimeMillis,
    /// Injected wake-retry sleep (ms), so the wake loops run at test speed.
    private val wakeSleep: suspend (Long) -> Unit = { delay(it) },
) {
    sealed interface Phase {
        data object LoadingAgents : Phase
        data class Agents(val agents: List<DeviceDTO>) : Phase
        data class Folders(val agent: DeviceDTO) : Phase
        data class Done(val convoID: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.LoadingAgents)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _folders = MutableStateFlow<List<RecentFolder>>(emptyList())
    val folders: StateFlow<List<RecentFolder>> = _folders.asStateFlow()

    private val _foldersError = MutableStateFlow<String?>(null)
    val foldersError: StateFlow<String?> = _foldersError.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    // MARK: wake-on-pick (apple #168)
    //
    // The journal boots an idle-stopped box whenever an `agent_request`
    // targets it and refuses with `agent_unreachable`, so the client's job is
    // to keep re-asking until the bridge connects. `agent_unreachable` is the
    // ONLY retried failure for `start` — the server refuses it before anything
    // reaches the bridge, so a retry can never double-start; the folder loop
    // also retries a timeout (mid-boot the socket can be up while the bridge
    // is still starting).

    /// True while a wake loop (folders or start) is re-asking a sleeping box.
    private val _isWakingBox = MutableStateFlow(false)
    val isWakingBox: StateFlow<Boolean> = _isWakingBox.asStateFlow()

    /// When the current wake began (epoch ms), for the banner's elapsed time.
    private val _wakeStartedAt = MutableStateFlow<Long?>(null)
    val wakeStartedAt: StateFlow<Long?> = _wakeStartedAt.asStateFlow()

    /// The last wake ran out of attempts or time; the sheet offers Try Again.
    private val _wakeGaveUp = MutableStateFlow(false)
    val wakeGaveUp: StateFlow<Boolean> = _wakeGaveUp.asStateFlow()

    /// Ownership token per wake loop: a superseded loop (another box picked,
    /// back to the roster, sheet dismissed) sees the token move and exits
    /// without touching the flags the newer owner holds.
    private var wakeToken = 0
    private var wakeAgentID: Long? = null
    private var isAbandoned = false

    /// Capacity per connected agent device id, filled by the roster fan-out.
    /// A box with no entry simply has no capacity to show (never asked, or its
    /// `recent_folders` failed) — the row still renders and stays pickable.
    private val _capacities = MutableStateFlow<Map<Long, BoxCapacity>>(emptyMap())
    val capacities: StateFlow<Map<Long, BoxCapacity>> = _capacities.asStateFlow()

    /// Agent device ids whose fan-out request is still in flight, so a row can
    /// say "Checking…" instead of looking capacity-less.
    private val _capacityPending = MutableStateFlow<Set<Long>>(emptySet())
    val capacityPending: StateFlow<Set<Long>> = _capacityPending.asStateFlow()

    /// Folders harvested from the fan-out replies, so picking a box that already
    /// answered skips a second `recent_folders` round trip.
    private val folderCache = mutableMapOf<Long, List<RecentFolder>>()

    /// Capture times for the entries in [capacities] that came out of the
    /// cache rather than off the wire this visit. Only offline boxes are ever
    /// seeded, so a key here means exactly "this row is showing last-known
    /// numbers" — see [capacityFreshness].
    private val capacityCapturedAt = mutableMapOf<Long, Long>()

    /// How much a row's capacity numbers can be trusted: live for a box this
    /// visit asked, cached-with-an-age for an offline box seeded from the
    /// store. A box with no entry at all reads [AgentCapacityFreshness.Live] —
    /// it has nothing to disclaim, and its row shows nothing either way.
    fun capacityFreshness(agentID: Long): AgentCapacityFreshness =
        capacityCapturedAt[agentID]?.let { AgentCapacityFreshness.Offline(it) } ?: AgentCapacityFreshness.Live

    var customPath: String = ""
    var browserEnabled: Boolean = false

    suspend fun load() {
        try {
            val agents = api.devices().filter { it.kind == "agent" }
            val connected = agents.filter { it.connected }
            // The roster is the authority on which boxes exist: prune the
            // capacity cache here, on EVERY path — the single-box auto-skip
            // below never reaches the fan-out, and an unpaired box would
            // otherwise sit in the cache forever with its quota and account
            // email (CodeRabbit, #51).
            capacityCache.prune(keeping = agents.map { it.id }.toSet())
            if (agents.size == 1) {
                // Auto-skip straight to the folder step: there's no roster to
                // decorate, so no fan-out. Asleep or not — a single-box roster
                // would be a dead stop, and a pick wakes it (apple #168).
                select(agents[0])
            } else {
                _phase.value = Phase.Agents(sorted(agents))
                // The roster is already on screen (the phase flow was set
                // first); this only fills in the capacity lines behind it.
                val connectedIDs = connected.map { it.id }
                val offlineIDs = agents.filter { !it.connected }.map { it.id }
                _capacityPending.value = connectedIDs.toSet()
                // Two entries never survive a reload: a box this fan-out won't
                // ask at all (nothing would ever revalidate it — it is
                // re-seeded from the cache below instead, captioned with its
                // age), and a cache seed for a box that has since come online
                // (never confirmed against the running box, so keeping it
                // would launder disk data into an uncaptioned, live-looking
                // row).
                val refreshing = connectedIDs.toSet()
                _capacities.value = _capacities.value.filterKeys { it in refreshing && capacityCapturedAt[it] == null }
                capacityCapturedAt.clear()
                seedOfflineCapacities(offline = offlineIDs)
                coroutineScope {
                    for (id in connectedIDs) launch { fetchCapacity(id) }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _phase.value = Phase.Agents(emptyList())
            _errorMessage.value = "Couldn't load agents — try again."
        }
    }

    suspend fun select(agent: DeviceDTO) {
        // An impatient re-tap on the box already waking must not start a
        // second loop — one wake per box.
        if (_isWakingBox.value && sameFolderAgent(agent)) return
        retireWakeOwner()
        _errorMessage.value = null
        _wakeGaveUp.value = false
        _phase.value = Phase.Folders(agent)
        _folders.value = emptyList()
        _foldersError.value = null
        if (!agent.connected) {
            // The first ask has already booted the box server-side; keep
            // asking until the bridge connects.
            wakeAndFetchFolders(agent)
            return
        }
        folderCache[agent.id]?.let {
            _folders.value = it
            return
        }
        try {
            val reply = api.agentRequest(agent.id, "recent_folders", "{}")
            // A fleet with one box auto-skips the roster and never fans out,
            // so this is the only reply that box's capacity can be learned
            // from before it goes to sleep. Recorded off the answer rather
            // than off the phase: it is true whether or not the user has
            // moved on since.
            recordCapacity(reply, agent.id)
            if (!sameFolderAgent(agent)) return // switched away meanwhile
            when (reply) {
                is RPCReply.Ok -> _folders.value = parseFolders(reply.result)
                // The roster's `connected` is a snapshot; a box idle-stopped
                // since then answers agent_unreachable — which has already
                // fired its wake, so it gets the wake loop, not the degrade copy.
                is RPCReply.Failure ->
                    if (reply.code == AGENT_UNREACHABLE) wakeAndFetchFolders(agent) else folderFetchFailed(agent)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (!sameFolderAgent(agent)) return
            folderFetchFailed(agent)
        }
    }

    /// Try Again after a wake gave up: runs the folder wake loop once more.
    suspend fun retryWake() {
        val phaseNow = _phase.value
        if (phaseNow !is Phase.Folders || _isWakingBox.value || _isStarting.value) return
        _errorMessage.value = null
        _wakeGaveUp.value = false
        wakeAndFetchFolders(phaseNow.agent)
    }

    /// The sheet went away: retire every wake loop and stop the start
    /// re-asks — a retried start landing minutes later would silently open a
    /// session (and a live Claude process) on a box nobody is looking at.
    fun abandon() {
        isAbandoned = true
        retireWakeOwner()
    }

    private fun retireWakeOwner() {
        wakeToken += 1
        _isWakingBox.value = false
        _wakeStartedAt.value = null
        wakeAgentID = null
    }

    private fun beginWake(agentID: Long): Int {
        wakeToken += 1
        _isWakingBox.value = true
        // Same box again (a start retry during its folder wake) keeps the
        // clock running; a different box restarts it.
        if (_wakeStartedAt.value == null || wakeAgentID != agentID) _wakeStartedAt.value = now()
        wakeAgentID = agentID
        return wakeToken
    }

    private fun endWake(token: Int) {
        if (token != wakeToken) return // a newer owner holds the flags
        _isWakingBox.value = false
        _wakeStartedAt.value = null
        wakeAgentID = null
    }

    private fun stillOwns(token: Int, agent: DeviceDTO): Boolean = token == wakeToken && sameFolderAgent(agent)

    private suspend fun wakeAndFetchFolders(agent: DeviceDTO) {
        val token = beginWake(agent.id)
        try {
            val wakeBegan = now()
            for (attempt in 1..WAKE_ATTEMPT_LIMIT) {
                try {
                    val reply = api.agentRequest(agent.id, "recent_folders", "{}")
                    recordCapacity(reply, agent.id)
                    if (!stillOwns(token, agent)) return
                    when (reply) {
                        is RPCReply.Ok -> {
                            _folders.value = parseFolders(reply.result)
                            return
                        }
                        is RPCReply.Failure -> if (reply.code != AGENT_UNREACHABLE) {
                            _foldersError.value = FOLDERS_ERROR_COPY
                            return
                        } // else still booting — go around
                    }
                } catch (timeout: RPCRequestError.Timeout) {
                    // Mid-boot the socket can be up while the bridge is still
                    // starting: a wake in progress, not a dead end.
                    if (!stillOwns(token, agent)) return
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    if (!stillOwns(token, agent)) return
                    _foldersError.value = FOLDERS_ERROR_COPY
                    return
                }
                // Attempts cost RPC + sleep, so a timeout streak would otherwise
                // run many minutes of banner: the wall-clock deadline cuts in
                // long before the attempt limit.
                if (attempt >= WAKE_ATTEMPT_LIMIT || now() - wakeBegan >= WAKE_DEADLINE_MS) break
                wakeSleep(WAKE_RETRY_DELAY_MS)
                if (!stillOwns(token, agent)) return
            }
            if (!stillOwns(token, agent)) return
            // Never bury a start error the user still needs to read.
            if (_errorMessage.value == null) _errorMessage.value = WAKE_GAVE_UP_MESSAGE
            _wakeGaveUp.value = true
        } finally {
            endWake(token)
        }
    }

    private fun recordCapacity(reply: RPCReply, agentID: Long) {
        if (reply is RPCReply.Ok) capacityCache.save(BoxCapacity.parse(reply.result), agentID, now())
    }

    /// The live `recent_folders` call for the folder step failed. The roster
    /// fan-out may have warmed the cache for this box while that call was on
    /// the wire — if so, its answer is as good as ours; only a still-cold
    /// cache is worth an error (CodeRabbit, #36).
    private fun folderFetchFailed(agent: DeviceDTO) {
        folderCache[agent.id]?.let {
            _folders.value = it
            _foldersError.value = null
            return
        }
        _foldersError.value = FOLDERS_ERROR_COPY
    }

    /// Fires `start {workdir?, browser?}` at the picked agent. A `null`/blank
    /// [workdir] means the bridge's default workdir — the key is omitted.
    suspend fun start(workdir: String?) {
        val phaseNow = _phase.value
        if (phaseNow !is Phase.Folders || _isStarting.value) return
        val agent = phaseNow.agent
        _isStarting.value = true
        var startWakeToken: Int? = null
        try {
            _errorMessage.value = null
            _wakeGaveUp.value = false
            val trimmed = workdir?.trim() ?: ""
            val params = buildJsonObject {
                if (trimmed.isNotEmpty()) put("workdir", trimmed)
                if (browserEnabled) put("browser", true)
            }
            try {
                var reply = api.agentRequest(agent.id, "start", params.toString())
                // `agent_unreachable` is refused before delivery, so re-asking
                // is provably safe; a timeout is NOT retried (the start may
                // have landed). Keep the banner up while the box boots.
                var attempts = 1
                while (attempts < WAKE_ATTEMPT_LIMIT && isUnreachable(reply) && !isAbandoned) {
                    // Only take the banner when nothing else holds it: a
                    // folder wake already running for this box keeps its loop
                    // (and its Try Again) — retiring it here would leave the
                    // folder step empty after a fast start failure (Bugbot, #52).
                    if (startWakeToken == null && !(_isWakingBox.value && wakeAgentID == agent.id)) {
                        startWakeToken = beginWake(agent.id)
                    }
                    wakeSleep(WAKE_RETRY_DELAY_MS)
                    if (isAbandoned || !sameFolderAgent(agent)) return
                    reply = api.agentRequest(agent.id, "start", params.toString())
                    attempts += 1
                }
                when (reply) {
                    is RPCReply.Ok -> {
                        val convoID = (reply.result as? JsonObject)?.stringOrNull("convo_id")
                        if (convoID.isNullOrEmpty()) {
                            _errorMessage.value =
                                "Couldn't start — the agent answered without a conversation id."
                            return
                        }
                        _phase.value = Phase.Done(convoID)
                    }
                    is RPCReply.Failure -> _errorMessage.value = startErrorCopy(reply.code, reply.detail)
                }
            } catch (timeout: RPCRequestError.Timeout) {
                _errorMessage.value = "The agent didn't answer — is the box awake?"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _errorMessage.value = "Couldn't start — check your connection and try again."
            }
        } finally {
            _isStarting.value = false
            startWakeToken?.let { endWake(it) }
        }
    }

    private fun isUnreachable(reply: RPCReply): Boolean =
        reply is RPCReply.Failure && reply.code == AGENT_UNREACHABLE

    /// Back from the folder step to the roster.
    suspend fun backToAgents() = load()

    /// One box's slice of the roster fan-out. The reply carries both the
    /// capacity blocks and the folder list, so a success warms both caches; a
    /// failure leaves no capacity entry and the folder step falls back to its
    /// own live RPC.
    private suspend fun fetchCapacity(agentID: Long) {
        try {
            val reply = api.agentRequest(agentID, "recent_folders", "{}")
            if (reply is RPCReply.Ok) {
                val capacity = BoxCapacity.parse(reply.result)
                _capacities.value = _capacities.value + (agentID to capacity)
                // These numbers came off the wire, so the row must not carry an
                // age caption for them; and they are what the row will show
                // once the host puts this box to sleep.
                capacityCapturedAt.remove(agentID)
                capacityCache.save(capacity, agentID, now())
                val folders = parseFolders(reply.result)
                folderCache[agentID] = folders
                // The folder step may already be showing this box with its own
                // live fetch failed (it raced ahead of this reply): swap the
                // fan-out's answer in rather than leaving a stale error over a
                // now-warm cache (Bugbot, #36).
                val phaseNow = _phase.value
                if (phaseNow is Phase.Folders && phaseNow.agent.id == agentID && _foldersError.value != null) {
                    _folders.value = folders
                    _foldersError.value = null
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Capacity is a convenience — the row just stays plain.
        } finally {
            _capacityPending.value = _capacityPending.value - agentID
        }
    }

    /// Fills the rows of boxes the host has put to sleep with what they last
    /// reported. Nothing here is ever asked for over the wire — that is the
    /// whole point: the user picks which box to wake by its remaining quota.
    private fun seedOfflineCapacities(offline: List<Long>) {
        val cached = capacityCache.loadAll()
        val moment = now()
        val seeded = mutableMapOf<Long, BoxCapacity>()
        for (id in offline) {
            val entry = cached[id] ?: continue
            if (moment - entry.capturedAtMs > MAX_CACHED_CAPACITY_AGE_MS) continue
            seeded[id] = entry.capacity
            capacityCapturedAt[id] = entry.capturedAtMs
        }
        if (seeded.isNotEmpty()) _capacities.value = _capacities.value + seeded
    }

    private fun sameFolderAgent(agent: DeviceDTO): Boolean {
        val phaseNow = _phase.value
        return phaseNow is Phase.Folders && phaseNow.agent.id == agent.id
    }

    companion object {
        /// The journal's refusal for an idle-stopped box — its wake has
        /// already fired server-side by the time this arrives.
        const val AGENT_UNREACHABLE = "agent_unreachable"
        const val WAKE_RETRY_DELAY_MS = 3_000L
        const val WAKE_ATTEMPT_LIMIT = 40
        /// Wall-clock bound on a wake: a mid-boot timeout streak runs ~18s per
        /// attempt, so the attempt limit alone would mean ~12 minutes of banner.
        const val WAKE_DEADLINE_MS = 120_000L
        const val WAKE_GAVE_UP_MESSAGE = "The box didn't wake — try again."
        const val FOLDERS_ERROR_COPY = "Couldn't fetch recent folders — you can still type a path."

        /// How stale a cached capacity may be before it stops being worth
        /// showing: past this, every limit window it describes has rolled over
        /// several times, so the percentages say nothing about the box today.
        const val MAX_CACHED_CAPACITY_AGE_MS: Long = 7L * 86_400_000

        fun sorted(agents: List<DeviceDTO>): List<DeviceDTO> =
            agents.sortedWith(compareByDescending<DeviceDTO> { it.connected }.thenBy { it.name })

        fun parseFolders(result: JsonElement): List<RecentFolder> {
            val obj = result as? JsonObject ?: return emptyList()
            val raw = obj.arrayOrNull("folders") ?: return emptyList()
            return raw.objects().mapNotNull { entry ->
                val path = entry.stringOrNull("path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RecentFolder(path, entry.longOrNull("last_used"))
            }.sortedWith { a, b ->
                val la = a.lastUsed
                val lb = b.lastUsed
                when {
                    la != null && lb != null -> lb.compareTo(la)
                    la != null && lb == null -> -1
                    la == null && lb != null -> 1
                    else -> a.path.compareTo(b.path)
                }
            }
        }

        fun startErrorCopy(code: String, detail: String?): String = when (code) {
            "agent_unreachable", "not_ready" -> "The agent didn't answer — is the box awake?"
            "bad_workdir" -> "That folder doesn't exist on the box."
            else -> "Couldn't start — ${detail ?: code}."
        }
    }
}
