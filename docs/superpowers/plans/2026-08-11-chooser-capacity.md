# New Chat Chooser Capacity Implementation Plan (Android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Apple chooser-capacity feature: per-box live sessions, all usage-limit lines, and account email on the New Chat machine-picker rows.

**Architecture:** Mirror of matron-apple's design (spec `docs/superpowers/specs/2026-08-11-chooser-capacity-design.md` here, full rationale in the apple repo's same-named spec). `NewChatViewModel.kt` fans `recent_folders` out to connected agents, fills a `StateFlow<Map<Long, BoxCapacity>>` + folder cache; `NewChatSheet.kt` renders the rows.

**Tech Stack:** Kotlin, Compose, kotlinx.serialization, JUnit4 + `runBlocking` VM tests. JAVA_HOME recipe: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before `./gradlew`.

## Global Constraints

- Every capacity key is optional wire-side; malformed blocks degrade to null/empty and never fail the folders parse.
- Rows stay pickable at all times; capacity is display-only.
- Percent tints: green < 50, orange < 80, red ≥ 80 — match `designsystem` color conventions (reuse existing status colors if the module defines them; otherwise `Color(0xFF3FB950)` green, `Color(0xFFF0883E)` orange, `Color(0xFFF85149)` red — the bridge's own hexes).
- Unit tests: `./gradlew testDebugUnitTest --tests 'chat.matron.android.viewmodels.*' --tests 'chat.matron.android.models.*'` and confirm the executed-test count in the output.

---

### Task 1: `BoxCapacity` parsing

**Files:**
- Create: `app/src/main/java/chat/matron/android/viewmodels/BoxCapacity.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/BoxCapacityTest.kt`

**Interfaces (produced, used by Tasks 2–3):**

```kotlin
data class LimitLine(val id: String, val label: String, val percent: Int, val resetsAt: Long?) // epoch ms
data class BoxCapacity(
    val liveSessions: Int?,
    val limitLines: List<LimitLine>,
    val accountEmail: String?,
) {
    companion object {
        fun parse(reply: JsonElement): BoxCapacity
        /// "resets 11:59 PM" if same local day, "resets Aug 15" otherwise, null for null.
        fun resetText(resetsAt: Long?, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String?
    }
}
```

- [ ] **Step 1: Write the failing tests** (`BoxCapacityTest.kt`, same JSON fixtures as the apple `BoxCapacityTests`):

```kotlin
package chat.matron.android.viewmodels

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.ZoneId

class BoxCapacityTest {
    private fun parse(json: String) = BoxCapacity.parse(Json.parseToJsonElement(json))

    @Test
    fun parse_fullBlock() {
        val c = parse(
            """{"folders":[],
                "activity":{"live_sessions":2,"last_hour":[{"path":"/w","sessions":1}]},
                "limits":{"as_of":1754900000000,"lines":[
                   {"id":"session","label":"Current session","percent":39,"resets_at":"2026-08-11T23:59:00Z"},
                   {"id":"week","label":"Current week (all models)","percent":66}]},
                "account":{"email":"pat@yearbook.com"}}""",
        )
        assertEquals(2, c.liveSessions)
        assertEquals(listOf("Current session", "Current week (all models)"), c.limitLines.map { it.label })
        assertEquals(39, c.limitLines[0].percent)
        assertNotNull(c.limitLines[0].resetsAt)
        assertNull(c.limitLines[1].resetsAt)
        assertEquals("pat@yearbook.com", c.accountEmail)
    }

    @Test
    fun parse_missingBlocks_degradeToEmpty() {
        val c = parse("""{"folders":[]}""")
        assertNull(c.liveSessions)
        assertTrue(c.limitLines.isEmpty())
        assertNull(c.accountEmail)
    }

    @Test
    fun parse_malformedEntries_dropLineNotBlock() {
        val c = parse(
            """{"limits":{"lines":[
                 {"id":"ok","label":"Fine","percent":10},
                 {"id":"bad","label":"No percent"},
                 {"label":"No id","percent":5}]},
                "account":{"email":42},
                "activity":{"live_sessions":"two"}}""",
        )
        assertEquals(listOf("ok"), c.limitLines.map { it.id })
        assertNull(c.accountEmail)
        assertNull(c.liveSessions)
    }

    @Test
    fun parse_percentClamped() {
        val c = parse("""{"limits":{"lines":[{"id":"a","label":"A","percent":-5},{"id":"b","label":"B","percent":5000}]}}""")
        assertEquals(listOf(0, 999), c.limitLines.map { it.percent })
    }

    @Test
    fun resetText_todayVsLater() {
        val zone = ZoneId.of("UTC")
        val now = 1_754_900_000_000L // 2026-08-11 UTC
        val today = now + 2 * 3_600_000L
        val nextWeek = now + 4 * 86_400_000L
        assertTrue(BoxCapacity.resetText(today, now, zone)!!.startsWith("resets "))
        assertFalse(BoxCapacity.resetText(today, now, zone)!!.contains("Aug"))
        assertTrue(BoxCapacity.resetText(nextWeek, now, zone)!!.contains("Aug"))
        assertNull(BoxCapacity.resetText(null, now, zone))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --tests 'chat.matron.android.viewmodels.BoxCapacityTest' 2>&1 | tail -8`
Expected: compile FAILURE (`BoxCapacity` unresolved).

- [ ] **Step 3: Implement `BoxCapacity.kt`** (use the journal JSON helpers already imported by `NewChatViewModel.kt` — `objects`, `stringOrNull`, `longOrNull`, `arrayOrNull`; add an `intOrNull` sibling only if one doesn't already exist in `journal`):

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/// One usage-limit meter from a bridge's `limits.lines`
/// (spec: 2026-08-11-chooser-capacity-design.md).
data class LimitLine(val id: String, val label: String, val percent: Int, val resetsAt: Long?)

/// The capacity blocks a bridge attaches to its `recent_folders` reply.
/// Every block is optional wire-side, so parsing degrades per-block and can
/// never fail the folders parse it rides along with. Port of matron-apple's
/// `BoxCapacity`.
data class BoxCapacity(
    val liveSessions: Int?,
    val limitLines: List<LimitLine>,
    val accountEmail: String?,
) {
    companion object {
        fun parse(reply: JsonElement): BoxCapacity {
            val obj = reply as? JsonObject ?: return BoxCapacity(null, emptyList(), null)
            val activity = obj["activity"] as? JsonObject
            val live = (activity?.get("live_sessions") as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

            val lines = (obj["limits"] as? JsonObject)?.arrayOrNull("lines")?.objects().orEmpty()
                .mapNotNull { line ->
                    val id = line.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val label = line.stringOrNull("label")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val percent = (line["percent"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
                        ?: return@mapNotNull null
                    val resetsAt = line.stringOrNull("resets_at")?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                    }
                    LimitLine(id, label, percent.coerceIn(0, 999), resetsAt)
                }

            val email = (obj["account"] as? JsonObject)?.stringOrNull("email")?.takeIf { it.isNotEmpty() }
            return BoxCapacity(live, lines, email)
        }

        fun resetText(
            resetsAt: Long?,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String? {
            if (resetsAt == null) return null
            val date = Instant.ofEpochMilli(resetsAt).atZone(zone)
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val pattern = if (date.toLocalDate() == now.toLocalDate()) "h:mm a" else "MMM d"
            return "resets " + date.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        }
    }
}
```

(If `JsonPrimitive.intOrNull` casts read awkwardly against the journal helpers' style, add `fun JsonObject.intOrNull(key: String): Int?` beside the existing helpers in the `journal` package and use it — match whichever file defines `longOrNull`.)

- [ ] **Step 4: Run to verify pass**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --tests 'chat.matron.android.viewmodels.BoxCapacityTest' 2>&1 | tail -8`
Expected: 5 tests passing (confirm the count).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/BoxCapacity.kt app/src/test/java/chat/matron/android/viewmodels/BoxCapacityTest.kt
git commit -m "feat(new-chat): parse the recent_folders capacity blocks"
```

---

### Task 2: Fan-out + caches in `NewChatViewModel.kt`

**Files:**
- Modify: `app/src/main/java/chat/matron/android/viewmodels/NewChatViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/NewChatViewModelTest.kt`

**Interfaces:**
- Consumes: `BoxCapacity.parse` (Task 1).
- Produces (used by Task 3):

```kotlin
val capacities: StateFlow<Map<Long, BoxCapacity>>
val capacityPending: StateFlow<Set<Long>>
```

Behaviour notes:
- `load()` fans out AFTER `_phase.value = Phase.Agents(...)` — the fan-out runs in the caller's coroutine via `coroutineScope { launch { ... } }` around per-agent fetches, so `load()` suspends until the fan-out completes; the roster is already visible because the phase StateFlow was set first. (This matches how the sheet calls `load()` in a `LaunchedEffect` and keeps the VM test synchronous under `runBlocking` — no test-only await hook needed, unlike the apple port.)
- Auto-skip path (exactly one connected agent) does NOT fan out.
- Per-agent failure: swallow (except `CancellationException` — rethrow), leave no `capacities` entry, always clear pending.
- Fan-out replies also fill a `folderCache: MutableMap<Long, List<RecentFolder>>`; `select()` serves from it when present, else does the live RPC exactly as today.

- [ ] **Step 1: Write the failing tests** — extend `FakeAgentRPCProvider` with `var repliesByDevice: MutableMap<Long, RPCReply> = mutableMapOf()` consulted first for `recent_folders`, then append:

```kotlin
    @Test
    fun load_fansOutToConnectedAgentsOnly() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(
            listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true), agent(3, name = "c", connected = false)),
        )
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"account":{"email":"pat@yearbook.com"},"activity":{"live_sessions":2}}""")
        fake.repliesByDevice[2] = foldersReply("""{"folders":[]}""")
        val vm = NewChatViewModel(fake)
        vm.load()
        assertEquals(listOf(1L, 2L), fake.requests.filter { it.method == "recent_folders" }.map { it.agentDeviceID }.sorted())
        assertEquals("pat@yearbook.com", vm.capacities.value[1L]?.accountEmail)
        assertEquals(2, vm.capacities.value[1L]?.liveSessions)
        assertEquals(BoxCapacity(null, emptyList(), null), vm.capacities.value[2L])
        assertTrue(vm.capacityPending.value.isEmpty())
    }

    @Test
    fun fanOut_oneFailingBoxDegradesAlone() = runBlocking {
        val fake = FakeAgentRPCProvider()
        fake.devicesResult = Result.success(listOf(agent(1, name = "a", connected = true), agent(2, name = "b", connected = true)))
        fake.repliesByDevice[1] = foldersReply("""{"folders":[],"activity":{"live_sessions":1}}""")
        fake.repliesByDevice[2] = RPCReply.Failure("agent_unreachable", null)
        val vm = NewChatViewModel(fake)
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
        val vm = NewChatViewModel(fake)
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
        val vm = NewChatViewModel(fake)
        vm.load()
        fake.repliesByDevice[1] = foldersReply("""{"folders":[{"path":"/late","last_used":1}]}""")
        vm.select(agents[0])
        assertEquals(listOf("/late"), vm.folders.value.map { it.path })
    }
```

Also verify the existing single-connected-agent auto-skip test still passes unmodified (auto-skip must not fan out).

- [ ] **Step 2: Run to verify failure**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --tests 'chat.matron.android.viewmodels.NewChatViewModelTest' 2>&1 | tail -8`
Expected: compile FAILURE (`capacities` unresolved).

- [ ] **Step 3: Implement.** In `NewChatViewModel`:

```kotlin
    private val _capacities = MutableStateFlow<Map<Long, BoxCapacity>>(emptyMap())
    val capacities: StateFlow<Map<Long, BoxCapacity>> = _capacities.asStateFlow()

    private val _capacityPending = MutableStateFlow<Set<Long>>(emptySet())
    val capacityPending: StateFlow<Set<Long>> = _capacityPending.asStateFlow()

    private val folderCache = mutableMapOf<Long, List<RecentFolder>>()
```

In `load()`, in the `else` branch after `_phase.value = Phase.Agents(sorted(agents))`:

```kotlin
                val connectedIDs = connected.map { it.id }
                _capacityPending.value = connectedIDs.toSet()
                coroutineScope {
                    for (id in connectedIDs) launch { fetchCapacity(id) }
                }
```

New private method:

```kotlin
    private suspend fun fetchCapacity(agentID: Long) {
        try {
            val reply = api.agentRequest(agentID, "recent_folders", "{}")
            if (reply is RPCReply.Ok) {
                _capacities.value = _capacities.value + (agentID to BoxCapacity.parse(reply.result))
                folderCache[agentID] = parseFolders(reply.result)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // capacity is a convenience — the row just stays plain
        } finally {
            _capacityPending.value = _capacityPending.value - agentID
        }
    }
```

In `select()`, before the live RPC:

```kotlin
        folderCache[agent.id]?.let {
            _folders.value = it
            return
        }
```

Imports to add: `kotlinx.coroutines.coroutineScope`, `kotlinx.coroutines.launch`.

- [ ] **Step 4: Run the whole VM suite**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest --tests 'chat.matron.android.viewmodels.NewChatViewModelTest' 2>&1 | tail -8`
Expected: all tests pass, count includes the 4 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/NewChatViewModel.kt app/src/test/java/chat/matron/android/viewmodels/NewChatViewModelTest.kt
git commit -m "feat(new-chat): fan recent_folders out to every connected box on sheet open"
```

---

### Task 3: Row UI in `NewChatSheet.kt`

**Files:**
- Modify: `app/src/main/java/chat/matron/android/features/chatlist/NewChatSheet.kt` (the agent-picker row composable)

**Interfaces:**
- Consumes: `vm.capacities`, `vm.capacityPending` (Task 2), `BoxCapacity.resetText` (Task 1).

- [ ] **Step 1: Implement the row additions.** Collect the new flows beside the existing ones (`val capacities by vm.capacities.collectAsState()` etc. — match the file's existing collect idiom). In the connected-agent row, after the name/status lines:

```kotlin
// Trailing email on the name line:
//   Row { Text(name, ...); Spacer(Modifier.width(6.dp));
//         capacity?.accountEmail?.let { Text(it, style = caption, color = secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) } }
// Below the "Connected" caption, when agent.connected:
val capacity = capacities[agent.id]
if (capacity != null) {
    capacity.liveSessions?.let { live ->
        Text(
            if (live == 0) "No active sessions" else "$live active session" + if (live == 1) "" else "s",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    capacity.limitLines.forEach { line ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(line.label, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${line.percent}%", style = MaterialTheme.typography.bodySmall,
                 fontWeight = FontWeight.Medium, color = usagePercentColor(line.percent))
            BoxCapacity.resetText(line.resetsAt)?.let {
                Text("· $it", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }
} else if (agent.id in capacityPending) {
    Text("Checking…", style = MaterialTheme.typography.bodySmall,
         color = MaterialTheme.colorScheme.outline)
}
```

with a small file-local helper (or in `designsystem` if a sibling exists):

```kotlin
private fun usagePercentColor(percent: Int): Color = when {
    percent < 50 -> Color(0xFF3FB950)
    percent < 80 -> Color(0xFFF0883E)
    else -> Color(0xFFF85149)
}
```

Adjust exact composable structure to the file's real layout (read it first — the row shape may differ from this sketch; keep its click/enabled handling untouched).

- [ ] **Step 2: Build + full unit tests**

Run: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -8`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/chat/matron/android/features/chatlist/NewChatSheet.kt
git commit -m "feat(new-chat): capacity + account rows in the machine picker"
```
