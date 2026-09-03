package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `NewChatViewModelOfflineCapacityTests` (#164):
/// sleeping boxes are seeded from the capacity cache, captioned with their
/// age, never asked, and every live reply is persisted for next time.
class NewChatViewModelOfflineCapacityTest {
    private val now = 1_754_900_000_000L

    private class Fake : AgentRPCProviding {
        var devicesResult: Result<List<DeviceDTO>> = Result.success(emptyList())
        var replies: MutableMap<String, RPCReply> = mutableMapOf()
        var repliesByDevice: MutableMap<Long, RPCReply> = mutableMapOf()
        data class Request(val method: String, val agentDeviceID: Long)
        val requests = mutableListOf<Request>()
        override suspend fun devices(): List<DeviceDTO> = devicesResult.getOrThrow()
        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply {
            requests.add(Request(method, agentDeviceID))
            if (method == "recent_folders") repliesByDevice[agentDeviceID]?.let { return it }
            return replies[method] ?: RPCReply.Failure("unknown_method", null)
        }
    }

    private fun agent(id: Long, name: String, connected: Boolean) =
        device(id, kind = "agent", name = name, connected = connected)

    private fun ok(json: String) = RPCReply.Ok(Json.parseToJsonElement(json))

    private fun capacity(percent: Int, email: String? = "pat@yearbook.com") = BoxCapacity(
        liveSessions = 2,
        limitLines = listOf(LimitLine("session", "Current session", percent, null)),
        accountEmail = email,
    )

    private fun makeVM(fake: Fake, cache: InMemoryBoxCapacityCache) = NewChatViewModel(fake, cache, now = { now })

    private fun twoAwakeOneAsleep(fake: Fake, asleepName: String = "sleeping") {
        fake.devicesResult = Result.success(
            listOf(agent(1, "a", true), agent(2, "b", true), agent(3, asleepName, false)),
        )
        fake.repliesByDevice[1] = ok("""{"folders":[],"activity":{"live_sessions":1}}""")
        fake.repliesByDevice[2] = ok("""{"folders":[]}""")
    }

    @Test
    fun load_seedsOfflineBoxesFromTheCacheWithoutAskingThem() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake)
        val cached = capacity(39)
        val cache = InMemoryBoxCapacityCache(mapOf(3L to CachedBoxCapacity(cached, now - 2 * 3_600_000)))
        val vm = makeVM(fake, cache)
        vm.load()
        assertEquals(cached, vm.capacities.value[3L])
        assertFalse("a sleeping box is still never queried", fake.requests.any { it.agentDeviceID == 3L })
        assertFalse(vm.capacityPending.value.contains(3L))
    }

    @Test
    fun load_marksSeededRowsStaleAndRefreshedOnesLive() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake)
        val capturedAt = now - 2 * 3_600_000
        val vm = makeVM(fake, InMemoryBoxCapacityCache(mapOf(3L to CachedBoxCapacity(capacity(39), capturedAt))))
        vm.load()
        assertEquals(AgentCapacityFreshness.Offline(capturedAt), vm.capacityFreshness(3))
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(1))
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(99))
    }

    @Test
    fun load_seedsEveryRowWhenTheWholeFleetIsAsleep() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, "a", false), agent(2, "b", false)))
        val cache = InMemoryBoxCapacityCache(mapOf(
            1L to CachedBoxCapacity(capacity(12), now - 3_600_000),
            2L to CachedBoxCapacity(capacity(93), now - 3_600_000),
        ))
        val vm = makeVM(fake, cache)
        vm.load()
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Agents)
        assertEquals(12, vm.capacities.value[1L]?.limitLines?.first()?.percent)
        assertEquals(93, vm.capacities.value[2L]?.limitLines?.first()?.percent)
        assertTrue("there is nobody awake to ask", fake.requests.isEmpty())
    }

    @Test
    fun load_ignoresCacheEntriesTooOldToMeanAnything() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake, "long-gone")
        val vm = makeVM(fake, InMemoryBoxCapacityCache(mapOf(3L to CachedBoxCapacity(capacity(39), now - 8 * 86_400_000))))
        vm.load()
        assertNull(vm.capacities.value[3L])
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(3))
    }

    @Test
    fun load_seedsAnEntryExactlyAtTheAgeLimit() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake, "borderline")
        val limit = NewChatViewModel.MAX_CACHED_CAPACITY_AGE_MS
        val vm = makeVM(fake, InMemoryBoxCapacityCache(mapOf(3L to CachedBoxCapacity(capacity(39), now - limit))))
        vm.load()
        assertNotNull("exactly at the limit is still inside it", vm.capacities.value[3L])
    }

    @Test
    fun load_prunesCachedBoxesThatLeftTheRoster() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake)
        val cache = InMemoryBoxCapacityCache(mapOf(
            3L to CachedBoxCapacity(capacity(39), now),
            99L to CachedBoxCapacity(capacity(10), now),
        ))
        makeVM(fake, cache).load()
        assertEquals(setOf(1L, 2L, 3L), cache.pruneCalls.last())
        assertNull(cache.loadAll()[99L])
    }

    /// CodeRabbit (#51): a one-box fleet auto-skips the roster and never fans
    /// out, so the prune must not live only on the fan-out path — a departed
    /// box's quota and account email would otherwise persist forever.
    @Test
    fun load_singleAgentFleet_stillPrunesDepartedBoxes() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(9, "only", true)))
        fake.replies["recent_folders"] = ok("""{"folders":[]}""")
        val cache = InMemoryBoxCapacityCache(mapOf(99L to CachedBoxCapacity(capacity(10), now)))
        makeVM(fake, cache).load()
        assertEquals(setOf(9L), cache.pruneCalls.last())
        assertNull("the departed box is gone from the cache", cache.loadAll()[99L])
    }

    @Test
    fun fanOut_persistsEveryCapacityItParses() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(1, "a", true), agent(2, "b", true)))
        fake.repliesByDevice[1] = ok("""{"folders":[],"activity":{"live_sessions":2},"account":{"email":"pat@yearbook.com"},"limits":{"lines":[{"id":"session","label":"Current session","percent":39}]}}""")
        fake.repliesByDevice[2] = ok("""{"folders":[]}""")
        val cache = InMemoryBoxCapacityCache()
        val vm = makeVM(fake, cache)
        vm.load()
        assertEquals(vm.capacities.value[1L], cache.loadAll()[1L]?.capacity)
        assertEquals(now, cache.loadAll()[1L]?.capturedAtMs)
    }

    /// A fleet with one connected box auto-skips the roster and never fans out
    /// — the live folder reply is the only chance to learn its capacity.
    @Test
    fun select_persistsCapacityFromTheLiveFolderReply() = runBlocking {
        val fake = Fake()
        fake.devicesResult = Result.success(listOf(agent(9, "only", true)))
        fake.replies["recent_folders"] = ok("""{"folders":[{"path":"/w","last_used":1}],"activity":{"live_sessions":3},"account":{"email":"pat@yearbook.com"}}""")
        val cache = InMemoryBoxCapacityCache()
        makeVM(fake, cache).load()
        assertEquals(3, cache.loadAll()[9L]?.capacity?.liveSessions)
        assertEquals(now, cache.loadAll()[9L]?.capturedAtMs)
    }

    /// Bugbot (#51): the capacity is recorded off the answer, not the phase —
    /// a start fired from the custom-folder field while the live folder reply
    /// is still in flight moves the phase to Done, and the quota must still land.
    @Test
    fun select_persistsCapacityEvenIfThePhaseMovedOnMeanwhile() = runBlocking {
        val fake = object : AgentRPCProviding {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            override suspend fun devices() = listOf(agent(9, "only", true))
            override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply {
                if (method == "recent_folders") { gate.await(); return ok("""{"folders":[],"activity":{"live_sessions":3}}""") }
                return ok("""{"convo_id":"c-new"}""")
            }
        }
        val cache = InMemoryBoxCapacityCache()
        val vm = NewChatViewModel(fake, cache, now = { now })
        val loading = launch { vm.load() } // auto-skips into select(), parks on the reply
        yield()
        vm.start("/w") // custom path, before the folder reply lands → phase Done
        assertTrue(vm.phase.value is NewChatViewModel.Phase.Done)
        fake.gate.complete(Unit)
        loading.join()
        assertEquals(3, cache.loadAll()[9L]?.capacity?.liveSessions)
    }

    /// A cache seed for a box that has since come online is dropped on the
    /// next load rather than laundered into a live-looking row.
    @Test
    fun reload_dropsASeedForABoxThatCameOnline() = runBlocking {
        val fake = Fake(); twoAwakeOneAsleep(fake)
        val cache = InMemoryBoxCapacityCache(mapOf(3L to CachedBoxCapacity(capacity(39), now - 3_600_000)))
        val vm = makeVM(fake, cache)
        vm.load()
        assertNotNull(vm.capacities.value[3L])
        // Box 3 wakes up but its fan-out fails: no live numbers, no stale seed either.
        fake.devicesResult = Result.success(listOf(agent(1, "a", true), agent(2, "b", true), agent(3, "sleeping", true)))
        fake.repliesByDevice[3] = RPCReply.Failure("agent_unreachable", null)
        vm.load()
        assertNull(vm.capacities.value[3L])
        assertEquals(AgentCapacityFreshness.Live, vm.capacityFreshness(3))
    }
}
