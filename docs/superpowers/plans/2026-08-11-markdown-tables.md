# Markdown Tables Implementation Plan (Android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render GFM pipe tables as real tables in chat messages, with the flat-copy path degrading tables to readable pipe text. Port of apple #134 (there the change was Mac-framed, but the parsing is platform-neutral).

**Architecture:** Extend `MarkdownAttributed` (hand-rolled line scanner → `MarkdownBlock` list) with a `Table` block kind carrying structured header/rows/alignments (`MarkdownTable`, inline styling applied per cell); render it via a new `MarkdownTableBlock` composable dispatched from `MarkdownText`'s Column — the same seam `CodeBlock` uses. The `MarkdownDocument.annotated` flat string degrades a table to pipe text with a rebuilt delimiter row (the Mac's copy-time `MarkdownReconstruction` analog). Compose has no text-table primitive, so the grid is a custom `Layout` whose sizing rules are pure, unit-tested functions.

**Tech Stack:** Kotlin / Jetpack Compose (M3), JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-11-markdown-tables-design.md` — read it first.

## Global Constraints

- Scope is `matron-android` only; all code in `app/src/main/java/chat/matron/android/designsystem/`, tests in `app/src/test/java/chat/matron/android/designsystem/`.
- Parsing stays a pure, deterministic function of (source, colours) — the memoised `parse` cache is untouched.
- Never render composables in unit tests: render-side logic that needs testing (column width normalisation, alignment mapping) is extracted into pure functions.
- Ported Apple test cases carry `///` doc comments naming the Apple test they port.
- Build/test command: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` (two pre-existing `JournalAuthServiceTest` probe failures are known and ignored).
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Table parsing + block model

**Files:**
- Modify: `app/src/main/java/chat/matron/android/designsystem/MarkdownAttributed.kt`
- Test: `app/src/test/java/chat/matron/android/designsystem/MarkdownAttributedTest.kt`

**Interfaces:**
- Produces: `MarkdownBlockKind.Table`; `enum class MarkdownTableAlignment { Left, Center, Right }`; `data class MarkdownTable(header, rows, alignments)` with `columnCount`; `MarkdownBlock.table: MarkdownTable?`. Task 2 renders it; the block's `text` carries the pipe degradation.

- [x] **Step 1: Write the failing tests** — port the Apple parser cases (`test_tableCell_classifiedWithRowColumnHeader`, `test_table_cellsCarryTableBlocks` styling half, `test_inlineStylesInsideCells_keepAttributes`, `test_twoAdjacentTables_getSeparateTextTables`, `test_messageEndingInTable_keepsSingleTerminatorNewline` adapted to the flat model) plus the GFM edge cases: delimiter column-count mismatch stays paragraph; header+delimiter-only table has an empty body; short/long rows normalise to `columnCount`; `\|` stays inside a cell; outer pipes optional; tables interrupt paragraphs; a plain line without a blank after the table is swallowed as a row (GFM example 205); a pipe line without a delimiter row stays a paragraph; links inside cells keep URL annotations.
- [x] **Step 2: Run to verify failure** — table sources currently parse as paragraphs.
- [x] **Step 3: Implement** — `isTableStart` (pipe line + matching-count delimiter row), `parseDelimiterRow` (`:?-+:?` per cell → alignments; `:---` is still left), `splitTableRow` (optional outer pipes, `\|` unescape), the `buildBlocks` table branch (body rows until blank line / other block start; rows normalised to `columnCount`; header cells bold), and the paragraph gatherer's break on `isTableStart`.
- [x] **Step 4: Degradation** — `pipeText(table, base)` builds the Table block's `text`: rows as `| a | b |`, delimiter row from alignments (left → `---`, center → `:---:`, right → `---:`), cell spans preserved. Port the Apple reconstruction cases that apply to the flat model (`test_reconstruct_fullTable_roundTripsPipesAndAlignment`, `test_reconstruct_tableBetweenParagraphs_blankLineSeparated`); the partial-selection cases have no Android analog (no range-based reconstruction) and are dropped.
- [x] **Step 5: Run the target tests** — all pass, existing suite untouched.

---

### Task 2: Compose grid renderer

**Files:**
- Create: `app/src/main/java/chat/matron/android/designsystem/MarkdownTable.kt`
- Modify: `app/src/main/java/chat/matron/android/designsystem/MarkdownText.kt`
- Test: `app/src/test/java/chat/matron/android/designsystem/MarkdownTableLayoutTest.kt`

**Interfaces:**
- Consumes: `MarkdownTable` from Task 1.
- Produces: `@Composable MarkdownTableBlock(table, modifier, textStyle, onLinkClick)`; pure `markdownTableColumnWidths(cellWidths, columnCount)` and `markdownTableTextAlign(alignment)`.

- [x] **Step 1: Write the failing tests** for the pure helpers: per-column max of row-major widths; single column; dangling partial row still counts; zero/negative column count is empty; no cells yields zero widths; alignment mapping Left/Center/Right → Start/Center/End (port of the mapping asserted by Apple `test_table_columnAlignmentMapsToParagraphAlignment`).
- [x] **Step 2: Implement `MarkdownTableBlock`** — horizontal scroll wrapper (the `CodeBlock` pattern) around a custom `Layout`: intrinsic-width measure → `markdownTableColumnWidths` → row heights via `minIntrinsicHeight` at column width → fixed-size re-measure → grid placement. Chrome per spec: 0.5dp `outlineVariant` hairlines (interior end/bottom edges per cell + one outer stroked rect, no doubled seams), 8×4dp cell padding, header row bold (from the parser) over a 5% `onSurface` tint, per-column `TextAlign`, cells as `ClickableText` with the existing URL-annotation click policy.
- [x] **Step 3: Dispatch** — `MarkdownText` renders `Table` blocks through `MarkdownTableBlock` (before the default text branch, like `CodeBlock`).
- [x] **Step 4: Run the target tests.**

---

### Task 3: Full verification

- [x] Run `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` — unit suite green apart from the two known `JournalAuthServiceTest` probe failures; APK builds.
