# Conversation Summaries TOC — design (Android port)

**Date:** 2026-08-09 (Android port 2026-08-16)
**Status:** Approved

Port of matron-apple `docs/superpowers/specs/2026-08-09-summaries-toc-design.md`
(apple PRs #124, #126, #128) — read that spec for the full rationale, the
bridge/journal sides, and the wire shape. Every summary pass the bridge runs
becomes a persistent, anchored TOC entry riding the journal event stream as a
first-class `summary` kind; the event's own `seq` is the transcript anchor.
This doc records only the Android mapping:

- `JournalEventType` gains `SUMMARY`, deliberately NOT in `MESSAGE_TYPES` —
  no snippet, no unread bump, no `last_activity_ts` movement (the journal
  server applies the same exclusion).
- Storage mirrors the Apple GRDB v4 migration: Room v3 adds `summary_entry`
  (`convo_id` + `seq` composite PK, `toc`, `detail`, `created_at` epoch-ms),
  populated inside the same transaction as the event insert on all three
  ingest paths (live `applyJournal`, catch-up `applyJournalBatch`, pagination
  `insertHistory`). A payload without a usable non-empty `toc` publishes no
  row (the Apple `SummaryEntryRecord(event:)` failable-init contract).
  `wipe()` clears the table; `MatronDatabase.MIGRATION_2_3` follows the
  `MatronDatabaseMigrationTest` precedent.
- `JournalTimelineMapper` excludes `summary` from the transcript (it would
  otherwise render as `[unsupported event: summary]`).
- `TimelineService.summaryEntriesStream(): Flow<List<ConversationSummaryEntry>>`
  (default empty for fakes/non-journal backends);
  `JournalTimelineService` maps the store's Room flow, newest first.
- `ChatViewModel` gains `summaryEntries` (StateFlow) and the jump-to-point
  machinery ported from Apple's `focus(seq:)`: paginate backward until the
  nearest message with `seq <= entry.seq` is loaded (single-flight — a second
  call supersedes the first), fall back to the oldest loaded row, widen the
  render window, then publish `pendingFocusID` for the view to consume.
- UI: `SummariesSheet.kt`, a ModalBottomSheet from the tappable ChatScreen
  title (the iOS principal-title-button analog; `SessionStatusSheet`
  presentation precedent). Rows: `toc` line, chevron-expand `detail`, date
  caption, newest first; empty state "No summaries yet — they appear as the
  conversation grows." The #126 full-contrast expanded detail and the #128
  real chevron tap target are baked in from the start (`IconButton`'s 48dp
  minimum touch target is the Compose-native tap-shape inflation).
  `TimelineList` consumes `pendingFocusID` with `LazyListState.scrollToItem`
  (+ a 200ms re-assert guarded against superseded jumps, mirroring iOS).
- Tests mirror the Apple list: store ingest/wipe/no-snippet rows, the v2→v3
  migration, mapper exclusion, service stream mapping, VM stream + focus
  (including both single-flight supersede pins), and the sheet's decisions
  as pure functions (this repo renders no composables in unit tests).
