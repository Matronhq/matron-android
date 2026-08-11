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

    var customPath: String = ""
    var browserEnabled: Boolean = false

    suspend fun load() {
        try {
            val agents = api.devices().filter { it.kind == "agent" }
            val connected = agents.filter { it.connected }
            if (connected.size == 1) {
                // Auto-skip straight to the folder step: there's no roster to
                // decorate, so no fan-out.
                select(connected[0])
            } else {
                _phase.value = Phase.Agents(sorted(agents))
                // The roster is already on screen (the phase flow was set
                // first); this only fills in the capacity lines behind it.
                val connectedIDs = connected.map { it.id }
                _capacityPending.value = connectedIDs.toSet()
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
        _phase.value = Phase.Folders(agent)
        _folders.value = emptyList()
        _foldersError.value = null
        folderCache[agent.id]?.let {
            _folders.value = it
            return
        }
        try {
            val reply = api.agentRequest(agent.id, "recent_folders", "{}")
            if (!sameFolderAgent(agent)) return // switched away meanwhile
            when (reply) {
                is RPCReply.Ok -> _folders.value = parseFolders(reply.result)
                is RPCReply.Failure ->
                    _foldersError.value = "Couldn't fetch recent folders — you can still type a path."
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (!sameFolderAgent(agent)) return
            _foldersError.value = "Couldn't fetch recent folders — you can still type a path."
        }
    }

    /// Fires `start {workdir?, browser?}` at the picked agent. A `null`/blank
    /// [workdir] means the bridge's default workdir — the key is omitted.
    suspend fun start(workdir: String?) {
        val phaseNow = _phase.value
        if (phaseNow !is Phase.Folders || _isStarting.value) return
        val agent = phaseNow.agent
        _isStarting.value = true
        try {
            _errorMessage.value = null
            val trimmed = workdir?.trim() ?: ""
            val params = buildJsonObject {
                if (trimmed.isNotEmpty()) put("workdir", trimmed)
                if (browserEnabled) put("browser", true)
            }
            try {
                val reply = api.agentRequest(agent.id, "start", params.toString())
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
        }
    }

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
                _capacities.value = _capacities.value + (agentID to BoxCapacity.parse(reply.result))
                folderCache[agentID] = parseFolders(reply.result)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Capacity is a convenience — the row just stays plain.
        } finally {
            _capacityPending.value = _capacityPending.value - agentID
        }
    }

    private fun sameFolderAgent(agent: DeviceDTO): Boolean {
        val phaseNow = _phase.value
        return phaseNow is Phase.Folders && phaseNow.agent.id == agent.id
    }

    companion object {
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
