# Box Chip Colours Implementation Plan (Android port)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each agent box's chip a deterministic colour derived from its display name, identical on every platform and launch. Ports matron-apple's plan of the same name (shipped as apple #136).

**Architecture:** All logic lives in `designsystem/BoxChip.kt`: a 32-bit FNV-1a hash of the name's UTF-8 bytes indexes a fixed 10-colour palette (`BoxChipColors`). Call sites (the chat-list row) are untouched.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (`./gradlew :app:testDebugUnitTest`).

## Global Constraints

- Hash MUST be FNV-1a 32-bit (offset `2166136261`, prime `16777619`, wrapping multiply) — never `hashCode` — and MUST pin the same fixture indices as the Swift suite, or the two apps colour the same box differently.
- Palette order is frozen: blue, green, orange, purple, teal, pink, indigo, brown, cyan, mint. Reordering re-shuffles every user's colours.
- Chip stays single-line truncating (fixed-row-height invariant) — do not touch layout, only fills.
- Apple's snapshot baselines port as logic tests (Android unit tests don't render composables).

---

### Task 1: Deterministic tint derivation — DONE

**Files:** `designsystem/BoxChip.kt` (`BoxChipColors.palette`,
`paletteIndex`, `tint`); `designsystem/BoxChipTest.kt`
(`paletteIndexIsPinned`, `paletteIndexIsDeterministicAndInRange`,
`fullPaletteFixturesPinEveryIndexInOrder` — fixtures identical to the Swift
suite: `"eric"` → 4, `"dan-mac"` → 4, `"build-7"` → 9, `""` → 1,
`"🦊 box"` → 1; dev-7/romeo/india/charlie/quebec/delta/lima/alpha/echo/
foxtrot → 0…9).

### Task 2: Coloured chip rendering — DONE

**Files:** `designsystem/BoxChip.kt` — background becomes
`tint.copy(alpha = 0.18f)` in the capsule, text becomes
`BoxChipColors.textTint(name, darkTheme)` (the tint pulled toward black in
light mode / white in dark for contrast, matching apple #136's `textTint`;
dark-mode detection reads the theme's surface luminance because MatronTheme
can force an appearance that disagrees with the OS). Test:
`tintResolvesThroughThePalette` (ports the colour-snapshot fixture
assumptions).
