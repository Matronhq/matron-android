# Timeline: render markdown tables properly

**Date:** 2026-08-11 (Android port of the matron-apple spec of the same name)
**Status:** Ported from apple #134 (approved there: Dan, 2026-08-11 — "sure")

## Problem

Pipe tables in chat messages render as a vertical spill of cell text. The
Android renderer parses message bodies through `MarkdownAttributed` — a
hand-rolled line scanner producing `MarkdownBlock`s that `MarkdownText`
renders in a Column — and that parser has no table case: table lines fall
through to the paragraph path, so each source line renders as one plain
paragraph line, pipes and all.

The Mac fixed this in apple #134 by teaching its converter a `tableCell`
block kind and rendering through TextKit's `NSTextTable`. The parsing rules
are platform-neutral (GFM pipe tables); this spec ports them to the Android
parser and gives Compose — which has no text-table primitive — its own grid
renderer.

## Approach

Extend the existing parser and block model rather than adopting a markdown
library: `MarkdownAttributed` stays the single source of parsing truth, and
tables become one more `MarkdownBlockKind` the `MarkdownText` Column
dispatches on — exactly how fenced code blocks already work (a structured
block rendered by a dedicated composable, `CodeBlock`).

Rejected alternatives (mirroring the Mac spec's reasoning): pulling in a
markdown library for tables alone (two parsers disagreeing about every other
construct); rendering tables as aligned monospaced text (still "not
properly").

## Scope

Android only, all in `app/src/main/java/chat/matron/android/designsystem/`:

- `MarkdownAttributed.kt` — table detection/parsing + the flat-copy
  degradation
- `MarkdownTable.kt` (new) — the Compose grid renderer + pure layout helpers
- `MarkdownText.kt` — one new dispatch branch

No journal, bridge, or timeline-view changes.

## Design

### Block model

`MarkdownBlockKind` gains `Table`. `MarkdownBlock` gains a structured
payload:

```kotlin
enum class MarkdownTableAlignment { Left, Center, Right }

data class MarkdownTable(
    val header: List<AnnotatedString>,
    val rows: List<List<AnnotatedString>>,
    val alignments: List<MarkdownTableAlignment>,
) { val columnCount: Int get() = header.size }
```

Cell strings are display-ready `AnnotatedString`s — inline styling (bold,
italic, code, links) is applied per cell through the existing `appendInline`,
and header cells carry bold (the Swift port's `isBold` for header cells).
Every body row is normalised to `columnCount` cells (GFM: extra cells
dropped, missing cells empty) so the renderer never bounds-checks.

### Parsing (GFM rules, as Apple's parser inherits from cmark-gfm)

A table starts at a line containing a pipe whose NEXT line is a delimiter
row (`:?-+:?` per cell) with exactly the header's cell count — a mismatch
means "not a table" and the lines stay paragraphs. Alignment: `:---:` →
center, `---:` → right, everything else (including explicit `:---`) → left.

- Outer pipes are optional (`A | B` works); `\|` escapes a literal pipe
  inside a cell.
- Tables interrupt paragraphs — no blank line required before the header.
- Body rows run until a blank line or the start of another block; a plain
  pipe-less line is swallowed as a one-cell row (GFM spec example 205).
- Header + delimiter alone is a valid, body-less table.
- Back-to-back tables parse as separate `Table` blocks (the Mac needed a
  cell-coordinate rule, `tableCellContinues`, to split them; the Android
  line scanner gets the split for free — each table starts at its own
  header+delimiter pair).

### Rendering (`MarkdownTableBlock`)

Compose has no text-table primitive, so the grid is a custom `Layout`:

- Cells are measured at max intrinsic width; each column takes its widest
  cell (`markdownTableColumnWidths`, a pure function — the analog of what
  the Mac gets free from `NSTextTable`'s automatic layout algorithm). Row
  heights are the tallest cell at its column's width; cells are then
  re-measured to fixed column × row rectangles so backgrounds and borders
  fill the full cell.
- Wide tables scroll horizontally, matching `CodeBlock`'s pattern (the Mac
  instead lets the table span the bubble at 100% width; horizontal scroll
  is the phone-appropriate equivalent).
- Chrome mirrors the Mac's: 0.5dp hairline dividers (`outlineVariant` — the
  M3 analog of `separatorColor`), compact cell padding, and a header row
  shaded with a 5% `onSurface` tint — an overlay, not a fixed colour, for
  the same reason the Mac rejected `controlBackgroundColor`: it must read
  on either theme's bubble. Interior edges are drawn per cell (end +
  bottom only) with one outer stroked rectangle, so shared hairlines never
  double up.
- Per-column alignment maps to `TextAlign` (`markdownTableTextAlign`, the
  `nsAlignment(_:)` analog). Cells render through `ClickableText` so links
  inside cells stay tappable under the existing URL-annotation policy.

### Flat-copy degradation (`MarkdownDocument.annotated`)

The Mac's `MarkdownReconstruction` rebuilds pipe tables at copy time from
per-run semantics. Android's flat path is simpler — `annotated` is built
once from block text — so a `Table` block's `text` IS its degradation: rows
re-joined as `| a | b |`, a delimiter row rebuilt from the carried
alignments, left staying plain `---` (markdown's default; only center/right
carry colons — same choice as the Mac's reconstruction). Cell spans ride
along; inline markers are not re-synthesised (the Android flat string is
display text with spans throughout, unlike the Mac's marker-reconstructing
copy path). Range-based partial-selection reconstruction does not exist on
Android and is out of scope.

### Sizing & caching

No changes to the parse cache — conversion stays a pure function of
(source, colours). Streaming messages re-parse per delta as today; a
half-streamed table renders as paragraphs until the header + delimiter
lines have arrived, then snaps into a table (same class of reflow as any
streaming markdown, and the same accepted behaviour as the Mac).

## Testing

- `MarkdownAttributedTest`: ported Apple cases (classification, header
  bold, inline styles in cells, adjacent tables, pipe-text degradation,
  table between paragraphs, no trailing newline) plus the GFM edge cases
  (column-count mismatch, body-less table, row normalisation, escaped
  pipes, optional outer pipes, paragraph interruption, example-205
  swallowing, pipe line without delimiter, link annotations in cells).
- `MarkdownTableLayoutTest`: the pure layout helpers (column widths,
  alignment mapping). Composables are never rendered in unit tests.
