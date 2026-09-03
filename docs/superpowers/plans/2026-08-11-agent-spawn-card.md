# Agent-Spawn Consent Card Implementation Plan (matron-android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the journal's `agent_spawn` consent card, answer it via `POST /agent-spawn/answer`, and derive resolved state from the journaled `spawn_outcome` event — with an Open deep-link into the spawned room.

**Architecture:** Mirror the agent-chat card stack end to end (`AgentChatRequest` → `TimelineItem.Kind` → mapper dispatch → design-system composable → `ChatViewModel` state → `JournalApi` call), with ONE deliberate divergence: resolution is derived from `spawn_outcome` timeline events, NOT persisted to `KeyValueStore` — the journal event is the durable cross-device record (this is why the journal work happened first). Wire contract: matron-journal `docs/superpowers/specs/2026-08-11-spawn-outcome-events-design.md` + protocol.md "Agent-spawned sessions".

**Tech Stack:** Kotlin + Compose, plain-class VMs with injected scope, kotlinx.serialization JsonObject hand-parsing (`JournalJson.kt` helpers), JUnit4 + coroutines-test + MockWebServer (+ Robolectric only where Room/context is needed). Build: `./gradlew :app:assembleDebug :app:testDebugUnitTest` (JDK 21; `local.properties` points at ~/Android/Sdk).

## Global Constraints

- **The wire payloads** (matron-journal mints these; strings pre-sanitised server-side):
  - Card: `permission_request` event, payload `{kind:'agent_spawn', request_id, from_device_id, from_name, from_convo_id, from_convo_title, target_device_id, target_name, workdir, task, topic?}`. `topic`/`from_convo_title` may be empty strings → treat as null (agent-chat's `emptyToNull` rule).
  - Outcome: `spawn_outcome` event in the same conversation, payload `{request_id, outcome:'started'|'declined'|'expired'|'failed', room_id? (started), child_convo_id? (started), error_code? (failed)}`.
  - Answer: `POST /agent-spawn/answer` body EXACTLY `{request_id, decision:'approve'|'deny'}` → 200; 409 = resolved elsewhere/expired; 404 = gone; any `always_allow` key = 400 (never send one).
- Parse-or-fallback rule: a payload missing what an answer needs (string `request_id`, non-empty `task`) parses to null → the generic `AskUser` card, mirroring `AgentChatRequest.parse` (`events/AgentChatRequest.kt:96`).
- NO answered-state persistence for spawn cards (no `KeyValueStore` writes) — state precedence: derived outcome (from events) wins → transient in-flight → `Idle` if an answerer is wired, else `Expired`. 409 → transient resolved-`Expired` (copy: request no longer waiting) until the durable event arrives and takes over.
- Unknown `outcome` string → treat as a generic resolved state, never crash.
- Compose composables stay logic-free (they are untested); ALL new logic lives in testable non-Compose code (parse, mapper, VM, snippet).
- Full `:app:testDebugUnitTest` green + `:app:assembleDebug` builds; commit style `feat(spawn): …` with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` trailer.

---

### Task 1: Wire model + mapper + snippets (pure logic)

**Files:**
- Create: `app/src/main/java/chat/matron/android/events/AgentSpawnRequest.kt` (+ `AgentSpawnCardState` sealed class in the same file)
- Create: `app/src/main/java/chat/matron/android/events/SpawnOutcome.kt`
- Modify: `app/src/main/java/chat/matron/android/chat/TimelineItem.kt` (two new Kinds), `chat/JournalTimelineMapper.kt` (dispatch), `journal/JournalStore.kt` (snippet mapping ~:459), `journal/WireModels.kt` (only if event-type constants require it — check how `permission_request` reaches the mapper first; `spawn_outcome` is a NEW type and must flow through like any other event)
- Test: `app/src/test/java/chat/matron/android/events/AgentSpawnRequestTest.kt`, `events/SpawnOutcomeTest.kt`, extend `chat/JournalTimelineMapperTest.kt`, extend `journal/JournalStoreTest.kt`

**Interfaces (produces):**
- `AgentSpawnRequest.parse(payload: JsonObject): AgentSpawnRequest?` — gate `kind=="agent_spawn"` + string `request_id` + non-empty `task`; fields `requestId, fromDeviceId, fromName?, fromConvoId?, fromConvoTitle?, targetDeviceId, targetName?, workdir, task, topic?`; display helpers `headline` (topic ?: task first line), `requesterLabel`, `targetLabel` mirroring AgentChatRequest's.
- `AgentSpawnCardState`: `Idle | Sending | Resolved(outcome: SpawnOutcome) | Failed(message: String)` — `Resolved` covers started/declined/expired/failed AND the 409 case (`SpawnOutcome(outcome="expired")`-style synthetic).
- `SpawnOutcome.parse(payload: JsonObject): SpawnOutcome?` — `requestId, outcome, roomId?, childConvoId?, errorCode?`; `SpawnOutcome.displayLine`: `started → "🚀 Spawned session started"`, `declined → "🚫 Spawn declined"`, `expired → "⌛ Spawn request expired"`, `failed → "❌ Spawn failed" (+ " — <errorCode>" when present)`, unknown → `"Spawn request resolved"`.
- `TimelineItem.Kind.AgentSpawnRequestCard(eventID: String, request: AgentSpawnRequest)` and `Kind.SpawnOutcomeRow(eventID: String, outcome: SpawnOutcome)` — `eventID` = seq string, invariant as documented at `TimelineItem.kt:29-33`.
- Mapper: in the `PERMISSION_REQUEST` branch (`JournalTimelineMapper.kt:69-101`), try `AgentChatRequest.parse` first (unchanged), then `AgentSpawnRequest.parse`, else the existing generic fallback. New branch for event type `spawn_outcome` → `SpawnOutcomeRow` (parse-fail → existing unknown handling).
- Snippets (`JournalStore.kt` ~:459, mirroring the server): `permission_request` with `kind=="agent_spawn"` → `"🤝 Agent spawn request"`; `spawn_outcome` → `SpawnOutcome.displayLine`-equivalent strings (reuse the mapping, not a copy).

- [ ] **Step 1:** Failing tests: parse happy path from the exact minted payload JSON (copy field set from Global Constraints); reject missing request_id/task/wrong kind; empty topic/title → null; outcome parse per-outcome incl. unknown; mapper dispatches spawn card, keeps agent-chat priority, falls back generic for unanswerable; store snippet tests for ask + all outcomes (JournalStoreTest pattern).
- [ ] **Step 2:** Run: `./gradlew :app:testDebugUnitTest --tests '*AgentSpawn*' --tests '*SpawnOutcome*' --tests '*TimelineMapper*' --tests '*JournalStore*'` → fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command → pass; then full `:app:testDebugUnitTest`.
- [ ] **Step 5:** Commit `feat(spawn): parse agent_spawn cards and spawn_outcome events`.

### Task 2: ViewModel state + API call + Compose card

**Files:**
- Modify: `viewmodels/ChatViewModel.kt` (spawn state derivation + `answerAgentSpawn`), `journal/JournalApi.kt` (+DTO-free POST), `viewmodels/AgentChatViewModel.kt`-style interface slice (new `AgentSpawnAnswering` — put beside the API), `AppDependencies.kt` (service wiring mirroring `agentChatService()`)
- Create: `designsystem/AgentSpawnRequestCard.kt`
- Modify: `features/chat/TimelineItemView.kt` (dispatch both new Kinds; `SpawnOutcomeRow` renders its `displayLine` as a modest centered/status text like existing state rows), `features/chat/ChatScreen.kt` (thread callbacks, VM-scope launch comment rule at :422)
- Test: extend `viewmodels/ChatViewModelTest.kt`, `journal/JournalApiTest.kt` (MockWebServer)

**Interfaces:**
- Consumes Task 1's types.
- `JournalApi.answerAgentSpawn(requestId: String, decision: AgentSpawnDecision)` → Unit; errors via existing `JournalApiError` mapping (409→`Conflict`, 404→`NotFound`).
- `ChatViewModel`: `spawnOutcomes: Map<String, SpawnOutcome>` derived in `applySnapshot` from the mapped items (SpawnOutcomeRow kinds keyed by `outcome.requestId`); `agentSpawnState(eventID, request): AgentSpawnCardState` precedence per Global Constraints; `answerAgentSpawn(eventID, request, decision)` — guard re-answer/double-send, `Sending`, VM-scope launch (NOT row scope — copy the agent-chat comment rationale), `Conflict` → synthetic resolved-expired transient, `NotFound` → `Failed("That request is no longer on the server.")`, transport → `Failed(...)` answerable again, `CancellationException` → drop transient + rethrow.
- MockWebServer test asserts the POST body is EXACTLY `{"request_id":…,"decision":…}` (key set, no always_allow).
- Card composable mirrors `AgentChatRequestCard.kt` (Detail rows From/Target/Folder + task text block + Decline/OutlinedButton + Approve/Button + spinner; `Resolved` states render icon+copy; started additionally an "Open" TextButton → `onOpen(roomId)` closure; null state-resolver → read-only, no buttons).

- [ ] **Step 1:** Failing tests: derived-resolution precedence (outcome event in items resolves card, survives new VM for same room WITHOUT KeyValueStore — contrast test vs agent-chat's persistence); answer flow guards; Conflict→expired; NotFound copy; transport retryable; cancellation; API body-exact + status mapping.
- [ ] **Step 2:** Run targeted → fail. **Step 3:** Implement. **Step 4:** Targeted → pass; full suite + assembleDebug.
- [ ] **Step 5:** Commit `feat(spawn): agent-spawn consent card with journal-derived resolution`.

### Task 3: Open deep-link + polish

**Files:**
- Modify: `features/chat/ChatScreen.kt` / `MainActivity.kt` — Open on the card and outcome row navigates to `room_id`: call `deps.prepareConversation(session, roomId)` then `nav.navigate("chat/$roomId")`, the exact `NewChatViewModel`/`MainActivity.kt:373-378` precedent (placeholder row survives the journal race). Thread an `onOpenConversation: (String) -> Unit` from the nav host down to `TimelineItemView`.
- Test: whatever of the threading is testable without Compose (e.g. a VM-level or callback-level unit); do not add Compose UI tests.

- [ ] **Step 1:** Wire + targeted tests where logic exists. **Step 2:** Full `:app:testDebugUnitTest` + `:app:assembleDebug` green.
- [ ] **Step 3:** Commit `feat(spawn): open the spawned room from card and outcome row`.
