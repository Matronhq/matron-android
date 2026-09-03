# Box chip colours — design (Android port)

**Date:** 2026-08-12 (Android port 2026-08-16)
**Status:** Approved (Dan: "auto is fine"). Ports the matron-apple spec of
the same name (shipped as apple #136).

## Goal

Each machine (agent box) gets a distinct tag colour, so the box chip beside
a chat title reads at a glance — eric is always the same colour, everywhere:
iOS, Mac, and Android.

## Decision: automatic colour assignment

Dan chose automatic over user-picked. The colour is derived deterministically
from the box's display name — zero setup, no storage, no sync, and the same
name yields the same colour on every platform.

Renaming a box re-rolls its colour. That matches the mental model: the colour
tags the *name*, and renames are rare deliberate acts (Settings → rename,
shipped in #131 / the Android rename port).

## Colour derivation

- **Hash:** FNV-1a (32-bit) over the display name's UTF-8 bytes. Explicitly
  NOT Kotlin's `hashCode` (or Swift's seed-randomised `hashValue`) — the
  algorithm must be identical in both apps and stable across launches.
- **Palette:** a fixed array of 10 hues that read in both light and dark
  mode: blue, green, orange, purple, teal, pink, indigo, brown, cyan, mint.
  Android pins the iOS system-colour values (the repo's `MatronGreen`/
  `MatronOrange`/`MatronRed` precedent) so both apps paint the same hue.
- **Index:** `hash % palette.count`. Collisions between two box names are
  acceptable (10 hues, typical fleets < 15 boxes; colour is an aid, not an
  identifier — the name is still printed in the chip).

## Rendering

`BoxChip` keeps its shape, typography, truncation, and accessibility label.
Only the fill changes, GitHub-label style:

- background: `tint.copy(alpha = 0.18f)` (was onSurfaceVariant at 0.15)
- text: the tint pulled toward the label colour — `lerp(tint, black, 0.35)`
  in light mode, `lerp(tint, white, 0.3)` in dark — because the raw hues are
  accent colours tuned for white text ON them, and teal/cyan/mint captions on
  the pale fill land around 2:1 contrast. (The Apple app gates this mix on an
  OS floor; Compose's `lerp` has none, so every device gets the readable
  variant.)

## Placement

All logic lives in `designsystem/BoxChip.kt`:

- `BoxChipColors.tint(name)` / `textTint(name, darkTheme)` — public, so the
  planned follow-ups (sender avatars, session tags) and Settings can reuse
  them. Not adopted anywhere else in this pass.
- The FNV-1a hash as `BoxChipColors.paletteIndex(name)`, unit-testable.

The chat list already renders `BoxChip(boxName)` — it picks the colour up
for free, no call-site changes.

## Testing

- Unit test pinning fixture names → palette indices (`"eric"` → 4,
  `"dan-mac"` → 4, `"build-7"` → 9, `""` → 1, `"🦊 box"` → 1 — the SAME
  values the Swift suite pins), so the hash/palette can never silently
  change and re-shuffle everyone's colours or desync the platforms.
- Unit test: same name twice → same index (determinism); distinct fixtures →
  distinct indices; the ten full-palette fixture names cover indices 0…9 in
  order. (Apple's visual snapshot baselines port as these logic tests —
  Android unit tests don't render composables.)

## Out of scope (YAGNI)

- Manual colour override in Settings (revisit only if auto collisions annoy).
- Colouring the box name anywhere else (Devices list, New Chat chooser,
  session status sheet).
- Storing colour in the journal / syncing it.
