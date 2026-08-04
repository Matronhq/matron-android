# Play Store listing — Matron

Submission pack for the Google Play production listing. Character limits are
Google's; counts below are approximate — adjust freely.

## App title (max 30 chars)

```
Matron — Chat for Claude Code
```
(29 chars)

"for Claude Code" is descriptive-compatibility naming; if Play's impersonation
check ever objects to the trademark in the title, fall back to plain `Matron`
and keep Claude Code in the descriptions only.

## Short description (max 80 chars)

```
Chat with your Claude Code agents from your phone — live replies, diffs & tools.
```
(79 chars)

## Full description (max 4000 chars)

```
Matron is a native Android client for talking to your Claude Code agents from your phone.

Sign in to your Matron server and watch your agents work in real time: streaming replies, tool-call and diff cards, live terminal output, and inline prompts you can answer on the go.

FEATURES
• Live streaming — replies and tool calls update as they happen
• Diff & tool cards — see file changes and command output inline
• Live terminal output — follow long-running commands as they run
• Sub-agents — parallel agent tasks appear as their own chats
• Voice notes — record and send audio to your agent
• QR sign-in — link a device by scanning, no password typing
• App lock — fingerprint, face, or device-credential lock with idle timeout
• Offline-ready — a local mirror renders instantly and resumes cleanly after any disconnect

BUILT TO RECONNECT
Matron keeps a local mirror of your conversation and resumes from exactly where it left off after a dropped connection, a backgrounded app, or a server restart — no wedged states and no forced restarts.

PRIVATE BY ARCHITECTURE
Your conversations go to a server you run — not to us. Matron has no analytics, no ads, and no tracking of any kind.

REQUIRES A MATRON SERVER
Matron is a client, not a standalone service. You need access to a matron-journal server to sign in. The server is open source and self-hostable.

Matron is open source, released under AGPL-3.0.
```

## Listing metadata

- **Category:** Productivity
- **Contact email:** same address as the privacy policy contact
- **Privacy policy URL:** https://matron.chat/privacy/
- **Graphics:** `play-icon-512.png` (512×512), `feature-graphic-1024x500.png`
  (1024×500), 7 phone screenshots in `screenshots/` (1080×1920, captured by
  `tools/screenshots.sh` against a seeded demo journal)

## App access (reviewer instructions)

The app requires a server, so declare "All or some functionality is
restricted" and provide the demo account:

- **Server URL:** `https://demo.matron.chat`
- **Username:** `demo`
- **Password:** *not committed to this repo — enter it in the Play Console
  App access form* (it lives with the Apple review credentials)

Suggested instructions text:

```
Matron is a client for a self-hosted agent server; the demo server above is
provided for review. On first launch enter the Server URL, username and
password exactly as given, then tap "Sign in". The account contains seeded
conversations demonstrating live agent chat, code-diff cards, tool output,
and sub-agent tasks. Ignore the "From another device" QR section — that is
an alternative sign-in path for users who already have a signed-in device.
```

Before every (re)submission, verify the demo server accepts the credentials:
`curl -X POST https://demo.matron.chat/login` with the JSON body — expect
HTTP 200. Note the journal locks a username out after repeated failed logins
(30s–1h): if a reviewer reports a login error, check for lockout before
assuming an app bug.

## Data safety form

Grounded in the manifest (INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS,
RECORD_AUDIO, VIBRATE — no camera, no location) and the code: no analytics,
no crash reporting, no ads SDKs, no Firebase (push is dormant), no
third-party data sharing. All user data goes only to the server the user
chooses at sign-in.

**Does your app collect or share any of the required user data types?** Yes
(messages etc. are transmitted off the device — to the user's own server).

| Data type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| Personal info → User IDs (username) | Yes | No | No | App functionality (authentication) |
| Messages → In-app messages | Yes | No | No | App functionality |
| Photos and videos → Photos | Yes | No | Yes | App functionality (attachments) |
| Audio → Voice or sound recordings | Yes | No | Yes | App functionality (voice notes) |
| Files and docs | Yes | No | Yes | App functionality (attachments) |

Everything else (location, contacts, financial, health, browsing, device
identifiers, etc.): **not collected**.

- **Is all user data encrypted in transit?** Yes (HTTPS/WSS).
- **Do you provide a way for users to request data deletion?** Yes — data is
  stored on the user's own server; deletion is performed there (and the local
  cache is removed by uninstalling). Reference the privacy policy's
  "Retention and deletion" section.
- **Account creation:** the app does not let users create accounts (accounts
  are provisioned by the server operator), so no account-deletion URL is
  required.
- **Independent security review:** No.
- **Data collected by the developer:** none — per the privacy policy, the
  developer operates no server that stores user data. The declarations above
  exist because Google's definition of "collected" is "transmitted off the
  device", and the app transmits to the *user's chosen* server.

## Content rating questionnaire (IARC)

- Category: **Utility, Productivity, Communication, or Other**
- Violence / sexuality / language / controlled substances / gambling: **No**
- Does the app allow users to interact or exchange content with other users?
  **No** — the chat is with the user's own AI agent, not with other people.
- Does the app share the user's location with other users? **No**
- Does the app allow users to make digital purchases? **No**
- Expected rating: Everyone / PEGI 3.

## Other console declarations

- **Ads:** No ads.
- **News app / COVID app / Government app / Financial features / Health:** No.
- **Target audience:** 18+ (developer tool; avoids all children-policy
  obligations). Not directed at children (matches privacy policy).
- **AI-generated content:** the app is a chat client to an AI coding agent
  the user operates on their own infrastructure. If the console's generative
  AI declaration applies, answer honestly that AI-generated content is
  displayed but generated by the user's own self-hosted service, which also
  controls it; there is no developer-hosted generation.
