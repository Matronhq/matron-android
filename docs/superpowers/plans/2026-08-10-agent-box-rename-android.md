# Agent Box Rename — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Repo:** `matron-android`. Spec: `docs/superpowers/specs/2026-08-10-agent-box-rename-design.md`.
Ports matron-apple's plan 3 of 3 (`2026-08-10-agent-box-rename-apple.md`,
shipped as apple #131); the journal and bridge plans are deployed, so the new
snapshot fields, `device_meta` frame and rename endpoint already exist. The
app code degrades safely against an older server (absent fields → no chips,
rename → 404).

**Goal:** Render which agent box owns each conversation as a live chip, and
let the user rename a box from Settings → Devices.

**Architecture:** The store learns two new things — `conversation.agent_device_id`
and an `agent` id→name table — from `GET /snapshot`, live `convo_meta` events,
and the `device_meta` frame. `JournalChatService` joins them when building
`ChatSummary`, applying the "only when the user has ≥2 boxes" gate in one
place so every view stays dumb. The chip renders in the chat-list rows and
the session-status sheet. Rename is a new `DevicesProviding` method with UI
on the Devices screen.

**Tech Stack:** Kotlin, Jetpack Compose (M3), Room, JUnit4 + Turbine +
Robolectric (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- The chip shows **only when the user has ≥2 agent boxes**. Single-box users
  see no chip anywhere. An unknown or null `agent_device_id` (e.g. a revoked
  box) shows no chip.
- Device-name cap on the client is **40 characters**, matching the server's
  rejection threshold; validation refuses before a round-trip.
- Additive Room migration only (v3) — existing rows survive with NULL, and
  the app ships no destructive-migration fallback.
- Absent wire fields must never clear a value the client already learned
  (the `parent_convo_id` discipline in `upsertSummary`).

---

### Task 1: Store learns the owning box — DONE

**Files:** `journal/db/Entities.kt`, `journal/db/MatronDatabase.kt`
(MIGRATION_2_3), `journal/JournalApi.kt` (`AgentDTO`,
`SnapshotResponse.agents`, `ConvoSummaryDTO.agentDeviceID`, snapshot
parsing), `journal/JournalStore.kt` (upsert + `convo_meta` apply,
`conversation(id)`). Tests: `JournalStoreTest.snapshotAndConvoMetaRecordTheOwningBox`,
`MatronDatabaseMigrationTest.migratesV2FileToV3AndAgentRosterWorks`,
`JournalApiTest.snapshotParsesAgentDeviceIDAndAgentsList`.

### Task 2: Agent roster mirror + live `device_meta` — DONE

**Files:** `journal/db/Daos.kt` (`AgentDao`), `journal/JournalStore.kt`
(`replaceAgents` — empty list is a no-op, `renameAgent` — upsert,
`agentNames`, `agentNamesFlow`), `journal/WireModels.kt`
(`ServerFrame.DeviceMeta`), `journal/JournalSyncEngine.kt` (frame switch +
`refreshSummaries()` + `coldStartIfNeeded()`). Tests:
`JournalStoreTest.agentRosterMirrorsSnapshotAndLiveRenames`,
`agentNamesFlowRefiresOnRename`, `WireModelsTest.decodesDeviceMetaRenameFrame`.

### Task 3: `ChatSummary.boxName` and the ≥2-boxes gate — DONE

**Files:** `chat/ChatSummary.kt`, `chat/JournalChatService.kt` — the
summaries stream `combine`s conversations with the roster (the Kotlin shape
of the Apple original's two-input doorbell), `summary(record, boxNames)` and
the pure `boxName(record, boxNames)` rule. Tests:
`JournalChatServiceTest.boxNameOnlyResolvesWhenTheUserHasTwoOrMoreBoxes`,
`renamingABoxRelabelsAnOpenChatList`.

### Task 4: The chip in the chat list — DONE

**Files:** `designsystem/BoxChip.kt` (new; single-line truncating capsule),
`features/chatlist/ChatListScreen.kt` (`ChatRow` title line). Test:
`BoxChipTest`.

### Task 5: Box name in the chat header — DONE

**Files:** `features/chat/SessionStatusSheet.kt` (leads the footer block;
gated on content alone so the name shows before the first status frame),
`features/chat/ChatScreen.kt` + `MainActivity.kt` (`ChatRoute`) thread
`boxName` from the list's `ChatSummary` — same source as the row chip, so
header and row can never disagree. (Android has no Mac-style toolbar
subtitle; the sheet is the chip's header surface.)

### Task 6: Rename a box from Settings → Devices — DONE

**Files:** `journal/JournalApi.kt` (`renameDevice`), `viewmodels/
DevicesViewModel.kt` (`DevicesProviding.renameDevice`, `rename`, `validate`,
`NAME_CAP`), `features/settings/DevicesScreen.kt` (per-row Rename button +
pre-filled dialog — the Compose analog of the iOS swipe action + alert).
Tests: `DevicesViewModelTest.rename_updatesTheRosterAndSurfacesFailures`,
`validateName_matchesTheServerRules`, `JournalApiTest.renameDevicePostsAndParses`.

---

## Manual verification (before opening the PR)

Against a journal with at least two agent boxes paired:

1. Chat list shows a chip on each conversation naming its box.
2. Settings → Devices → Rename one box; the chip updates live in the open
   list without a relaunch (that is the `device_meta` path).
3. Relaunch: the chip still shows the new name (that is the snapshot path).
4. With only one box paired, no chips appear anywhere.

## Self-review notes

- Spec §3.1 (data layer) → Tasks 1–3. §3.2 (chip UI, ≥2 gate) → Tasks 4–5.
  §3.3 (rename UI) → Task 6.
- The gate is implemented once, in `JournalChatService`, and consumed by the
  row and the sheet through the same `ChatSummary`.
- The Apple `SummaryInputs` doorbell (a lock-guarded latest-value pair plus a
  one-slot signal stream, needed because GRDB observations can't be combined)
  maps to a single `combine(...).conflate()` here — same semantics, no
  hand-rolled state.
