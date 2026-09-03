package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import chat.matron.android.journal.RPCRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Recording fake for the New-Chat RPC surface. Replies are scripted per method;
/// `agentRequest` throws when [rpcError] is set. Ported from matron-apple's
/// `FakeAgentRPCProvider`.
private class FakeAgentRPCProvider : AgentRPCProviding {
    var devicesResult: Result<List<DeviceDTO>> = Result.success(emptyList())
    var replies: MutableMap<String, RPCReply> = mutableMapOf()

    /// Per-box `recent_folders` scripting for the fan-out tests; consulted before
    /// [replies] so one box can answer while another fails.
    var repliesByDevice: MutableMap<Long, RPCReply> = mutableMapOf()

    /// Scripted `recent_folders` replies per box, consumed one per call and
    /// consulted before [repliesByDevice] — so the fan-out's call and a later
    /// `select()` for the same box can answer differently.
    var foldersSequenceByDevice: MutableMap<Long, ArrayDeque<RPCReply>> = mutableMapOf()

    /// Parks a box's `recent_folders` calls on these gates, one per call in
    /// order (a reply still on the wire); each is consumed on use.
    var foldersGatesByDevice: MutableMap<Long, ArrayDeque<CompletableDeferred<Unit>>> = mutableMapOf()
    var rpcError: RPCRequestError? = null

    data class Request(val method: String, val agentDeviceID: Long, val params: JsonObject)

    val requests = mutableListOf<Request>()

    override suspend fun devices(): List<DeviceDTO> = devicesResult.getOrThrow()

    override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply {
        val params = runCatching { Json.parseToJsonElement(paramsJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        requests.add(Request(method, agentDeviceID, params))
        rpcError?.let { throw it }
        if (method == "recent_folders") {
            val reply = foldersSequenceByDevice[agentDeviceID]?.removeFirstOrNull()
                ?: repliesByDevice[agentDeviceID]
            foldersGatesByDevice[agentDeviceID]?.removeFirstOrNull()?.await()
            reply?.let { return it }
        }
        return replies[method] ?: RPCReply.Failure("unknown_method", null)
    }
}

private fun agent(id: Long, name: String = "dev", connected: Boolean) =
    device(id, kind = "agent", name = name, connected = connected)

class NewChatViewModelTest {
    private fun foldersReply(json: String) = RPCReply.Ok(Json.parseToJsonElement(json))

    @Test
    fun load_showsAgentsOnly_connectedFirst() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(
            listOf(
                device(1, kind = "client", name = "dan-mac", isSelf = true, connected = true),
                agent(2, name = "dev-7", connected = false),
                agent(3, name = "dev-2", connected = true),
                agent(4, name = "dev-9", connected = true),
            ),
        )
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        val phase = vm.phase.value
        assertTrue(phase is NewChatViewModel.Phase.Agents)
        assertEquals(listOf(3L, 4L, 2L), (phase as NewChatViewModel.Phase.Agents).agents.map { it.id })
    }

    @Test
    fun load_singleConnectedAgent_skipsStraightToFolders() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true), agent(2, connected = false)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[{"path":"/home/dan/app","last_used":100}]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        val phase = vm.phase.value
        assertTrue(phase is NewChatViewModel.Phase.Folders)
        assertEquals(9L, (phase as NewChatViewModel.Phase.Folders).agent.id)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
    }

    @Test
    fun folders_sortNewestFirst_nullsLast() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[{"path":"/never","last_used":null},{"path":"/old","last_used":100},{"path":"/new","last_used":900}]}""",
        )
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        assertEquals(listOf("/new", "/old", "/never"), vm.folders.value.map { it.path })
        assertNull(vm.folders.value.last().lastUsed)
    }

    @Test
    fun foldersFailure_degradesPickerButKeepsFreeText() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.rpcError = RPCRequestError.Timeout
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Folders)
        assertNotNull(vm.foldersError.value)
        assertTrue(vm.folders.value.isEmpty())
    }

    @Test
    fun start_sendsWorkdirAndBrowser_navigatesOnConvoID() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        vm.browserEnabled = true
        vm.start("~/dev/app")
        assertEquals(NewChatViewModel.Phase.Done("c-new"), vm.phase.value)
        val start = fake.requests.last()
        assertEquals("start", start.method)
        assertEquals("~/dev/app", start.params["workdir"]?.jsonPrimitive?.content)
        assertEquals(true, start.params["browser"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun start_omitsEmptyWorkdirAndFalseBrowser() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        vm.start("  ")
        val params = fake.requests.last().params
        assertNull(params["workdir"])
        assertNull(params["browser"])
    }

    @Test
    fun start_errorCopyTable() = runBlocking {
        val cases = listOf(
            RPCReply.Failure("agent_unreachable", null) to "The agent didn't answer — is the box awake?",
            RPCReply.Failure("not_ready", null) to "The agent didn't answer — is the box awake?",
            RPCReply.Failure("bad_workdir", "/nope") to "That folder doesn't exist on the box.",
            RPCReply.Failure("spawn_failed", "boom") to "Couldn't start — boom.",
            RPCReply.Failure("unsupported_mode", null) to "Couldn't start — unsupported_mode.",
        )
        for ((reply, expected) in cases) {
            val fake = FakeAgentRPCProvider()
            fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
            fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
            fake.replies["start"] = reply
            val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
            vm.load()
            vm.start("/x")
            assertEquals(expected, vm.errorMessage.value)
            assertTrue(vm.phase.value is NewChatViewModel.Phase.Folders)
        }
    }

    @Test
    fun start_timeoutUsesUnreachableCopy() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        fake.rpcError = RPCRequestError.Timeout
        vm.start("/x")
        assertEquals("The agent didn't answer — is the box awake?", vm.errorMessage.value)
    }

    @Test
    fun start_missingConvoID_surfacesError() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{}"""))
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        vm.start("/x")
        assertNotNull(vm.errorMessage.value)
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Folders)
    }

    @Test
    fun start_reentrantCallIgnored() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        val first = async { vm.start("/x") }
        val second = async { vm.start("/x") }
        awaitAll(first, second)
        assertEquals(1, fake.requests.count { it.method == "start" })
    }

    @Test
    fun selectAgent_fromRoster() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(3, connected = true), agent(4, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        val phase = vm.phase.value
        assertTrue(phase is NewChatViewModel.Phase.Agents)
        val list = (phase as NewChatViewModel.Phase.Agents).agents
        vm.select(list[1])
        val folders = vm.phase.value
        assertTrue(folders is NewChatViewModel.Phase.Folders)
        assertEquals(list[1].id, (folders as NewChatViewModel.Phase.Folders).agent.id)
        assertEquals(list[1].id, fake.requests.last().agentDeviceID)
    }

    @Test
    fun load_fansOutToConnectedAgentsOnly() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(
            listOf(
                agent(1, name = "a", connected = true),
                agent(2, name = "b", connected = true),
                agent(3, name = "c", connected = false),
            ),
        )
        fake.repliesByDevice[1] =
            foldersReply("""{"folders":[],"account":{"email":"pat@yearbook.com"},"activity":{"live_sessions":2}}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        assertEquals(
            listOf(1L, 2L),
            fake.requests.filter { it.method == "recent_folders" }.map { it.agentDeviceID }.sorted(),
        )
        assertEquals("pat@yearbook.com", vm.capacities.value[1L]?.accountEmail)
        assertEquals(2, vm.capacities.value[1L]?.liveSessions)
        assertEquals(BoxCapacity(null, emptyList(), null), vm.capacities.value[2L])
        assertTrue(vm.capacityPending.value.isEmpty())
    }

    @Test
    fun fanOut_oneFailingBoxDegradesAlone() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(
            listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true)),
        )
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"activity":{"live_sessions":1}}""")
        fake.repliesByDevice[2] = RPCReply.Failure("agent_unreachable", null)
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        assertEquals(1, vm.capacities.value[1L]?.liveSessions)
        assertNull(vm.capacities.value[2L])
        assertTrue(vm.capacityPending.value.isEmpty())
    }

    @Test
    fun select_usesFannedFoldersWithoutSecondRPC() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = foldersReply("""{"folders":[{"path":"/w/app","last_used":100}]}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        val callsBefore = fake.requests.count { it.method == "recent_folders" }
        vm.select(agents[0])
        assertEquals(listOf("/w/app"), vm.folders.value.map { it.path })
        assertEquals(callsBefore, fake.requests.count { it.method == "recent_folders" })
    }

    @Test
    fun select_fallsBackToLiveRPCWhenFanOutFailed() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = RPCReply.Failure("agent_unreachable", null)
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        vm.load()
        fake.repliesByDevice[1] = foldersReply("""{"folders":[{"path":"/late","last_used":1}]}""")
        vm.select(agents[0])
        assertEquals(listOf("/late"), vm.folders.value.map { it.path })
    }

    /// Bugbot (#36): the folder step's own live `recent_folders` can fail
    /// while the roster fan-out's call for the same box is still on the wire.
    /// When that fan-out reply then succeeds it must repair the step (folders
    /// in, error out), not just warm a cache behind a stuck error.
    @Test
    fun fanOutSuccess_repairsFolderStepAfterLiveFetchFailed() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        val gate = CompletableDeferred<Unit>()
        fake.foldersGatesByDevice[1] = ArrayDeque(listOf(gate))
        fake.foldersSequenceByDevice[1] = ArrayDeque(
            listOf(
                foldersReply("""{"folders":[{"path":"/w/app","last_used":100}]}"""), // fan-out, parked
                RPCReply.Failure("agent_unreachable", null), // select()'s live call
            ),
        )
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        val loading = async { vm.load() }
        // Let load() post the roster and park box 1's fan-out on the gate.
        while (fake.requests.count { it.method == "recent_folders" } < 2) yield()
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Agents)

        vm.select(agents[0]) // cache still cold → live call → fails
        assertNotNull(vm.foldersError.value)
        assertTrue(vm.folders.value.isEmpty())

        gate.complete(Unit) // the fan-out reply lands after the failure
        loading.await()
        assertNull(vm.foldersError.value)
        assertEquals(listOf("/w/app"), vm.folders.value.map { it.path })
    }

    /// The other completion order (CodeRabbit, #36): the folder step's live
    /// call was already on the wire (cache cold) when the fan-out's reply
    /// warmed the cache; the live call then fails. The cached answer must win
    /// over the error.
    @Test
    fun selectFailure_fallsBackToFanOutFoldersThatLandedMeanwhile() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        val fanOutGate = CompletableDeferred<Unit>()
        val selectGate = CompletableDeferred<Unit>()
        fake.foldersGatesByDevice[1] = ArrayDeque(listOf(fanOutGate, selectGate))
        fake.foldersSequenceByDevice[1] = ArrayDeque(
            listOf(
                foldersReply("""{"folders":[{"path":"/w/app","last_used":100}]}"""), // fan-out
                RPCReply.Failure("agent_unreachable", null), // select()'s live call
            ),
        )
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache())
        val loading = async { vm.load() }
        while (fake.requests.count { it.method == "recent_folders" } < 2) yield()

        val selecting = async { vm.select(agents[0]) } // cache cold → live call, parked
        while (fake.requests.count { it.method == "recent_folders" } < 3) yield()

        fanOutGate.complete(Unit) // cache warms while the live call is still out
        loading.await()
        selectGate.complete(Unit) // …and then the live call fails
        selecting.await()
        assertNull(vm.foldersError.value)
        assertEquals(listOf("/w/app"), vm.folders.value.map { it.path })
    }
}
