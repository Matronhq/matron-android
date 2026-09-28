package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The chooser reads every box's usage from the journal (journal PR #82):
/// `GET /devices` carries each box's last `status` report and the socket
/// fans live `box_status` frames. The local capacity cache is only the
/// fallback for a box the journal has no report for. Ported from
/// matron-apple's `NewChatViewModelBoxStatusTests`.
class NewChatViewModelBoxStatusTest {
    /// 2026-08-11 08:13 UTC — every report time below is relative to this.
    private val now = 1_754_900_000_000L

    private class Fake : AgentRPCProviding {
        var devicesResult: Result<List<DeviceDTO>> = Result.success(emptyList())
        val repliesByDevice = mutableMapOf<Long, RPCReply>()
        /// A box's `recent_folders` call completes [arrivals] on landing, then
        /// parks on its gate until the test opens it (a reply on the wire).
        val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val arrivals = mutableMapOf<Long, CompletableDeferred<Unit>>()
        data class Request(val method: String, val agentDeviceID: Long)
        val requests = mutableListOf<Request>()

        /// The live `box_status` feed: buffered, so a frame sent before the
        /// watcher's first collect still lands, as on the engine's socket.
        private val boxStatusFeed = Channel<Pair<Long, BoxStatus>>(Channel.UNLIMITED)

        override suspend fun devices(): List<DeviceDTO> = devicesResult.getOrThrow()
        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply {
            requests.add(Request(method, agentDeviceID))
            arrivals[agentDeviceID]?.complete(Unit)
            gates[agentDeviceID]?.await()
            return repliesByDevice[agentDeviceID] ?: RPCReply.Failure("unknown_method", null)
        }
        override fun boxStatusUpdates(): Flow<Pair<Long, BoxStatus>> = boxStatusFeed.receiveAsFlow()

        fun sendBoxStatus(deviceID: Long, status: BoxStatus) {
            boxStatusFeed.trySend(deviceID to status)
        }
    }

    private fun agent(id: Long, connected: Boolean, status: BoxStatus? = null) =
        device(id, kind = "agent", name = "box-$id", connected = connected).copy(status = status)

    private fun capacity(percent: Int, sessions: Int? = 2) = BoxCapacity(
        liveSessions = sessions,
        limitLines = listOf(LimitLine("session", "Current session", percent, null)),
        accountEmail = "pat@yearbook.com",
    )

    private fun report(percent: Int, agoMs: Long) = BoxStatus(now - agoMs, capacity(percent))

    private fun makeVM(fake: Fake, cache: InMemoryBoxCapacityCache = InMemoryBoxCapacityCache()) =
        NewChatViewModel(fake, cache, now = { now })

    private val emptyFolders = RPCReply.Ok(Json.parseToJsonElement("""{"folders":[]}"""))

    private val replied25 = RPCReply.Ok(Json.parseToJsonElement(
        """{"folders":[],"limits":{"lines":[{"id":"session","label":"Current session","percent":25}]}}""",
    ))

    private fun NewChatViewModel.percent(id: Long) = capacities.value[id]?.limitLines?.firstOrNull()?.percent

    /// Waits for the view model to apply a frame the fake has just sent: the
    /// watcher collects on its own coroutine.
    private suspend fun waitUntil(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            yield()
        }
        assertTrue("condition never became true", condition())
    }

    // MARK: Seeding from GET /devices

    @Test
    fun load_seedsAnOfflineBoxFromItsJournalReportAgedByReportedAt() = runBlocking {
        val fake = Fake()
        val reported = report(39, agoMs = 2 * 3_600_000)
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false, reported)))
        fake.repliesByDevice[1] = emptyFolders
        val vm = makeVM(fake)
        vm.load()
        assertEquals(
            "a sleeping box shows what it last told the journal, with no cache involved",
            reported.capacity, vm.capacities.value[2L],
        )
        assertEquals(
            "the caption reads when the box reported, not when this device last asked",
            AgentCapacityFreshness.Offline(reported.reportedAtMs), vm.capacityFreshness(2),
        )
        assertFalse("a sleeping box is still never queried", fake.requests.any { it.agentDeviceID == 2L })
    }

    @Test
    fun load_prefersTheJournalReportOverTheLocalCache() = runBlocking {
        val fake = Fake()
        val reported = report(71, agoMs = 3 * 3_600_000)
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false, reported)))
        fake.repliesByDevice[1] = emptyFolders
        // A newer local capture must not win: it is this device's memory of
        // one reply, while the journal holds the box's own latest word.
        val cache = InMemoryBoxCapacityCache(mapOf(2L to CachedBoxCapacity(capacity(5), now - 60_000)))
        val vm = makeVM(fake, cache)
        vm.load()
        assertEquals(71, vm.percent(2))
    }

    @Test
    fun load_fallsBackToTheCacheForABoxTheJournalHasNoReportFor() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false)))
        fake.repliesByDevice[1] = emptyFolders
        val capturedAt = now - 3_600_000
        val cache = InMemoryBoxCapacityCache(mapOf(2L to CachedBoxCapacity(capacity(12), capturedAt)))
        val vm = makeVM(fake, cache)
        vm.load()
        assertEquals(
            "an older journal, or a box that never reported, still shows last-known numbers",
            12, vm.percent(2),
        )
        assertEquals(AgentCapacityFreshness.Offline(capturedAt), vm.capacityFreshness(2))
    }

    /// The seven-day cut-off now measures the box's own report: a box that
    /// has not reported in a week describes quota windows long rolled over.
    @Test
    fun load_ignoresAReportOlderThanTheAgeLimit() = runBlocking {
        val fake = Fake()
        val limit = NewChatViewModel.MAX_CACHED_CAPACITY_AGE_MS
        fake.devicesResult = Result.success(listOf(
            agent(1, false, report(39, agoMs = limit + 60_000)),
            agent(2, false, report(40, agoMs = limit)),
        ))
        val vm = makeVM(fake)
        vm.load()
        assertNull(vm.capacities.value[1L])
        assertEquals("nothing shown, nothing to caption", AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        assertEquals("the boundary is inclusive", 40, vm.percent(2))
    }

    /// A connected box's report is the box's own recent word, so its row
    /// fills in at once instead of reading "Checking…" until the fan-out
    /// answers — and the answer, when it lands, replaces it.
    @Test
    fun load_showsAConnectedBoxsReportWhileItsFanOutIsInFlight() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true, report(20, agoMs = 60_000)), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Ok(Json.parseToJsonElement(
            """{"folders":[],"limits":{"lines":[{"id":"session","label":"Current session","percent":25}]}}""",
        ))
        val gate = CompletableDeferred<Unit>()
        val arrival = CompletableDeferred<Unit>()
        fake.gates[1] = gate
        fake.arrivals[1] = arrival
        val vm = makeVM(fake)
        val loading = launch { vm.load() }
        arrival.await()

        assertEquals(20, vm.percent(1))
        assertEquals(
            "a connected box is not offline, and its answer is seconds away",
            AgentCapacityFreshness.Live, vm.capacityFreshness(1),
        )
        assertTrue("the fan-out is still asked, for folders and live numbers", 1L in vm.capacityPending.value)

        gate.complete(Unit)
        loading.join()
        assertEquals(25, vm.percent(1))
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
    }

    /// A connected box that doesn't answer used to lose its row entirely.
    /// The journal still holds what the box last reported, so the row keeps
    /// it — de-emphasised and aged, because it is no longer vouched for.
    @Test
    fun fanOutFailure_fallsBackToTheJournalReportCaptionedWithItsAge() = runBlocking {
        val fake = Fake()
        val reported = report(20, agoMs = 600_000)
        fake.devicesResult = Result.success(listOf(agent(1, true, reported), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
        val vm = makeVM(fake)
        vm.load()
        assertEquals(reported.capacity, vm.capacities.value[1L])
        assertEquals(AgentCapacityFreshness.Reported(reported.reportedAtMs), vm.capacityFreshness(1))
        assertEquals(
            "the freshness is observable on its own — the sheet recomposes on it",
            AgentCapacityFreshness.Reported(reported.reportedAtMs), vm.capacityStaleness.value[1L],
        )
    }

    @Test
    fun fanOutFailure_withNoReportLeavesThePlainRow() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
        val vm = makeVM(fake)
        vm.load()
        assertNull(vm.capacities.value[1L])
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
    }

    // MARK: Live box_status frames

    @Test
    fun boxStatusFrame_updatesAnOfflineRowInPlace() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false, report(39, agoMs = 3_600_000))))
        fake.repliesByDevice[1] = emptyFolders
        val vm = makeVM(fake)
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            val fresh = report(44, agoMs = 5_000)
            fake.sendBoxStatus(2, fresh)
            waitUntil { vm.percent(2) == 44 }
            assertEquals(AgentCapacityFreshness.Offline(fresh.reportedAtMs), vm.capacityFreshness(2))
        } finally {
            watcher.cancel()
        }
    }

    @Test
    fun boxStatusFrame_forAConnectedBoxReadsLive() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
        val vm = makeVM(fake)
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            assertNull(vm.capacities.value[1L])
            fake.sendBoxStatus(1, report(61, agoMs = 1_000))
            waitUntil { vm.percent(1) == 61 }
            assertEquals("the box is reporting right now", AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        } finally {
            watcher.cancel()
        }
    }

    /// A connected box whose fan-out failed reads `Reported`; a fresh frame
    /// from it lifts the caption even when its numbers didn't move — the
    /// freshness changes while the capacity map stays equal.
    @Test
    fun boxStatusFrame_liftsTheReportedCaptionEvenWithUnchangedNumbers() = runBlocking {
        val fake = Fake()
        val reported = report(20, agoMs = 600_000)
        fake.devicesResult = Result.success(listOf(agent(1, true, reported), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
        val vm = makeVM(fake)
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            assertEquals(AgentCapacityFreshness.Reported(reported.reportedAtMs), vm.capacityFreshness(1))
            fake.sendBoxStatus(1, report(20, agoMs = 1_000))
            waitUntil { vm.capacityStaleness.value[1L] == null }
            assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        } finally {
            watcher.cancel()
        }
    }

    /// Frames and the roster race: a report the socket delivered first must
    /// not be overwritten by an older one `GET /devices` answers with later.
    @Test
    fun anOlderReportNeverReplacesANewerOne() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(
            agent(1, true), agent(2, false, report(10, agoMs = 3_600_000)), agent(3, false),
        ))
        fake.repliesByDevice[1] = emptyFolders
        val vm = makeVM(fake)
        val watcher = launch { vm.watchBoxStatus() }
        try {
            fake.sendBoxStatus(2, report(90, agoMs = 30_000))
            waitUntil { vm.hasReportForTesting(2) }
            vm.load()
            assertEquals(90, vm.percent(2))

            // A late, older frame is dropped too; box 3's frame behind it marks
            // the point where the stale one has certainly been processed (an
            // offline box, so nothing newer can outrank the marker).
            fake.sendBoxStatus(2, report(5, agoMs = 7_200_000))
            fake.sendBoxStatus(3, report(61, agoMs = 1_000))
            waitUntil { vm.percent(3) == 61 }
            assertEquals(90, vm.percent(2))
        } finally {
            watcher.cancel()
        }
    }

    /// Off the roster (auto-skipped into a single box's folder step), a
    /// frame is only held for the next `load()` — nothing is painted.
    @Test
    fun boxStatusFrame_offTheRosterIsOnlyHeld() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true)))
        fake.repliesByDevice[1] = emptyFolders
        val vm = makeVM(fake)
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            assertTrue(vm.phase.value is NewChatViewModel.Phase.Folders)
            fake.sendBoxStatus(1, report(33, agoMs = 1_000))
            waitUntil { vm.hasReportForTesting(1) }
            assertNull(vm.capacities.value[1L])
        } finally {
            watcher.cancel()
        }
    }

    /// A frame can arrive late, or in a backlog the socket delivers after a
    /// reconnect. For a connected box whose fan-out has already answered,
    /// a frame reported before that reply carries older numbers than the
    /// row shows — it must not repaint the row (CodeRabbit, #80).
    @Test
    fun boxStatusFrame_olderThanTheFanOutReplyLeavesAConnectedRowAlone() = runBlocking {
        var clock = now
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false)))
        fake.repliesByDevice[1] = replied25
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache(), now = { clock })
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            assertEquals(25, vm.percent(1))

            // Reported before the reply landed: stale by the time it arrives.
            // Box 2's frame behind it marks the point where it has certainly
            // been processed.
            fake.sendBoxStatus(1, BoxStatus(clock - 30_000, capacity(61)))
            fake.sendBoxStatus(2, BoxStatus(clock - 1_000, capacity(7)))
            waitUntil { vm.percent(2) == 7 }
            assertEquals("older numbers never replace the live reply", 25, vm.percent(1))
            assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))

            // A frame the box reported after the reply is its newer word.
            clock += 60_000
            fake.sendBoxStatus(1, BoxStatus(clock - 5_000, capacity(90)))
            waitUntil { vm.percent(1) == 90 }
            assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        } finally {
            watcher.cancel()
        }
    }

    /// The fan-out suspends on the wire. A frame that lands meanwhile is the
    /// box's newer word, so the reply — computed before it — must not put
    /// older numbers back over it (CodeRabbit, #80). The reply's folders
    /// are still taken: they are not what the frame carries.
    @Test
    fun fanOutReply_doesNotOverwriteANewerFrameThatLandedDuringTheRequest() = runBlocking {
        var clock = now
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true, report(10, agoMs = 600_000)), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Ok(Json.parseToJsonElement(
            """{"folders":[{"path":"/home/pat/app","last_used":1754899200000}],""" +
                """"limits":{"lines":[{"id":"session","label":"Current session","percent":25}]}}""",
        ))
        val gate = CompletableDeferred<Unit>()
        val arrival = CompletableDeferred<Unit>()
        fake.gates[1] = gate
        fake.arrivals[1] = arrival
        val cache = InMemoryBoxCapacityCache()
        val vm = NewChatViewModel(fake, cache, now = { clock })
        val watcher = launch { vm.watchBoxStatus() }
        try {
            val loading = launch { vm.load() }
            arrival.await()
            assertEquals("the seed, while the fan-out is in flight", 10, vm.percent(1))

            clock += 10_000
            fake.sendBoxStatus(1, BoxStatus(clock - 1_000, capacity(90)))
            waitUntil { vm.percent(1) == 90 }

            gate.complete(Unit)
            loading.join()
            assertEquals("the frame is the newer word", 90, vm.percent(1))
            assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
            assertNull("the reply's numbers are not cached over the frame either", cache.loadAll()[1L])
            assertEquals("the reply still warms the folder cache", 1, vm.cachedFoldersForTesting(1)?.size)
        } finally {
            watcher.cancel()
        }
    }

    /// The same race on the failure path: a frame that lands during the
    /// request has already painted the row live. The failed request has
    /// nothing to say about those numbers, so it must not demote them to
    /// `Reported` (CodeRabbit, #80).
    @Test
    fun fanOutFailure_keepsAFrameThatLandedDuringTheRequestLive() = runBlocking {
        var clock = now
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true, report(10, agoMs = 600_000)), agent(2, false)))
        fake.repliesByDevice[1] = RPCReply.Failure("internal", null)
        val gate = CompletableDeferred<Unit>()
        val arrival = CompletableDeferred<Unit>()
        fake.gates[1] = gate
        fake.arrivals[1] = arrival
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache(), now = { clock })
        val watcher = launch { vm.watchBoxStatus() }
        try {
            val loading = launch { vm.load() }
            arrival.await()

            clock += 10_000
            val fresh = BoxStatus(clock - 1_000, capacity(90))
            fake.sendBoxStatus(1, fresh)
            waitUntil { vm.percent(1) == 90 }

            gate.complete(Unit)
            loading.join()
            assertEquals(90, vm.percent(1))
            assertEquals("the box reported while we waited; that word is live", AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        } finally {
            watcher.cancel()
        }
    }

    // MARK: Reload after the folder step

    /// A frame that lands while the folder step is showing is only held. Back
    /// on the roster, the previous visit's live numbers for that connected box
    /// survive the reload (stale-while-revalidate) — but they are older than
    /// the held report, so the report has to win the seed.
    @Test
    fun reload_seedsANewerHeldReportOverLastVisitsLiveNumbers() = runBlocking {
        var clock = now
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true), agent(2, false)))
        fake.repliesByDevice[1] = replied25
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache(), now = { clock })
        val watcher = launch { vm.watchBoxStatus() }
        try {
            vm.load()
            assertEquals(25, vm.percent(1))

            vm.select(agent(1, true))
            clock += 60_000
            fake.sendBoxStatus(1, BoxStatus(clock - 5_000, capacity(90)))
            waitUntil { vm.hasReportForTesting(1) }

            val gate = CompletableDeferred<Unit>()
            val arrival = CompletableDeferred<Unit>()
            fake.gates[1] = gate
            fake.arrivals[1] = arrival
            val reloading = launch { vm.backToAgents() }
            arrival.await()
            assertEquals("the held report is newer than last visit's reply", 90, vm.percent(1))
            gate.complete(Unit)
            reloading.join()
        } finally {
            watcher.cancel()
        }
    }

    /// The other way round: a reply newer than the journal's report keeps its
    /// row across the reload rather than stepping back to older numbers.
    @Test
    fun reload_keepsLastVisitsLiveNumbersWhenTheReportIsOlder() = runBlocking {
        var clock = now
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, true, report(10, agoMs = 600_000)), agent(2, false)))
        fake.repliesByDevice[1] = replied25
        val vm = NewChatViewModel(fake, InMemoryBoxCapacityCache(), now = { clock })
        vm.load()

        val gate = CompletableDeferred<Unit>()
        val arrival = CompletableDeferred<Unit>()
        fake.gates[1] = gate
        fake.arrivals[1] = arrival
        clock += 30_000
        val reloading = launch { vm.backToAgents() }
        arrival.await()
        assertEquals(25, vm.percent(1))
        gate.complete(Unit)
        reloading.join()
    }
}
