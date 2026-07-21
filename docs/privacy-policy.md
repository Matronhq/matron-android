# Matron for Android — Privacy Policy

**Effective date:** <!-- TODO: e.g. 2026-07-21 -->
**Last updated:** <!-- TODO -->

> This is a starting template that reflects how the app actually handles data.
> Review it, fill in every `TODO`, host it at a public URL, and enter that URL in
> the Play Console. Consider legal review before publishing — this is not legal
> advice.

## Who we are

Matron for Android ("the app") is a chat client for talking to Claude Code
agents. The app is published by **<!-- TODO: your name or company -->**
("we", "us"). You can contact us at **<!-- TODO: support email -->**.

## The short version

The app is a **client** for a Matron server ("homeserver") that **you choose and
connect to**. Your messages, attachments, and voice notes are sent to that
server so agents can respond. We, the app publisher, do **not** operate a
central service that collects your content, and the app contains **no
third-party analytics or advertising SDKs**. The party responsible for storing
and processing your chat content is whoever operates the homeserver you sign in
to (which may be you).

## What data the app handles

- **Account / session credentials.** When you sign in, an access token (and
  optional refresh token) for your homeserver is stored **encrypted on your
  device** using Android's hardware-backed keystore (`EncryptedSharedPreferences`).
  It is not transmitted to us.
- **Messages and chat content.** Text you send is transmitted to the homeserver
  you connect to and mirrored to a local database on your device so the app can
  render offline.
- **Attachments (files and images).** Files you attach are transmitted to your
  homeserver. Downloaded attachments are cached temporarily on your device.
- **Voice notes (microphone).** If you record a voice note, the app captures
  audio to a temporary file and sends it to your homeserver as an audio
  attachment. Audio is only captured while you are actively recording a note; it
  is not recorded in the background and is not used for any other purpose.
- **Server address.** The homeserver URL you enter is stored on your device to
  keep you signed in.

## Permissions the app requests

- **Microphone (`RECORD_AUDIO`)** — only to record voice notes you explicitly
  send. See above.
- **Notifications (`POST_NOTIFICATIONS`)** — to alert you to new activity.
- **Internet / network state** — to connect to your homeserver.
- **Camera** — the app does **not** request the camera permission. QR sign-in
  uses the Google Play Services code scanner, whose capture UI runs outside the
  app; the app only receives the decoded link text.

## How data is shared

- With **your chosen homeserver**, to provide the service (this is the core
  function of the app).
- We do **not** sell your data and do **not** share it with advertisers.
- The app uses **Google Play Services** (code scanner) for QR sign-in; Google's
  handling of any data is governed by Google's privacy policy.

## Data retention and deletion

- Locally stored data (the session token, the mirrored message database, and
  cached attachments) is removed when you sign out or uninstall the app.
- Content held on your homeserver is retained and deleted according to that
  server operator's policies. If you operate the server, you control this.

## Children

The app is not directed to children under 13 (or the equivalent minimum age in
your jurisdiction).

## Changes to this policy

We may update this policy; material changes will be reflected by the "Last
updated" date above.

## Contact

**<!-- TODO: support email -->**
