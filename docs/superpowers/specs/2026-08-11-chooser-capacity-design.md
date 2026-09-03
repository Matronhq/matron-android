# New Chat chooser: per-box activity, usage limits, and account (Android port)

**Date:** 2026-08-11
**Status:** Approved

Port of matron-apple `docs/superpowers/specs/2026-08-11-chooser-capacity-design.md`
— read that spec for the full rationale, wire shapes, and UI rules. This doc
records only the Android mapping:

- `NewChatViewModel.kt` gains the same fan-out: after the roster loads, fire
  `recent_folders` at every connected agent in parallel; fill a
  `Map<Long, BoxCapacity>` as replies land; per-box failure degrades that
  row silently; replies also warm the folder cache used by the folder step.
- `BoxCapacity` / `LimitLine` data classes (account is a plain `String?` email) parsed defensively
  from the reply JSON (each block optional, malformed → dropped, folders
  parse never fails because of a capacity block).
- `NewChatSheet.kt` row layout mirrors the Apple rows: name + trailing
  account email (secondary), "N active sessions" line, then **all** limit
  lines in bridge order with percent tinted green < 50 / orange < 80 /
  red ≥ 80 and a compact reset time (time-only if today, else short date).
- Offline rows unchanged; rows stay pickable while capacity is loading.
- Tests mirror the Apple list: fan-out behaviour in the VM test and parsing
  edge cases. (No Compose UI test — the repo has no Compose test
  infrastructure; a capacity-less box renders today's row because every
  capacity element is conditional on parsed data.)
