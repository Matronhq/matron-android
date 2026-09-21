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
import org.junit.Assert.assertFalse
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
        // A single-box fleet auto-skips the roster (asleep or not, apple #168);
        // two boxes — one asleep — now show it, so the fixture is one box.
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
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
            // `agent_unreachable` is retried through the start wake loop
            // (apple #168) and, once that gives up, reads as before.
            RPCReply.Failure("agent_unreachable", null) to "The agent didn't answer — is the box awake?",
            RPCReply.Failure("not_ready", null) to "The agent didn't answer — is the box awake?",
            RPCReply.Failure("bad_workdir", "/nope") to "That folder doesn't exist on the box.",
            RPCReply.Failure("bad_model", "opus") to "That box doesn't offer that model — pick another.",
            RPCReply.Failure("bad_agent", "codex") to "That box can't start that agent — pick another.",
            RPCReply.Failure("spawn_failed", "boom") to "Couldn't start — boom.",
            RPCReply.Failure("unsupported_mode", null) to "Couldn't start — unsupported_mode.",
        )
        for ((reply, expected) in cases) {
            val fake = FakeAgentRPCProvider()
            fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
            fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
            fake.replies["start"] = reply
            val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache(), wakeSleep = {})
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
        fake.repliesByDevice[2] = RPCReply.Failure("internal", null)
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
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
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
                RPCReply.Failure("internal", null), // select()'s live call
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
                RPCReply.Failure("internal", null), // select()'s live call
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

    // MARK: Model picker (apple #169)

    private fun vmWith(fake: FakeAgentRPCProvider) = NewChatViewModel(fake, InMemoryBoxCapacityCache(), wakeSleep = { _ -> })

    @Test
    fun start_sendsSelectedModel() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[],"model_options":[{"value":"opus","label":"Opus"},{"value":"sonnet","label":"Sonnet"}]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        vm.selectModel("sonnet")
        vm.start("~/dev/app")
        assertEquals("sonnet", fake.requests.last().params["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun start_omitsModelWhenDefaultPicked() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[],"model_options":[{"value":"opus","label":"Opus"}]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        assertNull("the picker opens on the bridge's own default", vm.selectedModel.value)
        vm.start("~/dev/app")
        assertFalse("no pick means the bridge decides — omit the key", fake.requests.last().params.containsKey("model"))
    }

    @Test
    fun modelOptions_parsedFromRecentFolders() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"model_options":[{"value":"default","label":"Default"},{"value":"opus","label":"Opus 4.6"},{"value":"sonnet"},{"value":"opus","label":"Opus again"},{"label":"nameless"},{"value":""}]}""",
        )
        val vm = vmWith(fake)
        vm.load()
        assertEquals(listOf(ModelOption("opus", "Opus 4.6"), ModelOption("sonnet", "sonnet")), vm.modelOptions.value)
    }

    @Test
    fun modelOptions_absentKeyLeavesNoOffer() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[{"path":"/a","last_used":1}]}""")
        val vm = vmWith(fake)
        vm.load()
        assertTrue("an older bridge omits the key — the picker hides", vm.modelOptions.value.isEmpty())
        assertEquals(listOf("/a"), vm.folders.value.map { it.path })
    }

    /// The roster fan-out already asked every connected box, so the folder
    /// step must render that box's offer without a second round-trip.
    @Test
    fun modelOptions_arriveFromTheRosterPrefetch() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"model_options":[{"value":"opus","label":"Opus"}]}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = vmWith(fake)
        vm.load()
        val before = fake.requests.count { it.method == "recent_folders" }
        vm.select(agents[0])
        assertEquals(listOf("opus"), vm.modelOptions.value.map { it.value })
        assertEquals("served from the prefetch, not re-asked", before, fake.requests.count { it.method == "recent_folders" })
    }

    @Test
    fun switchingBoxes_dropsAModelTheNewBoxDoesNotOffer() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"model_options":[{"value":"opus","label":"Opus"},{"value":"sonnet","label":"Sonnet"}]}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[],"model_options":[{"value":"sonnet","label":"Sonnet"}]}""")
        val vm = vmWith(fake)
        vm.load()
        vm.select(agents[0])
        vm.selectModel("sonnet")
        vm.select(agents[1])
        assertEquals("a model the new box also offers survives", "sonnet", vm.selectedModel.value)
        vm.select(agents[0])
        vm.selectModel("opus")
        vm.select(agents[1])
        assertNull("box b can't run opus — carrying the pick over would earn a bad_model", vm.selectedModel.value)
    }

    // MARK: Default model (apple #177)

    @Test
    fun defaultModel_titlesTheDefaultRowAndStillOmitsTheKey() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_model":"fable","model_options":[{"value":"opus","label":"Opus"},{"value":"fable","label":"Fable"}]}""",
        )
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        assertEquals("the offered option's label, not the raw alias", "Fable", vm.defaultModelLabel.value)
        assertEquals("Default (Fable)", vm.defaultRowTitle)
        assertNull("naming the default is not picking it", vm.selectedModel.value)
        vm.start("~/dev/app")
        assertFalse("the bridge applies its own default — the key stays omitted", fake.requests.last().params.containsKey("model"))
    }

    @Test
    fun defaultModel_absentOrEmptyReadsPlainDefault() = runBlocking {
        val replies = listOf(
            """{"folders":[],"model_options":[{"value":"opus","label":"Opus"}]}""",
            """{"folders":[],"default_model":"","model_options":[{"value":"opus","label":"Opus"}]}""",
        )
        for (reply in replies) {
            val fake = FakeAgentRPCProvider()
            fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
            fake.replies["recent_folders"] = foldersReply(reply)
            val vm = vmWith(fake)
            vm.load()
            assertNull(vm.defaultModelLabel.value)
            assertEquals("an older bridge, or one with no box default", "Default", vm.defaultRowTitle)
        }
    }

    @Test
    fun defaultModel_unlistedValueShowsTheRawName() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_model":"claude-opus-5","model_options":[{"value":"opus","label":"Opus"}]}""",
        )
        val vm = vmWith(fake)
        vm.load()
        assertEquals("a full model name is not in the alias offer — show it as sent", "Default (claude-opus-5)", vm.defaultRowTitle)
    }

    /// Same prefetch contract as `model_options`: the folder step renders
    /// the box's default off the roster fan-out, and switching boxes swaps
    /// it for the new box's (or drops it for a box that doesn't say).
    @Test
    fun defaultModel_followsTheBoxAcrossTheRosterPrefetch() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"default_model":"fable","model_options":[{"value":"fable","label":"Fable"}]}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[],"model_options":[{"value":"sonnet","label":"Sonnet"}]}""")
        val vm = vmWith(fake)
        vm.load()
        val before = fake.requests.count { it.method == "recent_folders" }

        vm.select(agents[0])
        assertEquals("Default (Fable)", vm.defaultRowTitle)
        assertEquals("served from the prefetch, not re-asked", before, fake.requests.count { it.method == "recent_folders" })
        vm.select(agents[1])
        assertEquals("box b never said — no stale label carried over", "Default", vm.defaultRowTitle)
    }

    // MARK: Agent switch (apple #179)

    @Test
    fun agentOptions_parsedFromRecentFolders_openOnTheBoxDefault() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_agent":"codex","agent_options":[
              {"value":"claude","label":"Claude Code"},
              {"value":"codex","label":"Codex"},
              {"value":"codex","label":"Codex again"},
              {"value":""},
              {"label":"nameless"}
            ]}""",
        )
        val vm = vmWith(fake)
        vm.load()
        assertEquals(
            "bridge order kept; repeats and entries with nothing to send are dropped",
            listOf(AgentOption("claude", "Claude Code"), AgentOption("codex", "Codex")),
            vm.agentOptions.value,
        )
        assertEquals("the switch opens on what a no-pick start would run", "codex", vm.selectedAgent.value)
        assertTrue(vm.agentSwitchVisible)
    }

    @Test
    fun agentOptions_absentKey_hidesTheSwitch_andStartOmitsAgent() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        assertTrue(vm.agentOptions.value.isEmpty())
        assertFalse("an older bridge offers nothing — no switch", vm.agentSwitchVisible)
        vm.start("~/dev/app")
        assertFalse("a bridge that never offered agents must not be sent one", fake.requests.last().params.containsKey("agent"))
    }

    @Test
    fun singleAgentOffer_hidesTheSwitch_butStartStillNamesIt() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_agent":"claude","agent_options":[{"value":"claude","label":"Claude Code"}]}""",
        )
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        assertFalse("one choice is no choice", vm.agentSwitchVisible)
        vm.start("~/dev/app")
        assertEquals(
            "a bridge that offers agents accepts the key — say what was shown",
            "claude",
            fake.requests.last().params["agent"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun start_sendsThePickedAgent() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_agent":"claude","agent_options":[{"value":"claude","label":"Claude Code"},{"value":"codex","label":"Codex"}]}""",
        )
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        vm.selectAgent("codex")
        vm.start("~/dev/app")
        assertEquals("codex", fake.requests.last().params["agent"]?.jsonPrimitive?.content)
    }

    @Test
    fun codexPick_hidesTheModelPicker_andOmitsTheModel() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(9, connected = true)))
        fake.replies["recent_folders"] = foldersReply(
            """{"folders":[],"default_agent":"claude",
             "agent_options":[{"value":"claude","label":"Claude Code"},{"value":"codex","label":"Codex"}],
             "model_options":[{"value":"opus","label":"Opus"}]}""",
        )
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        vm.load()
        vm.selectModel("opus")
        assertTrue(vm.modelPickerVisible)

        vm.selectAgent("codex")
        assertFalse("Claude aliases mean nothing to a Codex session", vm.modelPickerVisible)
        vm.start("~/dev/app")
        assertFalse(
            "the bridge answers bad_model to a model on a Codex start — never send one",
            fake.requests.last().params.containsKey("model"),
        )

        vm.selectAgent("claude")
        assertTrue(vm.modelPickerVisible)
        assertEquals("the pick was parked, not thrown away", "opus", vm.selectedModel.value)
    }

    @Test
    fun switchingBoxes_dropsAnAgentTheNewBoxDoesNotOffer() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        fake.repliesByDevice[1] = foldersReply(
            """{"folders":[],"default_agent":"claude","agent_options":[{"value":"claude","label":"Claude Code"},{"value":"codex","label":"Codex"}]}""",
        )
        fake.repliesByDevice[2] = foldersReply(
            """{"folders":[],"default_agent":"claude","agent_options":[{"value":"claude","label":"Claude Code"}]}""",
        )
        val vm = vmWith(fake)
        vm.load()

        vm.select(agents[0])
        vm.selectAgent("codex")
        vm.select(agents[1])
        assertEquals("box b can't run Codex — carrying the pick over would earn a bad_agent", "claude", vm.selectedAgent.value)
        assertFalse(vm.agentSwitchVisible)
    }

    /// Bugbot (#65): the fan-out repair of a folder step whose live fetch
    /// failed (see `fanOutSuccess_repairsFolderStepAfterLiveFetchFailed`)
    /// must adopt the whole reply, not just its folders — the step opened
    /// on an empty offer, so the switch, the default label and the `agent`
    /// key on `start` are all waiting on it.
    @Test
    fun fanOutRepair_adoptsAgentAndModelOfferToo() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        val gate = CompletableDeferred<Unit>()
        fake.foldersGatesByDevice[1] = ArrayDeque(listOf(gate))
        fake.foldersSequenceByDevice[1] = ArrayDeque(
            listOf(
                foldersReply(
                    """{"folders":[{"path":"/w/app","last_used":100}],"default_model":"fable",
                     "model_options":[{"value":"fable","label":"Fable"}],"default_agent":"claude",
                     "agent_options":[{"value":"claude","label":"Claude Code"},{"value":"codex","label":"Codex"}]}""",
                ), // fan-out, parked
                RPCReply.Failure("internal", null), // select()'s live call
            ),
        )
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        val loading = async { vm.load() }
        while (fake.requests.count { it.method == "recent_folders" } < 2) yield()

        vm.select(agents[0]) // cache still cold → live call → fails
        assertNotNull(vm.foldersError.value)
        assertFalse(vm.agentSwitchVisible)
        assertEquals("Default", vm.defaultRowTitle)

        gate.complete(Unit) // the fan-out reply lands after the failure
        loading.await()
        assertNull(vm.foldersError.value)
        assertEquals(listOf("/w/app"), vm.folders.value.map { it.path })
        assertTrue("the repair brought the offer with it", vm.agentSwitchVisible)
        assertEquals("claude", vm.selectedAgent.value)
        assertTrue(vm.modelPickerVisible)
        assertEquals("Default (Fable)", vm.defaultRowTitle)
        vm.start("/w/app")
        assertEquals(
            "the box offered agents, so start names one",
            "claude",
            fake.requests.last().params["agent"]?.jsonPrimitive?.content,
        )
    }

    /// The other completion order (see
    /// `selectFailure_fallsBackToFanOutFoldersThatLandedMeanwhile`): the
    /// fan-out warmed the cache while the live call was out, and the live
    /// call then failed. Falling back to the cached folders must take the
    /// cached offer too — the step adopted an empty cache on entry.
    @Test
    fun selectFailure_fallsBackToFanOutOfferThatLandedMeanwhile() = runBlocking {
        val fake = FakeAgentRPCProvider()
        val agents = listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true))
        fake.devicesResult = Result.success(agents)
        val fanOutGate = CompletableDeferred<Unit>()
        val selectGate = CompletableDeferred<Unit>()
        fake.foldersGatesByDevice[1] = ArrayDeque(listOf(fanOutGate, selectGate))
        fake.foldersSequenceByDevice[1] = ArrayDeque(
            listOf(
                foldersReply(
                    """{"folders":[],"default_model":"fable","model_options":[{"value":"fable","label":"Fable"}],
                     "default_agent":"codex","agent_options":[{"value":"claude","label":"Claude Code"},{"value":"codex","label":"Codex"}]}""",
                ), // fan-out
                RPCReply.Failure("internal", null), // select()'s live call
            ),
        )
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        fake.replies["start"] = RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}"""))
        val vm = vmWith(fake)
        val loading = async { vm.load() }
        while (fake.requests.count { it.method == "recent_folders" } < 2) yield()

        val selecting = async { vm.select(agents[0]) } // cache cold → live call, parked
        while (fake.requests.count { it.method == "recent_folders" } < 3) yield()

        fanOutGate.complete(Unit) // cache warms while the live call is still out
        loading.await()
        selectGate.complete(Unit) // …and then the live call fails
        selecting.await()
        assertNull(vm.foldersError.value)
        assertTrue(vm.agentSwitchVisible)
        assertEquals("opens on the box default the cached reply named", "codex", vm.selectedAgent.value)
        assertEquals("Fable", vm.defaultModelLabel.value)
        vm.start("/x")
        assertEquals("codex", fake.requests.last().params["agent"]?.jsonPrimitive?.content)
        assertFalse("a Codex start carries no Claude model", fake.requests.last().params.containsKey("model"))
    }

    @Test
    fun startErrorCopy_badAgent() {
        assertEquals(
            "That box can't start that agent — pick another.",
            NewChatViewModel.startErrorCopy("bad_agent", "codex"),
        )
    }
}
