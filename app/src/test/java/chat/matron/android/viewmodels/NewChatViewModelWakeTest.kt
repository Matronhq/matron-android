package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import chat.matron.android.journal.RPCRequestError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The wake loops (apple #168; spec: sleeping-VPS boxes, journal `wake.js`):
/// a refused `agent_request` to an offline box has already booted it
/// server-side, so the client's job is to keep re-asking until the bridge
/// connects. `agent_unreachable` is the ONLY retried failure for `start` —
/// the server refuses it before anything reaches the bridge, so a retry can
/// never double-start. Ported from `NewChatViewModelWakeTests`.
class NewChatViewModelWakeTest {
    /// One parked sleep: [arrived] completes when the loop reaches it,
    /// [resume] releases it — so a test can change the world while a wake
    /// loop is provably mid-wait.
    private class Park {
        val arrived = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
    }

    /// Records every wake-retry sleep the view model takes instead of actually
    /// sleeping. [parkNext] queues a park; each sleep consumes at most one.
    private class SleepRecorder {
        val delays = mutableListOf<Long>()
        private val pending = ArrayDeque<Park>()
        fun parkNext(): Park = Park().also { pending.addLast(it) }
        suspend fun sleep(ms: Long) {
            delays.add(ms)
            val park = pending.removeFirstOrNull() ?: return
            park.arrived.complete(Unit)
            park.resume.await()
        }
    }

    /// Per-method reply scripting: [sequences] pop one reply per call and are
    /// consulted first; [replies] answer everything after; [rpcError] throws.
    private class Fake : AgentRPCProviding {
        var devicesResult: Result<List<DeviceDTO>> = Result.success(emptyList())
        val replies = mutableMapOf<String, RPCReply>()
        val sequences = mutableMapOf<String, ArrayDeque<Result<RPCReply>>>()
        var rpcError: RPCRequestError? = null
        data class Request(val method: String, val agentDeviceID: Long)
        val requests = mutableListOf<Request>()
        override suspend fun devices(): List<DeviceDTO> = devicesResult.getOrThrow()
        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply {
            requests.add(Request(method, agentDeviceID))
            sequences[method]?.removeFirstOrNull()?.let { return it.getOrThrow() }
            rpcError?.let { throw it }
            return replies[method] ?: RPCReply.Failure("unknown_method", null)
        }
        fun folderRequests() = requests.count { it.method == "recent_folders" }
        fun startRequests() = requests.count { it.method == "start" }
    }

    private fun agent(id: Long, name: String = "dev", connected: Boolean) =
        device(id, kind = "agent", name = name, connected = connected)

    private val unreachable: Result<RPCReply> = Result.success(RPCReply.Failure("agent_unreachable", null))
    private val folders: Result<RPCReply> =
        Result.success(RPCReply.Ok(Json.parseToJsonElement("""{"folders":[{"path":"/home/dan/app","last_used":100}]}""")))
    private val started: Result<RPCReply> = Result.success(RPCReply.Ok(Json.parseToJsonElement("""{"convo_id":"c-new"}""")))
    private val timeout: Result<RPCReply> = Result.failure(RPCRequestError.Timeout)

    private fun seq(vararg r: Any) = ArrayDeque(r.map { @Suppress("UNCHECKED_CAST") (it as Result<RPCReply>) })

    private fun makeVM(fake: Fake, sleeper: SleepRecorder, now: () -> Long = { 1_000L }) =
        NewChatViewModel(fake, InMemoryBoxCapacityCache(), now) { sleeper.sleep(it) }

    /// Two-asleep-box fleet: big enough to show the roster (a single-box fleet
    /// auto-skips it, asleep or not — pinned separately below).
    private suspend fun makeAsleepVM(fake: Fake, sleeper: SleepRecorder, now: () -> Long = { 1_000L }): Pair<NewChatViewModel, DeviceDTO> {
        val box = agent(7, "dev-7", connected = false)
        fake.devicesResult = Result.success(listOf(box, agent(8, "dev-8", connected = false)))
        val vm = makeVM(fake, sleeper, now)
        vm.load()
        assertTrue("an all-asleep fleet still shows the roster", vm.phase.value is NewChatViewModel.Phase.Agents)
        return vm to box
    }

    @Test
    fun selectAsleepBox_retriesUntilFoldersAnswer() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(unreachable, unreachable, folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertNull(vm.foldersError.value)
        assertFalse("the waking banner comes down once the box answers", vm.isWakingBox.value)
        assertNull(vm.wakeStartedAt.value)
        assertFalse(vm.wakeGaveUp.value)
        assertEquals(3, fake.folderRequests())
        assertEquals(listOf(NewChatViewModel.WAKE_RETRY_DELAY_MS, NewChatViewModel.WAKE_RETRY_DELAY_MS), sleeper.delays)
    }

    @Test
    fun selectAsleepBox_givesUpAfterAttemptLimit() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        assertEquals(NewChatViewModel.WAKE_ATTEMPT_LIMIT, fake.folderRequests())
        assertEquals("no sleep after the last attempt", NewChatViewModel.WAKE_ATTEMPT_LIMIT - 1, sleeper.delays.size)
        assertEquals("The box didn't wake — try again.", vm.errorMessage.value)
        assertTrue(vm.wakeGaveUp.value)
        assertFalse(vm.isWakingBox.value)
    }

    @Test
    fun retryWake_runsTheLoopAgain() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        assertTrue(vm.wakeGaveUp.value)
        fake.sequences["recent_folders"] = seq(folders)
        vm.retryWake()
        assertNull("a retry clears the gave-up banner", vm.errorMessage.value)
        assertFalse(vm.wakeGaveUp.value)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
    }

    @Test
    fun selectAsleepBox_nonUnreachableFailureDegradesToFreeText() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(Result.success(RPCReply.Failure("internal", "boom")))
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        assertNotNull("an awake box that can't list folders degrades, not retries", vm.foldersError.value)
        assertEquals(1, fake.folderRequests())
        assertTrue(sleeper.delays.isEmpty())
        assertFalse(vm.isWakingBox.value)
    }

    /// Mid-boot the socket can be up while the bridge is still starting: the
    /// RPC times out rather than being refused. A wake in progress, not a dead end.
    @Test
    fun selectAsleepBox_timeoutKeepsWaking() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(timeout, folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertEquals(1, sleeper.delays.size)
    }

    /// Attempts cost RPC + sleep, so 40 timeouts would otherwise run ~12
    /// minutes of banner. The wall-clock deadline cuts in long before.
    @Test
    fun wakeDeadline_boundsATimeoutStreak() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        var clock = 0L
        fake.rpcError = RPCRequestError.Timeout
        val (vm, box) = makeAsleepVM(fake, sleeper) { clock += 40_000; clock }
        vm.select(box)
        assertTrue("the deadline, not the attempt count, bounds a timeout streak", fake.folderRequests() < 10)
        assertTrue(fake.folderRequests() >= 2)
        assertEquals("The box didn't wake — try again.", vm.errorMessage.value)
        assertTrue(vm.wakeGaveUp.value)
    }

    @Test
    fun backDuringWake_stopsTheLoopSilently() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        val park = sleeper.parkNext()
        val selecting = launch { vm.select(box) }
        park.arrived.await()
        vm.backToAgents()
        park.resume.complete(Unit)
        selecting.join()
        assertEquals("leaving the folder step ends the wake loop before its next ask", 1, fake.folderRequests())
        assertNull("an abandoned wake is not a failure", vm.errorMessage.value)
        assertFalse(vm.isWakingBox.value)
    }

    @Test
    fun selectAsleepBox_secondTapWhileWakingIsIgnored() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        val park = sleeper.parkNext()
        val selecting = launch { vm.select(box) }
        park.arrived.await()
        vm.select(box) // impatient re-tap
        assertEquals("one wake loop per box — a re-tap must not double the RPC traffic", 1, fake.folderRequests())
        fake.sequences["recent_folders"] = seq(folders)
        park.resume.complete(Unit)
        selecting.join()
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
    }

    /// The retired loop's teardown must not clear the flags the NEW loop owns.
    @Test
    fun selectDifferentBoxWhileWaking_retiresTheOldLoopWithoutClobber() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, boxA) = makeAsleepVM(fake, sleeper)
        val boxB = agent(8, "dev-8", connected = false)
        val parkA = sleeper.parkNext()
        val selectingA = launch { vm.select(boxA) }
        parkA.arrived.await()
        assertTrue(vm.isWakingBox.value)
        val parkB = sleeper.parkNext()
        val selectingB = launch { vm.select(boxB) }
        parkB.arrived.await()
        parkA.resume.complete(Unit)
        selectingA.join()
        assertTrue("the retired loop must not clear the new loop's banner", vm.isWakingBox.value)
        assertNotNull(vm.wakeStartedAt.value)
        fake.sequences["recent_folders"] = seq(folders)
        parkB.resume.complete(Unit)
        selectingB.join()
        val current = vm.phase.value as NewChatViewModel.Phase.Folders
        assertEquals(boxB.id, current.agent.id)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertFalse(vm.isWakingBox.value)
        assertEquals("loop A stopped asking after being superseded", 3, fake.folderRequests())
    }

    /// Start Here is live while the folder wake loop runs. A fast-failing start
    /// must not tear down the live loop's banner, and the loop's later writes
    /// must not overwrite the start error the user needs to read.
    @Test
    fun startFailureDuringFolderWake_keepsTheBannerAndTheRealError() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        val park = sleeper.parkNext()
        val selecting = launch { vm.select(box) }
        park.arrived.await()
        assertTrue(vm.isWakingBox.value)
        fake.sequences["start"] = seq(Result.success(RPCReply.Failure("bad_workdir", "/nope")))
        vm.start("/nope")
        val startError = vm.errorMessage.value
        assertNotNull(startError)
        assertTrue("a fast-failing start must not tear down the live wake banner", vm.isWakingBox.value)
        assertNotNull(vm.wakeStartedAt.value)
        fake.sequences["recent_folders"] = seq(folders)
        park.resume.complete(Unit)
        selecting.join()
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertFalse(vm.isWakingBox.value)
        assertEquals("the folder loop's outcome never overwrites the start error", startError, vm.errorMessage.value)
    }

    /// One box, asleep: nothing to choose between, so the sheet goes straight
    /// to the folder step and the wake loop starts.
    @Test
    fun load_singleAsleepBoxFleet_autoSkipsIntoTheWakeLoop() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.devicesResult = Result.success(listOf(agent(7, "dev-7", connected = false)))
        fake.sequences["recent_folders"] = seq(unreachable, folders)
        val vm = makeVM(fake, sleeper)
        vm.load()
        val picked = vm.phase.value as NewChatViewModel.Phase.Folders
        assertEquals(7L, picked.agent.id)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertEquals(1, sleeper.delays.size)
    }

    /// The roster's `connected` is a snapshot; a box idle-stopped since then
    /// answers agent_unreachable — which has already fired its wake.
    @Test
    fun connectedRowThatAnswersUnreachable_entersTheWakeLoop() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        val stale = agent(9, "dev-9", connected = true)
        fake.devicesResult = Result.success(listOf(stale, agent(8, "dev-8", connected = false)))
        fake.replies["recent_folders"] = RPCReply.Failure("agent_unreachable", null) // the fan-out's answer
        val vm = makeVM(fake, sleeper)
        vm.load()
        fake.sequences["recent_folders"] = seq(unreachable, folders)
        vm.select(stale)
        assertEquals(listOf("/home/dan/app"), vm.folders.value.map { it.path })
        assertNull("unreachable means waking, never the degrade copy", vm.foldersError.value)
        assertFalse(vm.isWakingBox.value)
    }

    @Test
    fun start_retriesOnUnreachableUntilTheBridgeAnswers() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        fake.sequences["start"] = seq(unreachable, unreachable, started)
        vm.start("/home/dan/app")
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Done)
        assertEquals(3, fake.startRequests())
        assertEquals(2, sleeper.delays.size)
        assertFalse("the start's wake banner comes down with the answer", vm.isWakingBox.value)
    }

    /// A timed-out start may have landed; re-asking could double-start.
    @Test
    fun start_timeoutIsNeverRetried() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        fake.sequences["start"] = seq(timeout, started)
        vm.start("/home/dan/app")
        assertEquals(1, fake.startRequests())
        assertTrue(sleeper.delays.isEmpty())
        assertNotNull(vm.errorMessage.value)
        assertFalse(vm.phase.value is NewChatViewModel.Phase.Done)
    }

    @Test
    fun start_givesUpAfterAttemptLimit() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        fake.replies["start"] = RPCReply.Failure("agent_unreachable", null)
        vm.start("/home/dan/app")
        assertEquals(NewChatViewModel.WAKE_ATTEMPT_LIMIT, fake.startRequests())
        assertNotNull(vm.errorMessage.value)
        assertFalse(vm.isWakingBox.value)
    }

    /// Dismissing the sheet must stop the start loop: a retried start landing
    /// minutes later would silently open a session on a box nobody is watching.
    @Test
    fun abandon_stopsTheStartRetries() = runBlocking {
        val fake = Fake(); val sleeper = SleepRecorder()
        fake.sequences["recent_folders"] = seq(folders)
        val (vm, box) = makeAsleepVM(fake, sleeper)
        vm.select(box)
        fake.replies["start"] = RPCReply.Failure("agent_unreachable", null)
        val park = sleeper.parkNext()
        val starting = launch { vm.start("/home/dan/app") }
        park.arrived.await()
        vm.abandon()
        fake.sequences["start"] = seq(started)
        park.resume.complete(Unit)
        starting.join()
        assertEquals("no further start after the sheet went away", 1, fake.startRequests())
        assertFalse(vm.phase.value is NewChatViewModel.Phase.Done)
        assertFalse(vm.isWakingBox.value)
    }
}
