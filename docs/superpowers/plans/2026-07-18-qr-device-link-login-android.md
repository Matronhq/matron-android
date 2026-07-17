# QR Device-Link Login — Android Implementation Plan (matron-android)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Both roles of QR device-link login on Android: Settings → "Link a Device" shows a QR (ZXing-rendered), and the sign-in screen scans one (ML Kit Play-services code scanner — no CAMERA permission) or accepts a typed link code, landing in the normal signed-in flow.

**Architecture:** Mirror of the Apple structure, following the existing `PairingViewModel`/`DevicesProviding` patterns: six new `JournalApi` methods over the server's `/link/*` endpoints (matron-journal `docs/protocol.md`, "Device link (QR sign-in)"), a `LinkURI` parser, two scope-injected view models (`DeviceLinkViewModel` show side, `LinkSignInViewModel` claimant side) unit-tested against fakes, thin Compose screens. Spec: matron-journal `docs/superpowers/specs/2026-07-18-qr-device-link-login-design.md` (§2 QR payload, §4 Android, §5 Errors, §7 Testing).

**Tech Stack:** Kotlin + Compose. New deps: `com.google.zxing:core` 3.5.3 (QR *generation* only — pure Java, no scanning machinery) and `com.google.android.gms:play-services-code-scanner` 16.1.0 (Google-provided capture UI, **no CAMERA permission, no manifest change**).

## Global Constraints

Copied from the spec — every task's requirements implicitly include these:

- QR payload: `matron://link?v=1&server=<URL-encoded base server URL>&code=XXXX-XXXX`. `v` ≠ 1 → "This QR code needs a newer version of Matron." Non-`matron://link` payload → "Not a Matron sign-in code."
- Wire fields: start → `{link_code, expires_in}`; claim `{link_code, device_name}` → `{status:'claimed', claim_token, expires_in}`; poll `{claim_token}` → `{status:'pending'}` | `{status:'approved', token, device_id, user_id, username}` | `{status:'denied'}`; status → `{status:'waiting', expires_in}` | `{status:'claimed', device_name, requester_ip, expires_in}`; approve/deny `{link_code}`. `claim` and `poll` are unauthenticated; the other four need the Bearer.
- The claimant builds the same `UserSession` shape password login builds: `userID` = returned `username`, `deviceID = device_id.toString()`, `homeserverURL` = the scanned/typed server URL (normalized string), `accessToken` = token. Persist via `auth.persist(session)`; sign-in completes through the existing `onSignedIn` callback (MainActivity's `awaitPendingTeardown()` + `wipeLocalDataForFreshLogin()` path applies unchanged).
- `device_name` sent on claim is `"Matron Android"` — the same string password login sends.
- Both pollers run every **2 s**, back off to **5 s** on transport errors, and keep trying until their screen closes. Show-side status `404` silently regenerates. Approve success is terminal for the show side.
- Old server (`404` on `/link/start`) → "Server doesn't support device linking yet." Play services unavailable → "Scanner unavailable — use a link code instead."
- Error copy (spec §5, verbatim, matching the Apple plan): denied → "Sign-in was denied on the other device." · expired poll → "Sign-in expired. Scan again." · claim 409 → "This code was already used. Generate a new one on your signed-in device." · approve-after-expiry show side → "Code expired — showing a fresh one".
- Codes reuse `PairingCode` (`journal/PairingCode.kt`) normalize/display/isPlausible; manual input auto-formats like `PairingViewModel.codeInput`.
- **No manifest changes** — the Play-services code scanner needs no permission entry.
- Match existing style: scope-injected VMs with `MutableStateFlow`, `runBlocking` + child-scope tests with the `waitUntil` helper (`viewmodels/TestSupport.kt`), `JournalApiTest`'s MockWebServer harness, KDoc `///` comments.
- Build/test commands need `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`. Focused tests: `./gradlew testDebugUnitTest --tests "chat.matron.android.<Class>"`. Full: `./gradlew testDebugUnitTest`. Build: `./gradlew assembleDebug`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## File Structure

- `app/src/main/java/chat/matron/android/journal/JournalApi.kt` (modify) — link DTOs + six methods.
- `app/src/main/java/chat/matron/android/journal/LinkURI.kt` (create) — QR URI format/parse (pure JVM; okhttp `HttpUrl` for server validation).
- `app/src/main/java/chat/matron/android/designsystem/QRCode.kt` (create) — ZXing `QRCodeWriter` → `Bitmap`.
- `app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt` (create) — show-side state machine, `DeviceLinking` interface + `JournalDeviceLinkService` adapter.
- `app/src/main/java/chat/matron/android/viewmodels/LinkSignInViewModel.kt` (create) — claimant state machine, `LinkClaiming` interface + `JournalLinkClaimService` adapter.
- `app/src/main/java/chat/matron/android/features/settings/DeviceLinkScreen.kt` (create) + `DeviceSettingsScreen.kt` (modify) + `MainActivity.kt` (modify) + `AppDependencies.kt` (modify) — show side.
- `app/src/main/java/chat/matron/android/features/onboarding/SignInScreen.kt` (modify) + `MainActivity.kt` (modify) — scan/manual side.
- `gradle/libs.versions.toml` + `app/build.gradle.kts` (modify) — the two new dependencies.
- Tests: `journal/JournalApiTest.kt` (modify), `journal/LinkURITest.kt` (create), `designsystem/QRCodeTest.kt` (create, Robolectric), `viewmodels/DeviceLinkViewModelTest.kt` (create), `viewmodels/LinkSignInViewModelTest.kt` (create).

---

### Task 1: JournalApi link methods + DTOs

**Files:**
- Modify: `app/src/main/java/chat/matron/android/journal/JournalApi.kt` (DTOs after `PairPreview` ~line 67; methods after `pairApprove` ~line 230; `Conflict` doc comment line 78)
- Test: `app/src/test/java/chat/matron/android/journal/JournalApiTest.kt` (append)

**Interfaces:**
- Consumes: existing `request(...)` internals, `JournalApiError` mapping (409→`Conflict`, 404→`NotFound` already handled), JSON helpers (`stringOrNull`, `longOrNull`, `intOrNull`).
- Produces (Tasks 3-4 rely on these exact signatures):
  - `data class LinkStart(val code: String, val expiresIn: Int)`
  - `sealed interface LinkStatus { data class Waiting(val expiresIn: Int); data class Claimed(val deviceName: String, val requesterIP: String, val expiresIn: Int) }`
  - `data class LinkClaim(val claimToken: String, val expiresIn: Int)`
  - `data class LinkApproval(val token: String, val deviceID: Long, val userID: Long, val username: String)`
  - `sealed interface LinkPollResult { data object Pending; data object Denied; data class Approved(val approval: LinkApproval) }`
  - `suspend fun linkStart(): LinkStart` · `linkStatus(): LinkStatus` · `linkApprove(code: String)` · `linkDeny(code: String)` · `linkClaim(code: String, deviceName: String): LinkClaim` · `linkPoll(claimToken: String): LinkPollResult`

- [ ] **Step 1: Write the failing tests**

Append to `JournalApiTest.kt` (uses the file's existing `api()` / `json()` helpers):

```kotlin
    @Test
    fun linkStartParsesResponseAndSendsBearer() = runBlocking {
        server.enqueue(json(200, """{"link_code":"KTNM-3VQ8","expires_in":120}"""))
        val started = api(token = "tok").linkStart()
        assertEquals(LinkStart("KTNM-3VQ8", 120), started)
        val request = server.takeRequest()
        assertEquals("/link/start", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun linkStatusWaitingAndClaimed() = runBlocking {
        server.enqueue(json(200, """{"status":"waiting","expires_in":90}"""))
        assertEquals(LinkStatus.Waiting(90), api(token = "tok").linkStatus())
        server.enqueue(json(200,
            """{"status":"claimed","device_name":"Pixel 9","requester_ip":"198.51.100.7","expires_in":55}"""))
        assertEquals(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 55), api(token = "tok").linkStatus())
    }

    @Test
    fun linkApproveAndDenySendCode() = runBlocking {
        server.enqueue(json(200, """{"status":"approved"}"""))
        api(token = "tok").linkApprove("KTNM-3VQ8")
        assertTrue(server.takeRequest().body.readUtf8().contains(""""link_code":"KTNM-3VQ8""""))
        server.enqueue(json(200, """{"status":"denied"}"""))
        api(token = "tok").linkDeny("KTNM-3VQ8")
        assertTrue(server.takeRequest().body.readUtf8().contains(""""link_code":"KTNM-3VQ8""""))
    }

    @Test
    fun linkClaimSendsBodyUnauthenticatedAndParses() = runBlocking {
        server.enqueue(json(200, """{"status":"claimed","claim_token":"aa11","expires_in":60}"""))
        // token set but must NOT be sent: claim is the unauthenticated side
        val claim = api(token = "tok").linkClaim("KTNM-3VQ8", "Matron Android")
        assertEquals(LinkClaim("aa11", 60), claim)
        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""link_code":"KTNM-3VQ8""""))
        assertTrue(body.contains(""""device_name":"Matron Android""""))
    }

    @Test
    fun linkPollPendingDeniedApproved() = runBlocking {
        server.enqueue(json(200, """{"status":"pending"}"""))
        assertEquals(LinkPollResult.Pending, api().linkPoll("aa11"))
        server.enqueue(json(200, """{"status":"denied"}"""))
        assertEquals(LinkPollResult.Denied, api().linkPoll("aa11"))
        server.enqueue(json(200,
            """{"status":"approved","token":"bb22","device_id":42,"user_id":7,"username":"dan"}"""))
        assertEquals(LinkPollResult.Approved(LinkApproval("bb22", 42, 7, "dan")), api().linkPoll("aa11"))
    }

    @Test
    fun linkPollApprovedWithoutUsernameIsMalformed() = runBlocking {
        // username is load-bearing (it becomes UserSession.userID) — a server
        // that omits it must fail loudly, not sign in with a garbage identity.
        server.enqueue(json(200, """{"status":"approved","token":"bb22","device_id":42,"user_id":7}"""))
        try {
            api().linkPoll("aa11"); fail("expected throw")
        } catch (e: JournalApiError.Transport) { /* expected */ }
        Unit
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew testDebugUnitTest --tests "chat.matron.android.journal.JournalApiTest"`
Expected: COMPILE FAILURE — `LinkStart` etc. unresolved. (A compile failure in the test source set is this step's "red".)

- [ ] **Step 3: Implement the DTOs and methods**

In `JournalApi.kt`, after `PairPreview` (line 67), add:

```kotlin
/// `POST /link/start` — a fresh device-link session for QR sign-in. `code`
/// is the display form (`XXXX-XXXX`), rendered under the QR and embedded in
/// the payload verbatim.
data class LinkStart(val code: String, val expiresIn: Int)

/// `POST /link/status` — what the show side's poll sees. `Claimed` carries
/// the claimant-supplied name and the IP the server saw — both go on screen
/// before the user may approve (anti-phish, like [PairPreview]).
sealed interface LinkStatus {
    data class Waiting(val expiresIn: Int) : LinkStatus
    data class Claimed(val deviceName: String, val requesterIP: String, val expiresIn: Int) : LinkStatus
}

/// `POST /link/claim` — the claimant's secret poll credential.
data class LinkClaim(val claimToken: String, val expiresIn: Int)

/// The identity minted at the approved `link/poll`. `username` exists because
/// the app stores the typed username as `UserSession.userID` and a link
/// claimant never types one.
data class LinkApproval(val token: String, val deviceID: Long, val userID: Long, val username: String)

/// `POST /link/poll` — pending until the starter acts; `Denied` and
/// `Approved` each arrive at most once (the server deletes the session).
sealed interface LinkPollResult {
    data object Pending : LinkPollResult
    data object Denied : LinkPollResult
    data class Approved(val approval: LinkApproval) : LinkPollResult
}
```

Update the `Conflict` doc comment (line 78) to:

```kotlin
    /// 409 — exactly-once semantics: `pair/approve` (already approved),
    /// `link/claim` (code already claimed), `link/approve` (nothing to
    /// approve yet, or already resolved).
```

After `pairApprove` (~line 230), add:

```kotlin
    // MARK: Device link (QR sign-in)

    /// Starts (or replaces) this device's link session. `NotFound` means the
    /// server predates /link/* — callers surface "doesn't support device
    /// linking yet".
    suspend fun linkStart(): LinkStart {
        val obj = request(path = "/link/start", method = "POST", jsonBody = buildJsonObject { })
        val code = obj.stringOrNull("link_code")
        val expiresIn = obj.intOrNull("expires_in")
        if (code == null || expiresIn == null) throw JournalApiError.Transport("malformed link start response")
        return LinkStart(code, expiresIn)
    }

    /// This device's active session state. `NotFound` = no active session
    /// (expired or resolved) — the show side regenerates on it.
    suspend fun linkStatus(): LinkStatus {
        val obj = request(path = "/link/status", method = "POST", jsonBody = buildJsonObject { })
        val expiresIn = obj.intOrNull("expires_in") ?: 0
        return when (obj.stringOrNull("status")) {
            "waiting" -> LinkStatus.Waiting(expiresIn)
            "claimed" -> {
                val name = obj.stringOrNull("device_name")
                val ip = obj.stringOrNull("requester_ip")
                if (name == null || ip == null) throw JournalApiError.Transport("malformed link status response")
                LinkStatus.Claimed(name, ip, expiresIn)
            }
            else -> throw JournalApiError.Transport("malformed link status response")
        }
    }

    /// Approves this device's claimed session. `Conflict` = nothing claimed
    /// yet or already resolved; `NotFound` = expired/gone.
    suspend fun linkApprove(code: String) {
        request(path = "/link/approve", method = "POST",
            jsonBody = buildJsonObject { put("link_code", code) })
    }

    suspend fun linkDeny(code: String) {
        request(path = "/link/deny", method = "POST",
            jsonBody = buildJsonObject { put("link_code", code) })
    }

    /// Claimant side: claims a scanned/typed code. Unauthenticated — this API
    /// instance points at the *target* server and has no token yet.
    /// `Conflict` = code already used; `NotFound` = unknown/expired.
    suspend fun linkClaim(code: String, deviceName: String): LinkClaim {
        val obj = request(path = "/link/claim", method = "POST", authenticated = false,
            jsonBody = buildJsonObject {
                put("link_code", code)
                put("device_name", deviceName)
            })
        val token = obj.stringOrNull("claim_token")
        val expiresIn = obj.intOrNull("expires_in")
        if (token == null || expiresIn == null) throw JournalApiError.Transport("malformed link claim response")
        return LinkClaim(token, expiresIn)
    }

    /// Claimant poll loop body. `NotFound` after a successful claim means the
    /// session expired (or was replaced) — surface "Sign-in expired".
    suspend fun linkPoll(claimToken: String): LinkPollResult {
        val obj = request(path = "/link/poll", method = "POST", authenticated = false,
            jsonBody = buildJsonObject { put("claim_token", claimToken) })
        return when (obj.stringOrNull("status")) {
            "pending" -> LinkPollResult.Pending
            "denied" -> LinkPollResult.Denied
            "approved" -> {
                val token = obj.stringOrNull("token")
                val deviceID = obj.longOrNull("device_id")
                val userID = obj.longOrNull("user_id")
                val username = obj.stringOrNull("username")
                if (token == null || deviceID == null || userID == null || username == null) {
                    throw JournalApiError.Transport("malformed link poll response")
                }
                LinkPollResult.Approved(LinkApproval(token, deviceID, userID, username))
            }
            else -> throw JournalApiError.Transport("malformed link poll response")
        }
    }
```

(Check `request(...)`'s actual parameter name for the auth flag — `login()` at line 127 calls it `authenticated = false`; use the same.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.journal.JournalApiTest"`
Expected: PASS, all (including 6 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/journal/JournalApi.kt app/src/test/java/chat/matron/android/journal/JournalApiTest.kt
git commit -m "Add /link/* device-link methods to JournalApi

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: LinkURI parser + QR bitmap renderer

**Files:**
- Create: `app/src/main/java/chat/matron/android/journal/LinkURI.kt`
- Create: `app/src/main/java/chat/matron/android/designsystem/QRCode.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (zxing dep)
- Test: `app/src/test/java/chat/matron/android/journal/LinkURITest.kt`, `app/src/test/java/chat/matron/android/designsystem/QRCodeTest.kt`

**Interfaces:**
- Consumes: `PairingCode` (`journal/PairingCode.kt`); okhttp `HttpUrl` (server validation); `com.google.zxing.qrcode.QRCodeWriter` (new dep).
- Produces:
  - `LinkURI.format(serverURL: String, code: String): String`
  - `LinkURI.parse(raw: String): LinkURI.Parsed` (`data class Parsed(val serverURL: String, val code: String)`, code in display form) throwing `LinkURI.ParseError` (`NotALink` | `UnsupportedVersion` | `Malformed` — a sealed class of exceptions)
  - `QRCode.bitmap(content: String, sizePx: Int = 512): Bitmap`

- [ ] **Step 1: Add the ZXing dependency**

`gradle/libs.versions.toml` — under `[versions]`:

```toml
zxing = "3.5.3"
```

under `[libraries]`:

```toml
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
```

`app/build.gradle.kts` — in `dependencies {}` beside the other `implementation` lines:

```kotlin
    // QR *generation* only (Settings → Link a Device). Pure Java; scanning
    // uses the Play-services code scanner instead (no camera permission).
    implementation(libs.zxing.core)
```

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/java/chat/matron/android/journal/LinkURITest.kt`:

```kotlin
package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkURITest {
    @Test
    fun roundTrip() {
        val uri = LinkURI.format("https://chat.example.com", "KTNM-3VQ8")
        assertTrue(uri.startsWith("matron://link?"))
        val parsed = LinkURI.parse(uri)
        assertEquals("https://chat.example.com", parsed.serverURL)
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun roundTrip_serverWithPathPrefixAndPort() {
        // The server URL is embedded exactly as the session stores it —
        // subpath-hosted and non-443 servers must survive the round trip.
        val parsed = LinkURI.parse(LinkURI.format("http://127.0.0.1:9810/journal", "KTNM-3VQ8"))
        assertEquals("http://127.0.0.1:9810/journal", parsed.serverURL)
    }

    @Test
    fun parse_normalizesSloppyCode() {
        val parsed = LinkURI.parse("matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=ktnm3vq8")
        assertEquals("KTNM-3VQ8", parsed.code)
    }

    @Test
    fun parse_wrongSchemeOrHost_isNotALink() {
        for (raw in listOf("https://chat.example.com", "matron://pair?v=1", "otp://x", "not a uri at all")) {
            try {
                LinkURI.parse(raw); fail("expected NotALink for $raw")
            } catch (e: LinkURI.ParseError.NotALink) { /* expected */ }
        }
    }

    @Test
    fun parse_otherVersion_isUnsupported() {
        try {
            LinkURI.parse("matron://link?v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            fail("expected UnsupportedVersion")
        } catch (e: LinkURI.ParseError.UnsupportedVersion) { /* expected */ }
    }

    @Test
    fun parse_missingOrBadParts_isMalformed() {
        for (raw in listOf(
            "matron://link?server=https%3A%2F%2Fx.example&code=KTNM-3VQ8",        // no v
            "matron://link?v=1&code=KTNM-3VQ8",                                    // no server
            "matron://link?v=1&server=ftp%3A%2F%2Fx.example&code=KTNM-3VQ8",       // non-http(s) server
            "matron://link?v=1&server=https%3A%2F%2Fx.example",                    // no code
            "matron://link?v=1&server=https%3A%2F%2Fx.example&code=KTN",           // short code
        )) {
            try {
                LinkURI.parse(raw); fail("expected Malformed for $raw")
            } catch (e: LinkURI.ParseError.Malformed) { /* expected */ }
        }
    }
}
```

Create `app/src/test/java/chat/matron/android/designsystem/QRCodeTest.kt` (Robolectric, like `DiffCardAccessibilityTest`):

```kotlin
package chat.matron.android.designsystem

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QRCodeTest {
    @Test
    fun bitmap_isSquareAtRequestedSize() {
        val bitmap = QRCode.bitmap("matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8", sizePx = 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.journal.LinkURITest" --tests "chat.matron.android.designsystem.QRCodeTest"`
Expected: COMPILE FAILURE — `LinkURI` / `QRCode` unresolved.

- [ ] **Step 4: Implement `LinkURI.kt`**

```kotlin
package chat.matron.android.journal

import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// The QR sign-in payload — the single place the format is known:
/// `matron://link?v=1&server=<URL-encoded base server URL>&code=XXXX-XXXX`.
/// Apple carries an equivalent parser; the server never sees the URI.
///
/// Parsed by hand (scheme/host prefix + query split) rather than
/// `android.net.Uri` so plain JVM unit tests cover it without Robolectric.
object LinkURI {
    sealed class ParseError : Exception() {
        /// Not ours at all — scanner shows "Not a Matron sign-in code."
        class NotALink : ParseError()
        /// Ours, but a future version — scanner shows "update the app".
        class UnsupportedVersion : ParseError()
        /// Ours and v=1, but the parts don't parse.
        class Malformed : ParseError()
    }

    data class Parsed(val serverURL: String, val code: String)

    private const val PREFIX = "matron://link?"

    fun format(serverURL: String, code: String): String {
        val server = URLEncoder.encode(serverURL, "UTF-8")
        val encodedCode = URLEncoder.encode(code, "UTF-8")
        return "${PREFIX}v=1&server=$server&code=$encodedCode"
    }

    fun parse(raw: String): Parsed {
        if (!raw.startsWith(PREFIX)) throw ParseError.NotALink()
        val params = raw.removePrefix(PREFIX).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else pair.substring(0, idx) to runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val server = params["server"] ?: throw ParseError.Malformed()
        val url = server.toHttpUrlOrNull() ?: throw ParseError.Malformed()
        if (url.scheme != "http" && url.scheme != "https") throw ParseError.Malformed()
        val code = params["code"] ?: throw ParseError.Malformed()
        if (!PairingCode.isPlausible(code)) throw ParseError.Malformed()
        return Parsed(serverURL = server, code = PairingCode.display(code))
    }
}
```

(Note: `toHttpUrlOrNull()` only ever returns http/https URLs, so the scheme check is belt-and-braces — keep it; it documents the constraint and survives a parser swap.)

- [ ] **Step 5: Implement `QRCode.kt`**

```kotlin
package chat.matron.android.designsystem

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/// ZXing QR *generation* for the Link-a-Device screen (`QRCodeWriter` →
/// `BitMatrix` → `Bitmap`). Scanning deliberately uses the Play-services
/// code scanner instead — no ZXing camera machinery.
object QRCode {
    fun bitmap(content: String, sizePx: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(sizePx * sizePx) { i ->
            if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.journal.LinkURITest" --tests "chat.matron.android.designsystem.QRCodeTest"`
Expected: PASS, 7/7.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/chat/matron/android/journal/LinkURI.kt \
        app/src/main/java/chat/matron/android/designsystem/QRCode.kt \
        app/src/test/java/chat/matron/android/journal/LinkURITest.kt \
        app/src/test/java/chat/matron/android/designsystem/QRCodeTest.kt
git commit -m "Add LinkURI payload parser and ZXing QR renderer

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: DeviceLinkViewModel (show side)

**Files:**
- Create: `app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/DeviceLinkViewModelTest.kt`

**Interfaces:**
- Consumes: `LinkStart`, `LinkStatus`, `JournalApiError`, `JournalApi` (Task 1), `LinkURI` (Task 2).
- Produces (Task 5 renders against this):
  - `interface DeviceLinking` (`suspend linkStart(): LinkStart`, `suspend linkStatus(): LinkStatus`, `suspend linkApprove(code: String)`, `suspend linkDeny(code: String)`)
  - `class JournalDeviceLinkService(api: JournalApi) : DeviceLinking`
  - `DeviceLinkViewModel(api, serverURL: String, scope, pollInterval, errorPollInterval)` with `phase: StateFlow<Phase>` (`Loading | Showing(code) | Claimed(deviceName, requesterIP) | Approved | Denied | Unsupported | Error(message)`), `noticeMessage: StateFlow<String?>`, `isSubmitting: StateFlow<Boolean>`, `qrPayload: String?`, `suspend fun start()`, `suspend fun approve()`, `suspend fun deny()`, `fun stop()`

- [ ] **Step 1: Write the failing tests**

Create `DeviceLinkViewModelTest.kt` (same `runBlocking` + child-scope pattern as `PairingViewModelTest`; `waitUntil` from `TestSupport.kt`):

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkStart
import chat.matron.android.journal.LinkStatus
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Scriptable show-side fake: `statusScript` is consumed one result per poll;
/// when it runs dry the last result repeats.
class FakeDeviceLinker : DeviceLinking {
    var startResults = mutableListOf<Result<LinkStart>>(Result.success(LinkStart("KTNM-3VQ8", 120)))
    var statusScript = mutableListOf<Result<LinkStatus>>(Result.success(LinkStatus.Waiting(100)))
    var approveResult: Result<Unit> = Result.success(Unit)
    var denyResult: Result<Unit> = Result.success(Unit)
    var startCount = 0
    var statusCount = 0
    val approvedCodes = mutableListOf<String>()
    val deniedCodes = mutableListOf<String>()

    override suspend fun linkStart(): LinkStart {
        startCount += 1
        return (if (startResults.size > 1) startResults.removeAt(0) else startResults[0]).getOrThrow()
    }
    override suspend fun linkStatus(): LinkStatus {
        statusCount += 1
        return (if (statusScript.size > 1) statusScript.removeAt(0) else statusScript[0]).getOrThrow()
    }
    override suspend fun linkApprove(code: String) {
        approvedCodes.add(code)
        approveResult.getOrThrow()
    }
    override suspend fun linkDeny(code: String) {
        deniedCodes.add(code)
        denyResult.getOrThrow()
    }
}

class DeviceLinkViewModelTest {
    private fun makeVM(fake: FakeDeviceLinker, scope: CoroutineScope) = DeviceLinkViewModel(
        api = fake,
        serverURL = "https://chat.example.com",
        scope = scope,
        pollInterval = 1.milliseconds,
        errorPollInterval = 1.milliseconds,
    )

    @Test
    fun start_showsCodeAndQRPayload() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeDeviceLinker(), scope)
            vm.start()
            assertEquals(DeviceLinkViewModel.Phase.Showing("KTNM-3VQ8"), vm.phase.value)
            assertEquals(
                chat.matron.android.journal.LinkURI.format("https://chat.example.com", "KTNM-3VQ8"),
                vm.qrPayload,
            )
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun start_notFound_meansServerTooOld() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.startResults = mutableListOf(Result.failure(JournalApiError.NotFound))
            val vm = makeVM(fake, scope)
            vm.start()
            assertEquals(DeviceLinkViewModel.Phase.Unsupported, vm.phase.value)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun claimedStatus_flipsToApproveCard() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(
                Result.success(LinkStatus.Waiting(100)),
                Result.success(LinkStatus.Claimed("Pixel 9", "198.51.100.7", 90)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value == DeviceLinkViewModel.Phase.Claimed("Pixel 9", "198.51.100.7") }
            assertEquals(DeviceLinkViewModel.Phase.Claimed("Pixel 9", "198.51.100.7"), vm.phase.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun statusNotFound_regeneratesSilently() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.startResults = mutableListOf(
                Result.success(LinkStart("KTNM-3VQ8", 120)),
                Result.success(LinkStart("WXYZ-2345", 120)),
            )
            fake.statusScript = mutableListOf(
                Result.failure(JournalApiError.NotFound),
                Result.success(LinkStatus.Waiting(100)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value == DeviceLinkViewModel.Phase.Showing("WXYZ-2345") }
            assertEquals(DeviceLinkViewModel.Phase.Showing("WXYZ-2345"), vm.phase.value)
            assertEquals(2, fake.startCount)
            assertNull(vm.noticeMessage.value) // expiry while waiting is routine, not an error
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun approve_isTerminalAndStopsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 90)))
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.approve()
            assertEquals(DeviceLinkViewModel.Phase.Approved, vm.phase.value)
            assertEquals(listOf("KTNM-3VQ8"), fake.approvedCodes)
            val countAtApprove = fake.statusCount
            delay(50)
            assertEquals(countAtApprove, fake.statusCount) // poll loop stopped
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun approve_expired_regeneratesWithNotice() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 5)))
            fake.approveResult = Result.failure(JournalApiError.NotFound)
            fake.startResults = mutableListOf(
                Result.success(LinkStart("KTNM-3VQ8", 120)),
                Result.success(LinkStart("WXYZ-2345", 120)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.approve()
            assertEquals(DeviceLinkViewModel.Phase.Showing("WXYZ-2345"), vm.phase.value)
            assertEquals("Code expired — showing a fresh one", vm.noticeMessage.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun deny_isTerminal() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(Result.success(LinkStatus.Claimed("Pixel 9", "1.1.1.1", 90)))
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Claimed }
            vm.deny()
            assertEquals(DeviceLinkViewModel.Phase.Denied, vm.phase.value)
            assertEquals(listOf("KTNM-3VQ8"), fake.deniedCodes)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun stop_haltsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { fake.statusCount >= 1 }
            vm.stop()
            val count = fake.statusCount
            delay(50)
            assertEquals(count, fake.statusCount)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun transportErrorOnStatus_keepsShowingAndKeepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            fake.statusScript = mutableListOf(
                Result.failure(JournalApiError.Transport("offline")),
                Result.success(LinkStatus.Waiting(90)),
            )
            val vm = makeVM(fake, scope)
            vm.start()
            waitUntil { fake.statusCount >= 2 }
            assertEquals(DeviceLinkViewModel.Phase.Showing("KTNM-3VQ8"), vm.phase.value)
            vm.stop()
        } finally { scope.cancel() }
        Unit
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.DeviceLinkViewModelTest"`
Expected: COMPILE FAILURE — `DeviceLinkViewModel` / `DeviceLinking` unresolved.

- [ ] **Step 3: Implement `DeviceLinkViewModel.kt`**

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkStart
import chat.matron.android.journal.LinkStatus
import chat.matron.android.journal.LinkURI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// The show-QR slice of [JournalApi], extracted so the view model tests
/// against a fake (same pattern as [DevicesProviding]).
interface DeviceLinking {
    suspend fun linkStart(): LinkStart
    suspend fun linkStatus(): LinkStatus
    suspend fun linkApprove(code: String)
    suspend fun linkDeny(code: String)
}

/// Production adapter over [JournalApi].
class JournalDeviceLinkService(private val api: JournalApi) : DeviceLinking {
    override suspend fun linkStart() = api.linkStart()
    override suspend fun linkStatus() = api.linkStatus()
    override suspend fun linkApprove(code: String) = api.linkApprove(code)
    override suspend fun linkDeny(code: String) = api.linkDeny(code)
}

/// Drives Settings → "Link a Device": start a session, render the QR, poll
/// status, and on a claim show the approve card (claimant name + IP — the
/// mandatory confirm-tap of the design; scanning alone never signs anything
/// in). Kotlin port of matron-apple's `DeviceLinkViewModel`.
///
/// Lifecycle: `start()` on screen enter, `stop()` on leave. Status 404 while
/// on screen means the session expired — routine, so the QR silently
/// regenerates. Approve/deny are terminal; the show side does not wait for
/// the claimant's final poll.
class DeviceLinkViewModel(
    private val api: DeviceLinking,
    private val serverURL: String,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface Phase {
        data object Loading : Phase
        data class Showing(val code: String) : Phase
        data class Claimed(val deviceName: String, val requesterIP: String) : Phase
        data object Approved : Phase
        data object Denied : Phase
        /// 404 on start: the server predates /link/*.
        data object Unsupported : Phase
        data class Error(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _noticeMessage = MutableStateFlow<String?>(null)
    /// One-line banner above a regenerated QR ("Code expired — showing a
    /// fresh one") or under a failed tap ("Couldn't approve — try again.").
    val noticeMessage: StateFlow<String?> = _noticeMessage.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    /// True while an approve/deny round-trip is in flight; reentrant taps are
    /// ignored and the poll loop skips regeneration to avoid racing it.
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    /// The full QR payload for the current code (null unless Showing).
    val qrPayload: String?
        get() = (_phase.value as? Phase.Showing)?.let { LinkURI.format(serverURL, it.code) }

    private var pollTask: Job? = null
    /// The active session's display code — what approve/deny send back as the
    /// belt-and-braces intent check.
    private var currentCode: String? = null

    suspend fun start() {
        stop()
        _noticeMessage.value = null
        _phase.value = Phase.Loading
        startSession()
    }

    fun stop() {
        pollTask?.cancel()
        pollTask = null
    }

    suspend fun approve() {
        val code = currentCode ?: return
        if (_phase.value !is Phase.Claimed || _isSubmitting.value) return
        _isSubmitting.value = true
        try {
            api.linkApprove(code)
            stop()
            _phase.value = Phase.Approved
        } catch (e: JournalApiError.NotFound) {
            _noticeMessage.value = "Code expired — showing a fresh one"
            stop()
            startSession()
        } catch (e: JournalApiError.Conflict) {
            // Nothing left to approve (raced expiry/replacement) — same
            // recovery as expiry: fresh code.
            _noticeMessage.value = "Code expired — showing a fresh one"
            stop()
            startSession()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _noticeMessage.value = "Couldn't approve — try again."
        } finally {
            _isSubmitting.value = false
        }
    }

    suspend fun deny() {
        val code = currentCode ?: return
        if (_phase.value !is Phase.Claimed || _isSubmitting.value) return
        _isSubmitting.value = true
        try {
            api.linkDeny(code)
            stop()
            _phase.value = Phase.Denied
        } catch (e: JournalApiError.NotFound) {
            stop()
            startSession()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _noticeMessage.value = "Couldn't deny — try again."
        } finally {
            _isSubmitting.value = false
        }
    }

    private suspend fun startSession() {
        try {
            val started = api.linkStart()
            currentCode = started.code
            _phase.value = Phase.Showing(started.code)
            startPolling()
        } catch (e: JournalApiError.NotFound) {
            _phase.value = Phase.Unsupported
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _phase.value = Phase.Error("Couldn't reach the server — try again.")
        }
    }

    private fun startPolling() {
        pollTask?.cancel()
        pollTask = scope.launch {
            var interval = pollInterval
            while (isActive) {
                delay(interval)
                if (!isActive) return@launch
                if (_isSubmitting.value) continue // don't race an in-flight tap
                try {
                    when (val status = api.linkStatus()) {
                        is LinkStatus.Waiting -> Unit // phase already Showing
                        is LinkStatus.Claimed -> {
                            if (_phase.value !is Phase.Claimed) {
                                _phase.value = Phase.Claimed(status.deviceName, status.requesterIP)
                            }
                        }
                    }
                    interval = pollInterval
                } catch (e: JournalApiError.NotFound) {
                    // Expired (routine): regenerate silently. startSession
                    // spawns a fresh poll task; this one must end.
                    if (!isActive || _isSubmitting.value) return@launch
                    startSession()
                    return@launch
                } catch (e: JournalApiError.Unauthenticated) {
                    // Starter signed out / revoked mid-flow: the host screen
                    // closes on its own sign-out path; stop quietly.
                    return@launch
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: Throwable) {
                    interval = errorPollInterval // network loss: back off, keep trying
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.DeviceLinkViewModelTest"`
Expected: PASS, 9/9.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt \
        app/src/test/java/chat/matron/android/viewmodels/DeviceLinkViewModelTest.kt
git commit -m "Add DeviceLinkViewModel (show-QR state machine)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: LinkSignInViewModel (claimant side)

**Files:**
- Create: `app/src/main/java/chat/matron/android/viewmodels/LinkSignInViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/LinkSignInViewModelTest.kt`

**Interfaces:**
- Consumes: `LinkClaim`, `LinkPollResult`, `LinkApproval`, `JournalApiError`, `JournalApi`, `LinkURI`, `PairingCode` (journal); `AuthService`, `ServerURLValidator` (auth); `UserSession` (models); test fake: `FakeAuthService` exists in `app/src/test/java/chat/matron/android/auth/` — reuse it (`chat.matron.android.auth.FakeAuthService`; check its stub fields before writing the test and adapt setter names to what it actually exposes — it needs only `persist` capture for these tests; if it lacks a persisted-sessions list, add one there rather than forking a new fake).
- Produces (Task 6 renders against this):
  - `interface LinkClaiming` (`suspend linkClaim(code, deviceName): LinkClaim`, `suspend linkPoll(claimToken): LinkPollResult`) + `class JournalLinkClaimService(api: JournalApi) : LinkClaiming`
  - `LinkSignInViewModel(auth, deviceDisplayName, scope, apiFactory, pollInterval, errorPollInterval)` with `state: StateFlow<State>` (`Idle | Claiming | WaitingForApproval | Error(message) | SignedIn(session)`), `var serverURL: String`, `var codeInput: String` (auto-formatting), `suspend fun handleScanned(payload: String)`, `suspend fun submitManual()`, `fun cancel()`

- [ ] **Step 1: Write the failing tests**

Create `LinkSignInViewModelTest.kt`:

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.auth.FakeAuthService
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkApproval
import chat.matron.android.journal.LinkClaim
import chat.matron.android.journal.LinkPollResult
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLinkClaimer : LinkClaiming {
    var claimResult: Result<LinkClaim> = Result.success(LinkClaim("aa11", 60))
    /// Consumed one per poll; last repeats when dry.
    var pollScript = mutableListOf<Result<LinkPollResult>>(Result.success(LinkPollResult.Pending))
    val claimedCodes = mutableListOf<String>()
    val claimedDeviceNames = mutableListOf<String>()
    var pollCount = 0

    override suspend fun linkClaim(code: String, deviceName: String): LinkClaim {
        claimedCodes.add(code)
        claimedDeviceNames.add(deviceName)
        return claimResult.getOrThrow()
    }
    override suspend fun linkPoll(claimToken: String): LinkPollResult {
        pollCount += 1
        return (if (pollScript.size > 1) pollScript.removeAt(0) else pollScript[0]).getOrThrow()
    }
}

class LinkSignInViewModelTest {
    private val scannedURI = "matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8"

    private fun makeVM(fake: FakeLinkClaimer, scope: CoroutineScope, auth: FakeAuthService = FakeAuthService()) =
        LinkSignInViewModel(
            auth = auth,
            deviceDisplayName = "Matron Android",
            scope = scope,
            apiFactory = { fake },
            pollInterval = 1.milliseconds,
            errorPollInterval = 1.milliseconds,
        )

    @Test
    fun scanned_happyPath_buildsAndPersistsSession() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.success(LinkPollResult.Pending),
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val auth = FakeAuthService()
            val vm = makeVM(fake, scope, auth)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            val session = (vm.state.value as LinkSignInViewModel.State.SignedIn).session
            assertEquals("dan", session.userID)               // username, never typed
            assertEquals("42", session.deviceID)
            assertEquals("https://chat.example.com", session.homeserverURL)
            assertEquals("tok99", session.accessToken)
            assertEquals(listOf(session), auth.persistedSessions) // persisted BEFORE state flips
            assertEquals(listOf("KTNM-3VQ8"), fake.claimedCodes)
            assertEquals(listOf("Matron Android"), fake.claimedDeviceNames)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun scanned_notALink_andWrongVersion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeLinkClaimer(), scope)
            vm.handleScanned("https://a-random-website.example/qr")
            assertEquals(LinkSignInViewModel.State.Error("Not a Matron sign-in code."), vm.state.value)
            vm.handleScanned("matron://link?v=2&server=https%3A%2F%2Fx.example&code=KTNM-3VQ8")
            assertEquals(
                LinkSignInViewModel.State.Error("This QR code needs a newer version of Matron."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun manual_happyPath_normalizesCodeAndURL() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val vm = makeVM(fake, scope)
            vm.serverURL = "chat.example.com" // ServerURLValidator adds https://
            vm.codeInput = "ktnm3vq8"
            assertEquals("KTNM-3VQ8", vm.codeInput) // auto-format like PairingViewModel
            vm.submitManual()
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            assertEquals(listOf("KTNM-3VQ8"), fake.claimedCodes)
            val session = (vm.state.value as LinkSignInViewModel.State.SignedIn).session
            assertTrue(session.homeserverURL.startsWith("https://chat.example.com"))
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun manual_invalidURL_errors() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val vm = makeVM(FakeLinkClaimer(), scope)
            vm.serverURL = "not a url"
            vm.codeInput = "KTNM-3VQ8"
            vm.submitManual()
            assertEquals(
                LinkSignInViewModel.State.Error("That doesn't look like a valid server URL."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun claim_conflict_notFound_rateLimited() = runBlocking {
        val cases = listOf(
            JournalApiError.Conflict to "This code was already used. Generate a new one on your signed-in device.",
            JournalApiError.NotFound to "Code not recognized or expired. Show a fresh QR code and try again.",
            JournalApiError.RateLimited to "Too many attempts — try again in a minute.",
        )
        for ((error, message) in cases) {
            val scope = CoroutineScope(coroutineContext + Job())
            try {
                val fake = FakeLinkClaimer()
                fake.claimResult = Result.failure(error)
                val vm = makeVM(fake, scope)
                vm.handleScanned(scannedURI)
                assertEquals(LinkSignInViewModel.State.Error(message), vm.state.value)
            } finally { scope.cancel() }
        }
        Unit
    }

    @Test
    fun poll_denied() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(Result.success(LinkPollResult.Denied))
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.Error }
            assertEquals(
                LinkSignInViewModel.State.Error("Sign-in was denied on the other device."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun poll_expired() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(Result.failure(JournalApiError.NotFound))
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.Error }
            assertEquals(LinkSignInViewModel.State.Error("Sign-in expired. Scan again."), vm.state.value)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun poll_transportError_backsOffAndKeepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            fake.pollScript = mutableListOf(
                Result.failure(JournalApiError.Transport("offline")),
                Result.success(LinkPollResult.Approved(LinkApproval("t", 1, 1, "dan"))),
            )
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { vm.state.value is LinkSignInViewModel.State.SignedIn }
            assertTrue(vm.state.value is LinkSignInViewModel.State.SignedIn) // one dropped poll never kills the flow
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun cancel_stopsPollingAndReturnsToIdle() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeLinkClaimer()
            val vm = makeVM(fake, scope)
            vm.handleScanned(scannedURI)
            waitUntil { fake.pollCount >= 1 }
            vm.cancel()
            assertEquals(LinkSignInViewModel.State.Idle, vm.state.value)
            val count = fake.pollCount
            delay(50)
            assertEquals(count, fake.pollCount)
        } finally { scope.cancel() }
        Unit
    }
}
```

If `FakeAuthService` lacks a `persistedSessions` list, add one there (mirroring the Apple `FakeAuthService`): a `val persistedSessions = mutableListOf<UserSession>()` appended to in `persist`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.LinkSignInViewModelTest"`
Expected: COMPILE FAILURE — `LinkSignInViewModel` / `LinkClaiming` unresolved.

- [ ] **Step 3: Implement `LinkSignInViewModel.kt`**

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthService
import chat.matron.android.auth.ServerURLValidator
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LinkClaim
import chat.matron.android.journal.LinkPollResult
import chat.matron.android.journal.LinkURI
import chat.matron.android.journal.PairingCode
import chat.matron.android.models.UserSession
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// The claimant slice of [JournalApi] (both calls unauthenticated),
/// extracted so the view model tests against a fake.
interface LinkClaiming {
    suspend fun linkClaim(code: String, deviceName: String): LinkClaim
    suspend fun linkPoll(claimToken: String): LinkPollResult
}

/// Production adapter over [JournalApi].
class JournalLinkClaimService(private val api: JournalApi) : LinkClaiming {
    override suspend fun linkClaim(code: String, deviceName: String) = api.linkClaim(code, deviceName)
    override suspend fun linkPoll(claimToken: String) = api.linkPoll(claimToken)
}

/// Signs a NEW device in from a link code — the claimant half of QR
/// device-link login. Kotlin port of matron-apple's `LinkSignInViewModel`.
/// Two entry points: [handleScanned] (scanner, full `matron://link` URI) and
/// [submitManual] (typed server URL + code). Both converge on claim → poll →
/// build the same [UserSession] shape password login builds (`userID` = the
/// server-returned username) → `auth.persist` → `SignedIn`, which the host
/// screen forwards to the normal `onSignedIn` path.
class LinkSignInViewModel(
    private val auth: AuthService,
    private val deviceDisplayName: String,
    private val scope: CoroutineScope,
    private val apiFactory: (String) -> LinkClaiming = { JournalLinkClaimService(JournalApi(it)) },
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface State {
        data object Idle : State
        data object Claiming : State
        data object WaitingForApproval : State
        data class Error(val message: String) : State
        data class SignedIn(val session: UserSession) : State
    }

    /// Manual path: the sign-in form's server field seeds this at submit.
    var serverURL: String = ""

    private var _codeInput: String = ""
    /// Auto-formatted as `XXXX-XXXX` while typing, like PairingViewModel.
    var codeInput: String
        get() = _codeInput
        set(value) { _codeInput = PairingCode.display(value) }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollTask: Job? = null

    suspend fun handleScanned(payload: String) {
        val parsed = try {
            LinkURI.parse(payload)
        } catch (e: LinkURI.ParseError.UnsupportedVersion) {
            _state.value = State.Error("This QR code needs a newer version of Matron.")
            return
        } catch (e: LinkURI.ParseError) {
            _state.value = State.Error("Not a Matron sign-in code.")
            return
        }
        claim(server = parsed.serverURL, code = parsed.code)
    }

    suspend fun submitManual() {
        val raw = serverURL.trim()
        if (raw.isEmpty() || !PairingCode.isPlausible(codeInput)) return
        val url = try {
            ServerURLValidator.normalize(raw)
        } catch (e: ServerURLValidator.ValidationError) {
            _state.value = State.Error("That doesn't look like a valid server URL.")
            return
        }
        claim(server = url, code = PairingCode.display(codeInput))
    }

    /// Back out: stop polling and return to the sign-in form. The show side
    /// still sees `claimed` and can deny or let the code expire.
    fun cancel() {
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Idle
    }

    private suspend fun claim(server: String, code: String) {
        if (_state.value is State.Claiming || _state.value is State.WaitingForApproval) return
        _state.value = State.Claiming
        val api = apiFactory(server)
        val claim = try {
            api.linkClaim(code, deviceDisplayName)
        } catch (e: JournalApiError.Conflict) {
            _state.value = State.Error("This code was already used. Generate a new one on your signed-in device.")
            return
        } catch (e: JournalApiError.NotFound) {
            _state.value = State.Error("Code not recognized or expired. Show a fresh QR code and try again.")
            return
        } catch (e: JournalApiError.RateLimited) {
            _state.value = State.Error("Too many attempts — try again in a minute.")
            return
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            _state.value = State.Error("Couldn't reach the server — try again.")
            return
        }
        _state.value = State.WaitingForApproval
        startPolling(api, server, claim.claimToken)
    }

    private fun startPolling(api: LinkClaiming, server: String, claimToken: String) {
        pollTask?.cancel()
        pollTask = scope.launch {
            var interval = pollInterval
            while (isActive) {
                delay(interval)
                if (!isActive) return@launch
                try {
                    when (val result = api.linkPoll(claimToken)) {
                        is LinkPollResult.Pending -> interval = pollInterval
                        is LinkPollResult.Denied -> {
                            _state.value = State.Error("Sign-in was denied on the other device.")
                            return@launch
                        }
                        is LinkPollResult.Approved -> {
                            val a = result.approval
                            val session = UserSession(
                                userID = a.username,
                                deviceID = a.deviceID.toString(),
                                homeserverURL = server,
                                accessToken = a.token,
                            )
                            try {
                                auth.persist(session)
                            } catch (e: Throwable) {
                                _state.value = State.Error("Signed in, but couldn't save the session — try again.")
                                return@launch
                            }
                            _state.value = State.SignedIn(session)
                            return@launch
                        }
                    }
                } catch (e: JournalApiError.NotFound) {
                    _state.value = State.Error("Sign-in expired. Scan again.")
                    return@launch
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: Throwable) {
                    interval = errorPollInterval // network loss: back off, keep trying
                }
            }
        }
    }
}
```

(Check `ServerURLValidator.normalize`'s return type — Android's returns a `String`; if it returns `HttpUrl`, call `.toString()`. Match `SignInViewModel.submit`'s usage at `viewmodels/SignInViewModel.kt:39`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.LinkSignInViewModelTest"`
Expected: PASS, 9/9.

- [ ] **Step 5: Run the full unit-test suite (regression)**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/LinkSignInViewModel.kt \
        app/src/test/java/chat/matron/android/viewmodels/LinkSignInViewModelTest.kt \
        app/src/test/java/chat/matron/android/auth/FakeAuthService.kt
git commit -m "Add LinkSignInViewModel (claimant state machine)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Show side — DeviceLinkScreen + Settings entry

**Files:**
- Create: `app/src/main/java/chat/matron/android/features/settings/DeviceLinkScreen.kt`
- Modify: `app/src/main/java/chat/matron/android/features/settings/DeviceSettingsScreen.kt` (new `onLinkDevice` param + row)
- Modify: `app/src/main/java/chat/matron/android/MainActivity.kt` (settings route ~line 214; new `link-device` route after `devices` ~line 230)
- Modify: `app/src/main/java/chat/matron/android/AppDependencies.kt` (factory beside `devicesService` line 216)

**Interfaces:**
- Consumes: `DeviceLinkViewModel`/`DeviceLinking`/`JournalDeviceLinkService` (Task 3), `QRCode.bitmap` (Task 2), `AppDependencies.core(session).api`.
- Produces: `AppDependencies.deviceLinkService(session: UserSession): DeviceLinking`.

- [ ] **Step 1: Add the dependency factory**

In `AppDependencies.kt`, directly after `devicesService` (lines 215-217):

```kotlin
    /** Show-QR surface (Settings → Link a Device). */
    fun deviceLinkService(session: UserSession): DeviceLinking =
        JournalDeviceLinkService(core(session).api)
```

(add the `chat.matron.android.viewmodels.DeviceLinking` / `JournalDeviceLinkService` imports alongside the existing `DevicesProviding` import.)

- [ ] **Step 2: Create `DeviceLinkScreen.kt`**

```kotlin
package chat.matron.android.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.QRCode
import chat.matron.android.viewmodels.DeviceLinkViewModel
import chat.matron.android.viewmodels.DeviceLinking
import kotlinx.coroutines.launch

/**
 * Settings → "Link a Device": shows a QR the new device scans, then the
 * approve card once someone claims it. The QR self-refreshes on expiry for
 * as long as the screen is open. Ports matron-apple's DeviceLinkView.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkScreen(
    api: DeviceLinking,
    serverURL: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { DeviceLinkViewModel(api = api, serverURL = serverURL, scope = scope) }
    val phase by viewModel.phase.collectAsState()
    val notice by viewModel.noticeMessage.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link a Device") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (notice != null) {
                Text(notice!!, color = MaterialTheme.colorScheme.tertiary)
            }
            when (val p = phase) {
                DeviceLinkViewModel.Phase.Loading -> CircularProgressIndicator()
                is DeviceLinkViewModel.Phase.Showing -> {
                    val payload = viewModel.qrPayload
                    if (payload != null) {
                        val bitmap = remember(payload) { QRCode.bitmap(payload) }
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Sign-in QR code",
                            modifier = Modifier.size(240.dp),
                        )
                    }
                    // Camera-less fallback: typed under "Have a link code?"
                    Text(
                        p.code,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "On your new device, open Matron and choose “Scan QR code” — or type the code under “Have a link code?”. Codes refresh automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is DeviceLinkViewModel.Phase.Claimed -> {
                    Text(
                        "${p.deviceName} at ${p.requesterIP} wants to sign in to your account. Only approve if this is your device.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { viewModel.deny() } },
                            enabled = !isSubmitting,
                        ) { Text("Deny") }
                        Button(
                            onClick = { scope.launch { viewModel.approve() } },
                            enabled = !isSubmitting,
                        ) { Text("Approve") }
                    }
                    Text(
                        "Approving signs that device in with full access to your account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DeviceLinkViewModel.Phase.Approved ->
                    Text("Approved — finishing sign-in on the other device.")
                DeviceLinkViewModel.Phase.Denied ->
                    Text("Denied. No device was signed in.")
                DeviceLinkViewModel.Phase.Unsupported ->
                    Text("Server doesn't support device linking yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                is DeviceLinkViewModel.Phase.Error -> {
                    Text(p.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { scope.launch { viewModel.start() } }) { Text("Try again") }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Add the Settings row and route**

In `DeviceSettingsScreen.kt`, add a parameter after `onManageDevices` (line 43):

```kotlin
    onLinkDevice: (() -> Unit)? = null,
```

and inside the Devices section (after the Manage Devices `Row`, before the section's closing brace, ~line 79):

```kotlin
                    if (onLinkDevice != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLinkDevice)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Link a Device", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
```

In `MainActivity.kt`, add to the `DeviceSettingsScreen(...)` call (~line 214):

```kotlin
                onLinkDevice = { nav.navigate("link-device") },
```

and after the `composable("devices") { ... }` block (~line 230):

```kotlin
        composable("link-device") {
            DeviceLinkScreen(
                api = deps.deviceLinkService(session),
                serverURL = session.homeserverURL,
                onBack = { nav.popBackStack() },
            )
        }
```

- [ ] **Step 4: Build and run the suite**

```bash
./gradlew assembleDebug testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/features/settings/DeviceLinkScreen.kt \
        app/src/main/java/chat/matron/android/features/settings/DeviceSettingsScreen.kt \
        app/src/main/java/chat/matron/android/MainActivity.kt \
        app/src/main/java/chat/matron/android/AppDependencies.kt
git commit -m "Add Link-a-Device screen (show QR + approve card)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Scan side — sign-in screen integration

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (code-scanner dep)
- Modify: `app/src/main/java/chat/matron/android/features/onboarding/SignInScreen.kt`
- Modify: `app/src/main/java/chat/matron/android/MainActivity.kt` (sign-in wiring, lines 89-100)

**Interfaces:**
- Consumes: `LinkSignInViewModel` (Task 4); `GmsBarcodeScanning` / `GmsBarcodeScannerOptions` / `Barcode` from the new dep.
- Produces: nothing downstream — final integration.

- [ ] **Step 1: Add the code-scanner dependency**

`gradle/libs.versions.toml` — under `[versions]`:

```toml
playServicesCodeScanner = "16.1.0"
```

under `[libraries]`:

```toml
play-services-code-scanner = { module = "com.google.android.gms:play-services-code-scanner", version.ref = "playServicesCodeScanner" }
```

`app/build.gradle.kts` — in `dependencies {}`:

```kotlin
    // Sign-in QR scanning via the Play-services code scanner: Google-provided
    // capture UI, NO CAMERA permission and no manifest change. Degrades to the
    // manual link-code path when Play services is unavailable.
    implementation(libs.play.services.code.scanner)
```

- [ ] **Step 2: Integrate into `SignInScreen.kt`**

Change the signature and add the link UI. The composable becomes:

```kotlin
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    linkViewModel: LinkSignInViewModel,
    onSignedIn: (UserSession) -> Unit,
) {
```

New imports:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import chat.matron.android.viewmodels.LinkSignInViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
```

Inside the composable, alongside the existing state:

```kotlin
    val linkState by linkViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showManualCode by remember { mutableStateOf(false) }
    var linkCode by remember { mutableStateOf(linkViewModel.codeInput) }
    var scannerUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(linkState) {
        (linkState as? LinkSignInViewModel.State.SignedIn)?.let { onSignedIn(it.session) }
    }
```

Then, if `linkState is LinkSignInViewModel.State.WaitingForApproval`, render the waiting block INSTEAD of the form column's fields (wrap the existing fields in an `if/else`):

```kotlin
            if (linkState is LinkSignInViewModel.State.WaitingForApproval) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Waiting for approval on your other device…")
                }
                Text(
                    "Approve the request on your signed-in device to finish.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { linkViewModel.cancel() }) { Text("Cancel") }
            } else {
                // ... the existing Server/Credentials fields, error text and
                // Sign-in button, unchanged ...
```

and after the existing Sign-in `Button` (still inside the `else`), append the link section:

```kotlin
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("From another device", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = {
                        val options = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let { payload ->
                                    scope.launch { linkViewModel.handleScanned(payload) }
                                }
                            }
                            .addOnFailureListener { scannerUnavailable = true }
                        // Cancelled scans call neither listener path we care
                        // about — the user is simply back on this form.
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
                if (scannerUnavailable) {
                    Text(
                        "Scanner unavailable — use a link code instead.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { showManualCode = !showManualCode }) {
                    Text(if (showManualCode) "Hide link code" else "Have a link code?")
                }
                if (showManualCode) {
                    OutlinedTextField(
                        value = linkCode,
                        onValueChange = {
                            linkViewModel.codeInput = it
                            linkCode = linkViewModel.codeInput // reflect auto-formatting
                        },
                        label = { Text("XXXX-XXXX") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            linkViewModel.serverURL = server // shares the form's server field
                            scope.launch { linkViewModel.submitManual() }
                        },
                        enabled = server.isNotEmpty() && linkCode.length >= 9,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in with code") }
                    Text(
                        "On your signed-in device: Settings → Link a Device. Enter the server URL above and the code shown under the QR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                (linkState as? LinkSignInViewModel.State.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error)
                }
```

(Follow the existing file's field-mirroring pattern exactly; `server`, `scope` already exist in the composable.)

- [ ] **Step 3: Wire the view model in `MainActivity.kt`**

Change the signed-out branch (lines 89-90) to:

```kotlin
                    val vm = remember { SignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android") }
                    val linkVm = remember {
                        LinkSignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android", scope = scope)
                    }
                    SignInScreen(viewModel = vm, linkViewModel = linkVm, onSignedIn = { s ->
```

(the closure body — `awaitPendingTeardown()` + `wipeLocalDataForFreshLogin()` + `session = s` — is unchanged: link sign-in reuses the same fresh-login wipe path. Add the `LinkSignInViewModel` import.)

- [ ] **Step 4: Build and run the full suite**

```bash
./gradlew assembleDebug testDebugUnitTest
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Verify no manifest change crept in**

Run: `git diff --stat app/src/main/AndroidManifest.xml`
Expected: empty output (the code scanner needs no permission entry).

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
        app/src/main/java/chat/matron/android/features/onboarding/SignInScreen.kt \
        app/src/main/java/chat/matron/android/MainActivity.kt
git commit -m "Add QR-scan and link-code sign-in to SignInScreen

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
