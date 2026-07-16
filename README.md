# Matron for Android

Matron is a chat system for talking to [Claude Code](https://claude.com/claude-code) agents from your phone, desktop, or browser. This repo is the **native Android client** — Kotlin + Jetpack Compose, a port of [matron-apple](https://github.com/Matronhq/matron-apple). Sign in and watch an agent's conversations arrive and update live: streaming replies, tool-call and diff cards, live terminal output, inline answer prompts. Speaks **matron-journal**, a purpose-built server protocol.

## Part of the Matron ecosystem

| Project | Description |
| --- | --- |
| **matron-android** | Android client (this repo) |
| [matron-apple](https://github.com/Matronhq/matron-apple) | iOS + macOS client |
| [matron-journal](https://github.com/Matronhq/matron-journal) | Sync server |
| [matron-desktop](https://github.com/Matronhq/matron-desktop) | Desktop client |
| [matron-web](https://github.com/Matronhq/matron-web) | Web client |

## Architecture & reliability model

The app keeps a local Room (SQLite) mirror of the journal and renders entirely from it; a single sync engine applies server frames to the mirror behind an integer cursor that only advances after a committed write. Every failure mode — dropped socket, backgrounded app, server restart — converges the same way: reconnect and resume from the stored cursor, with no wedge states and no required app restart.

## Requirements

- JDK 17
- Android SDK 35
- Android 8.0+ (API 26) device or emulator
- A matron-journal server (see the [matron-apple README](https://github.com/Matronhq/matron-apple#local-dev-server) for local-server setup)

## Building

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## License

AGPL-3.0 with commercial licensing available by arrangement. See `LICENSE` and `NOTICE`.
