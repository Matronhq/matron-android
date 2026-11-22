package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import chat.matron.android.journal.RosterSession
import chat.matron.android.journal.RosterSnapshot
import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxesViewModelTest {
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private class Fake : BoxesProviding {
        var devicesResult: Result<List<DeviceDTO>> = Result.success(emptyList())
        var rosterResult: Result<RosterSnapshot> = Result.success(RosterSnapshot())
        var devicesCalls = 0
        /// When set, `GET /devices` parks on it (a read still on the wire).
        var devicesGate: CompletableDeferred<Unit>? = null
        var wakeReply: RPCReply = RPCReply.Failure("unknown_method", null)
        val feed = Channel<Pair<Long, BoxStatus>>(Channel.UNLIMITED)
        val connection = MutableStateFlow<SyncConnectionState>(SyncConnectionState.Running)

        override suspend fun devices(): List<DeviceDTO> {
            devicesCalls += 1
            devicesGate?.await()
            return devicesResult.getOrThrow()
        }
        override suspend fun roster(): RosterSnapshot = rosterResult.getOrThrow()
        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply = wakeReply
        override fun boxStatusUpdates(): Flow<Pair<Long, BoxStatus>> = feed.receiveAsFlow()
        override fun connectionState(): StateFlow<SyncConnectionState> = connection
    }

    private fun agent(id: Long, name: String, connected: Boolean, status: BoxStatus? = null, defaults: BoxDefaults? = null) =
        device(id, kind = "agent", name = name, connected = connected, defaults = defaults).copy(status = status)

    private fun status(percent: Int, email: String?, live: Int? = 2, agoMs: Long = 60_000, resetsInDays: Double = 2.0) =
        BoxStatus(
            now - agoMs,
            BoxCapacity(
                liveSessions = live,
                limitLines = listOf(
                    LimitLine(BoxAllowances.ALL_MODELS_LINE_ID, "Week (all models)", percent, now + (resetsInDays * day).toLong()),
                ),
                accountEmail = email,
            ),
        )

    private fun makeVM(fake: Fake, poll: suspend () -> Unit = { awaitCancellation() }) =
        BoxesViewModel(fake, InMemoryBoxCapacityCache(), pollSleep = poll)

    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            yield()
        }
        assertTrue("condition never became true", condition())
    }

    @Test
    fun rows_sortOnlineThenAsleepThenOfflineEachByName() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(
            listOf(
                agent(1, "box-d", connected = false),
                agent(2, "box-b", connected = true),
                agent(3, "box-c", connected = false),
                agent(4, "box-a", connected = true),
                device(9, kind = "client", name = "phone"),
            ),
        )
        fake.rosterResult = Result.success(RosterSnapshot(wakeableBoxes = setOf(3)))
        val vm = makeVM(fake)
        vm.load()
        val rows = vm.rows(nowMs = now)
        assertEquals(listOf("box-a", "box-b", "box-c", "box-d"), rows.map { it.device.name })
        assertEquals(
            listOf(BoxConnectionState.Online, BoxConnectionState.Online, BoxConnectionState.Asleep, BoxConnectionState.Offline),
            rows.map { it.state },
        )
        assertFalse(rows.last().canStartSession)
        assertEquals("4 boxes · 2 online · 1 asleep · 1 offline", vm.summary(rows, now).countsText)
    }

    @Test
    fun rows_aDisconnectedBoxReadsAsleepUntilTheRosterSaysOtherwise() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, "box-a", connected = false)))
        fake.rosterResult = Result.failure(IllegalStateException("no roster"))
        val vm = makeVM(fake)
        vm.load()
        assertEquals(BoxConnectionState.Asleep, vm.rows(nowMs = now).single().state)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun rows_highlightNearLimitAndMostSpare() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(
            listOf(
                agent(1, "box-a", connected = true, status = status(85, "alice@example.com")),
                agent(2, "box-b", connected = true, status = status(10, "bob@example.com")),
                agent(3, "box-c", connected = true, status = status(10, "bob@example.com", resetsInDays = 3.0)),
                // Offline numbers are last known: never "near limit".
                agent(4, "box-d", connected = false, status = status(95, "carol@example.com")),
            ),
        )
        val vm = makeVM(fake)
        vm.load()
        val rows = vm.rows(nowMs = now).associateBy { it.device.name }
        assertTrue(rows.getValue("box-a").isNearLimit)
        assertFalse(rows.getValue("box-d").isNearLimit)
        assertTrue(rows.getValue("box-b").isMostSpare)
        assertTrue(rows.getValue("box-c").isMostSpare)
        assertFalse(rows.getValue("box-a").isMostSpare)
        val summary = vm.summary(vm.rows(nowMs = now), now)
        assertEquals("bob@example.com", summary.spare?.accountEmail)
        assertEquals(listOf("box-b", "box-c"), summary.spareBoxNames)
        // A sleeping box runs nothing, so it shows no live count.
        assertNull(rows.getValue("box-d").liveCount)
        assertEquals(2, rows.getValue("box-a").liveCount)
    }

    @Test
    fun rows_defaultsPreferTheBridgesResolvedOnes() = runBlocking {
        val resolved = status(10, null).let {
            it.copy(capacity = it.capacity.copy(defaults = BoxResolvedDefaults("codex", "gpt-example", "high")))
        }
        val fake = Fake()
        fake.devicesResult = Result.success(
            listOf(
                agent(1, "box-a", true, resolved, defaults = BoxDefaults(agent = "claude", model = "opus")),
                agent(2, "box-b", true, defaults = BoxDefaults(agent = "claude", model = "opus")),
                agent(3, "box-c", true),
            ),
        )
        val vm = makeVM(fake)
        vm.load()
        assertEquals(
            listOf("Codex · gpt-example · high", "Claude · Opus", null),
            vm.rows(nowMs = now).map { it.defaultsText },
        )
    }

    @Test
    fun load_failureSaysSo() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.failure(IllegalStateException("down"))
        val vm = makeVM(fake)
        vm.load()
        assertTrue(vm.state.value.hasLoaded)
        assertEquals("Couldn't load your boxes. Try again.", vm.state.value.errorMessage)
    }

    @Test
    fun watch_adoptsNewerFramesAndIgnoresOlderOnes() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, "box-a", true, status(40, null, agoMs = 60_000))))
        val vm = makeVM(fake)
        vm.load()
        val job = launch { vm.watch() }
        fake.feed.trySend(1L to status(70, null, agoMs = 10_000))
        waitUntil { vm.rows(nowMs = now).single().capacity?.limitLines?.single()?.percent == 70 }
        fake.feed.trySend(1L to status(20, null, agoMs = 120_000))
        repeat(20) { yield() }
        assertEquals(70, vm.rows(nowMs = now).single().capacity?.limitLines?.single()?.percent)
        job.cancel()
    }

    @Test
    fun watch_reloadsOnAReconnectAndOnThePoll() = runBlocking {
        val fake = Fake()
        val tick = Channel<Unit>(Channel.UNLIMITED)
        val vm = makeVM(fake, poll = { tick.receive() })
        vm.load()
        val job = launch { vm.watch() }
        repeat(10) { yield() }
        fake.connection.value = SyncConnectionState.Offline(null)
        repeat(10) { yield() }
        fake.connection.value = SyncConnectionState.Running
        waitUntil { fake.devicesCalls == 2 }
        tick.trySend(Unit)
        waitUntil { fake.devicesCalls == 3 }
        job.cancel()
    }

    @Test
    fun shownSessions_runningAlwaysThenWaitingUpToTheLiveCount() {
        fun session(id: String, running: Boolean, agoMin: Long?) =
            RosterSession(id, id, running, agoMin?.let { now - it * 60_000 })
        val sessions = listOf(
            session("w-old", false, 50),
            session("r", true, 30),
            session("w-new", false, 5),
            session("w-stale", false, 120),
        )
        assertEquals(
            listOf("r", "w-new"),
            BoxesViewModel.shownSessions(sessions, liveCount = 2, isOnline = true, nowMs = now).map { it.id },
        )
        // No count: the last hour's waiting ones.
        assertEquals(
            listOf("r", "w-new", "w-old"),
            BoxesViewModel.shownSessions(sessions, liveCount = null, isOnline = true, nowMs = now).map { it.id },
        )
        assertEquals(emptyList<RosterSession>(), BoxesViewModel.shownSessions(sessions, 4, isOnline = false, nowMs = now))
    }

    @Test
    fun anOlderLoadLandingLastDoesNotRewindANewerOne() = runBlocking {
        val fake = Fake()
        val asleep = agent(1, "box-a", connected = false)
        val gate = CompletableDeferred<Unit>()
        fake.devicesGate = gate
        val vm = makeVM(fake)
        val first = launch { vm.load() }
        waitUntil { fake.devicesCalls == 1 }
        // A newer load starts and lands while the first is still out.
        fake.devicesGate = null
        fake.devicesResult = Result.success(listOf(asleep.copy(connected = true)))
        vm.load()
        // The first answers last, with what was true when it was asked.
        fake.devicesResult = Result.success(listOf(asleep))
        gate.complete(Unit)
        first.join()
        assertEquals(BoxConnectionState.Online, vm.rows(nowMs = now).single().state)
    }

    @Test
    fun wake_staysWakingUntilTheReloadAfterItLands() = runBlocking {
        val fake = Fake()
        val asleep = agent(1, "box-a", connected = false)
        fake.devicesResult = Result.success(listOf(asleep))
        fake.rosterResult = Result.success(RosterSnapshot(wakeableBoxes = setOf(1)))
        val vm = makeVM(fake)
        vm.load()
        // The box answers at once (it is up); the reload is still out.
        fake.wakeReply = RPCReply.Ok(kotlinx.serialization.json.JsonObject(emptyMap()))
        val gate = CompletableDeferred<Unit>()
        fake.devicesGate = gate
        val job = launch { vm.wake(asleep) }
        waitUntil { fake.devicesCalls == 2 }
        assertEquals(NewChatViewModel.RowWake.Waking, vm.rows(nowMs = now).single().wake)
        fake.devicesResult = Result.success(listOf(asleep.copy(connected = true)))
        gate.complete(Unit)
        job.join()
        val row = vm.rows(nowMs = now).single()
        assertNull(row.wake)
        assertEquals(BoxConnectionState.Online, row.state)
    }

    @Test
    fun namesText() {
        assertEquals("box-a, box-b", BoxesSummary.namesText(listOf("box-a", "box-b")))
        assertEquals(
            "box-a, box-b, box-c and 2 more",
            BoxesSummary.namesText(listOf("box-a", "box-b", "box-c", "box-d", "box-e")),
        )
    }
}
