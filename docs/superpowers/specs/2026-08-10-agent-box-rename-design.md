# Agent Box Rename + Live Box Chip — Design (Android port)

**Date:** 2026-08-10 (Android port 2026-08-16)
**Status:** Approved by Dan (chip placement + B/C healing strategy confirmed in chat).
Ports the matron-apple spec of the same name; the journal (plan 1) and bridge
(plan 2) sides shipped with the Apple release — this document keeps their
sections as wire-contract context and adapts §3 to matron-android.

## Problem

Conversation titles used to bake in the bridge's `SERVER_LABEL`
(`label:xx Fix the thing`), derived from the hostname. Hostnames that don't
end in digits collapse to the same 4-char prefix — Zahra's `dev-y` and `dev-z`
boxes both showed as `DEV-`. Users could not rename the label at all: it is
not the journal device name (chosen at pairing, shown in Settings → Devices),
and no rename affordance existed for either.

## Decision (Dan: "b and c")

- **C:** Stop baking the label into title strings entirely. The box identity
  is data (`conversations.agent_device_id`, which the journal already records)
  and is rendered client-side as a live chip resolved from the device roster.
- **B:** Heal existing baked titles with a one-time journal migration
  (server-side; already shipped).
- Renaming = renaming the journal device, from Settings → Devices. One name,
  one place, applies everywhere (chips, agent-chat room titles, roster).

## 1. Journal (matron-journal — shipped, contract reference)

### 1.1 Rename endpoint

`POST /devices/:id/rename`, body `{ "name": "dev-y" }`.

- Client-gated (`who.kind === 'client'`) — an agent must not rename devices;
  403 `{ error: 'forbidden' }` otherwise.
- Validation: trim; reject empty; cap at 40 chars (reject, don't truncate).
  Duplicate names are allowed. Any device kind may be renamed.
- Not-owned and nonexistent devices are indistinguishable: both 404.
- Response: `{ ok: true, device: { device_id, name } }`.

### 1.2 Snapshot carries box identity

- `agent_device_id` in each conversation row (null when never recorded).
- A top-level `agents` array: `[{ device_id, name }]` for the user's
  `kind='agent'` devices, so clients resolve id → name from the snapshot alone.

### 1.3 Live events

- `convo_meta` payload carries `agent_device_id`, so a brand-new conversation
  gets its chip without a snapshot round-trip. Re-pointed freely: a session
  resumed on another box changes owner (unlike `parent_convo_id`).
- `device_meta` fan-out on rename: `{ kind: 'device_meta', device_id, name }`
  to all of the user's **client** connections. Transient — no seq, never
  replayed; a client that misses it learns the name from its next snapshot.

## 2. Bridge (matron-bridge — shipped, context)

Bridges no longer embed the label in titles (seed, first-user-message
fallback, Gemini title pass, resumed-session titles), and the journal healed
what was already stored.

## 3. Android app (matron-android)

Mirrors the Apple §3 architecture, translated to the Android stack (Room in
place of GRDB, Compose in place of SwiftUI):

### 3.1 Data layer

- `ConvoSummaryDTO` + `ConversationEntity` gain `agentDeviceID: Long?`
  (column `agent_device_id`); Room migration v3 adds the column.
- New Room table `agent` (`id INTEGER PRIMARY KEY`, `name TEXT NOT NULL`),
  replaced wholesale on each snapshot apply (`JournalStore.replaceAgents`)
  and patched by `device_meta` (`renameAgent`, update-only — the frame fans
  out for any device kind, so an upsert would let a renamed phone join the
  agent roster; unknown ids wait for the next snapshot. Divergence from
  matron-apple, which upserts).
- `convo_meta` handling stores `agent_device_id` when present; a snapshot row
  that omits it never clears a learned value (the `parent_convo_id`
  discipline), but a present value always wins.
- `ChatSummary` gains `boxName: String?`, resolved in `JournalChatService`
  by joining `agent_device_id` → the roster map. The summaries stream
  `combine`s the conversations flow with `agentNamesFlow()` — Room only
  re-fires a Flow for the tables its query reads, so a rename would otherwise
  never relabel an open list.

### 3.2 Chip UI

- Rendered **only when the user has ≥2 agent boxes** (roster count); the gate
  lives in `JournalChatService.boxName`, single-box users see no change.
  Unknown `agent_device_id` (revoked box): no chip. Null: no chip.
- Chat-list rows (`ChatListScreen.ChatRow`): `BoxChip` capsule beside the
  title line, single-line, truncating (rows keep a fixed height).
- Chat screen: the box name leads the `SessionStatusSheet` footer (Android has
  no toolbar subtitle; the sheet is the header's detail surface), shown even
  before the first status frame lands.

### 3.3 Rename UI

Settings → Devices: a Rename action on each device row opening a dialog
pre-filled with the current name. `DevicesProviding` gains
`renameDevice(id, name)`; `DevicesViewModel.rename` validates first
(`validate(name)`: non-empty after trimming, ≤ 40 chars — mirroring the
server so the field refuses before a 400) and re-fetches the roster on
success rather than patching, so a server-sanitised name is what the user
ends up seeing.

## 4. Out of scope

- Per-conversation label overrides.
- Stripping prefixes client-side (server data was healed instead).

## Testing

Room store tests for the owning-box column, roster mirror, and roster-flow
re-fire; wire test for `device_meta` decode; API tests for snapshot parsing
and the rename endpoint; chat-service tests for the ≥2-boxes gate and the
live relabel; view-model tests for rename validation and failure surfacing.
Run: `./gradlew :app:testDebugUnitTest`.
