# Link Rendezvous — Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The signed-out sign-in screen gains a Scan/Show tab pair (Show fetches a rendezvous from the shared relay and displays a `matron://rlink` QR); Settings → Link a Device gains a mirrored Show/Scan pair (Scan completes the hand-off for a computer showing that QR); the approve-card copy is sharpened.

**Architecture:** A new `RendezvousURI` payload object and `RelayApi` (OkHttp client for `https://push.matron.chat`) live in `journal/`. A new `RendezvousSignInViewModel` wraps rendezvous create/poll and delegates to the existing `LinkSignInViewModel` (claim → poll → persist untouched). The Settings side reuses the live `DeviceLinkViewModel` session — its code already exists when the screen is open — adding one `offerScanned` method. All new async flows use the established generation-guard pattern; race tests use the NonCancellable-gated fake pattern.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), OkHttp + kotlinx.serialization.json, JUnit4 + `runBlocking`/`waitUntil` (the repo's existing coroutine-test convention — no `runTest`).

**Spec:** `matron-journal:docs/superpowers/specs/2026-07-18-link-rendezvous-design.md` (approved). Wire contract from `matron-journal:docs/superpowers/plans/2026-07-18-link-rendezvous-server.md` Task 2.

## Global Constraints

- Repo `/Users/danbarker/Dev/matron-android`; create branch `feat/link-rendezvous` from `main` before the first commit.
- Tests: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17` then `./gradlew :app:testDebugUnitTest`. Build check: `./gradlew :app:assembleDebug`.
- Relay base URL is the hardcoded constant `https://push.matron.chat` (forks edit the constant). No UI override.
- Relay wire contract: `POST /link/rendezvous` (empty body) → `201 {rid, secret, expires_in}`; `GET /link/rendezvous/<rid>?secret=<hex>` → 204 waiting | `200 {server, code}` | 403 | 404 | 429; `POST /link/rendezvous/<rid>/offer` `{server, code}` → 204 | 409 | 404 | 400 | 429. `code` comes back dashed (`XXXX-XXXX`).
- QR payload: `matron://rlink?v=1&rid=<rid>`; `rid` is exactly 26 chars of `0123456789BCDFGHJKMNPQRSTVWXYZ`. Unknown version → the existing copy `"This QR code needs a newer version of Matron."`; not ours → `"Not a Matron link code."`.
- Sharpened approve copy (replaces the current footnote verbatim, plain text — Compose `Text` renders no markdown): `This signs a computer into your account — only approve if it's yours, in front of you.`
- Show-tab caption: `Scan this with a phone that's signed in to Matron`. Connecting copy: `Connecting to <server host>…`.
- All user-facing strings are Kotlin literals (this repo has no strings.xml) — match the surrounding files.
- Generation-guard every new async flow exactly like `LinkSignInViewModel`/`DeviceLinkViewModel` (capture `generation`, bump in `stop()`/`cancel()`, re-check after every suspension before any state write; rethrow `CancellationException` first).
- Commit messages end with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`

---

### Task 1: `RendezvousURI` payload format

**Files:**
- Create: `app/src/main/java/chat/matron/android/journal/RendezvousURI.kt`
- Test: `app/src/test/java/chat/matron/android/journal/RendezvousURITest.kt`

**Interfaces:**
- Produces (used by Tasks 3, 4): `object RendezvousURI` with `fun format(rid: String): String`, `fun parse(raw: String): String` (returns the rid), `sealed class ParseError : Exception()` with `NotALink`, `UnsupportedVersion`, `Malformed`.

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/chat/matron/android/journal/RendezvousURITest.kt`:

```kotlin
package chat.matron.android.journal

import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test

class RendezvousURITest {
    private val rid = "23456789BCDFGHJKMNPQRSTVWX" // 26 chars, all in alphabet

    @Test
    fun format_roundTripsThroughParse() {
        val uri = RendezvousURI.format(rid)
        assertEquals("matron://rlink?v=1&rid=$rid", uri)
        assertEquals(rid, RendezvousURI.parse(uri))
    }

    @Test
    fun parse_rejectsNonRlinkPayloads_asNotALink() {
        for (raw in listOf("https://example.com", "matron://link?v=1&server=x&code=ABCD-2345", "random text", "")) {
            assertFailsWith<RendezvousURI.ParseError.NotALink>(raw) { RendezvousURI.parse(raw) }
        }
    }

    @Test
    fun parse_futureVersionIsUnsupported_missingVersionIsMalformed() {
        assertFailsWith<RendezvousURI.ParseError.UnsupportedVersion> { RendezvousURI.parse("matron://rlink?v=2&rid=$rid") }
        assertFailsWith<RendezvousURI.ParseError.Malformed> { RendezvousURI.parse("matron://rlink?rid=$rid") }
    }

    @Test
    fun parse_ridShapeIsEnforced() {
        for (bad in listOf(
            "matron://rlink?v=1",                      // missing rid
            "matron://rlink?v=1&rid=SHORT",            // wrong length
            "matron://rlink?v=1&rid=${"A".repeat(26)}", // A not in alphabet
            "matron://rlink?v=1&rid=${rid}X",          // 27 chars
        )) {
            assertFailsWith<RendezvousURI.ParseError.Malformed>(bad) { RendezvousURI.parse(bad) }
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.journal.RendezvousURITest'`
Expected: FAIL (compile error — `RendezvousURI` unresolved).

- [ ] **Step 3: Implement**

Create `app/src/main/java/chat/matron/android/journal/RendezvousURI.kt`:

```kotlin
package chat.matron.android.journal

/// The rendezvous QR payload — the single place the format is known:
/// `matron://rlink?v=1&rid=<26-char rid>`. The reverse of [LinkURI]: this QR
/// is SHOWN by a signed-out device and SCANNED by a signed-in phone. It
/// carries only the rendezvous id — never the poll secret, never a server.
/// Apple carries an equivalent parser; the relay never sees the URI.
///
/// Parsed by hand (prefix + query split, like [LinkURI]) so plain JVM unit
/// tests cover it without Robolectric.
object RendezvousURI {
    sealed class ParseError : Exception() {
        /// Not ours at all — scanner shows "Not a Matron link code."
        class NotALink : ParseError()
        /// Ours, but a future version — scanner shows "update the app".
        class UnsupportedVersion : ParseError()
        /// Ours and v=1, but the rid doesn't parse.
        class Malformed : ParseError()
    }

    private const val PREFIX = "matron://rlink?"
    // Same alphabet as PairingCode / link codes; 26 chars ≈ 128 bits.
    private val RID_RE = Regex("^[0-9BCDFGHJKMNPQRSTVWXYZ]{26}$")

    fun format(rid: String): String = "${PREFIX}v=1&rid=$rid" // rid alphabet needs no encoding

    fun parse(raw: String): String {
        if (!raw.startsWith(PREFIX)) throw ParseError.NotALink()
        val params = raw.removePrefix(PREFIX).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val rid = params["rid"] ?: throw ParseError.Malformed()
        if (!RID_RE.matches(rid)) throw ParseError.Malformed()
        return rid
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.journal.RendezvousURITest'` — Expected: PASS (4/4).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/journal/RendezvousURI.kt app/src/test/java/chat/matron/android/journal/RendezvousURITest.kt
git commit -m "Add RendezvousURI payload format

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `RelayApi`

**Files:**
- Create: `app/src/main/java/chat/matron/android/journal/RelayApi.kt`
- Test: `app/src/test/java/chat/matron/android/journal/RelayApiTest.kt`

**Interfaces:**
- Produces (used by Tasks 3, 4):

```kotlin
object MatronRelay { const val BASE_URL = "https://push.matron.chat" }

data class Rendezvous(val rid: String, val secret: String, val expiresIn: Int)
sealed interface RendezvousPollResult {
    data object Waiting : RendezvousPollResult
    data class Offered(val server: String, val code: String) : RendezvousPollResult
}
sealed class RelayError : Exception() {
    class NotFound : RelayError(); class Conflict : RelayError(); class Forbidden : RelayError()
    class RateLimited : RelayError(); class Transport(val detail: String) : RelayError()
}
interface RelayRendezvousing {
    suspend fun createRendezvous(): Rendezvous
    suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult
    suspend fun offerRendezvous(rid: String, server: String, code: String)
}
class RelayApi(baseUrl: String = MatronRelay.BASE_URL, client: OkHttpClient = OkHttpClient()) : RelayRendezvousing
```

- [ ] **Step 1: Write failing tests** (response mappers are pure functions — the OkHttp glue mirrors `JournalApi`'s `execute` and stays thin)

Create `app/src/test/java/chat/matron/android/journal/RelayApiTest.kt`:

```kotlin
package chat.matron.android.journal

import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayApiTest {
    private val secret = "a".repeat(64)

    @Test
    fun mapCreate_parses201() {
        val r = RelayApi.mapCreate(201, """{"rid":"23456789BCDFGHJKMNPQRSTVWX","secret":"$secret","expires_in":180}""")
        assertEquals(Rendezvous("23456789BCDFGHJKMNPQRSTVWX", secret, 180), r)
    }

    @Test
    fun mapCreate_errors() {
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapCreate(429, """{"status":429,"reason":"rate_limited"}""") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapCreate(201, """{"nope":true}""") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapCreate(500, "") }
    }

    @Test
    fun mapPoll_coversAllStates() {
        assertEquals(RendezvousPollResult.Waiting, RelayApi.mapPoll(204, ""))
        assertEquals(
            RendezvousPollResult.Offered("https://j.example.com", "2345-6789"),
            RelayApi.mapPoll(200, """{"server":"https://j.example.com","code":"2345-6789"}"""),
        )
        assertFailsWith<RelayError.NotFound> { RelayApi.mapPoll(404, "") }
        assertFailsWith<RelayError.Forbidden> { RelayApi.mapPoll(403, "") }
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapPoll(429, "") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapPoll(200, """{"server":"https://x"}""") }
    }

    @Test
    fun mapOffer_coversAllStates() {
        RelayApi.mapOffer(204) // no throw
        assertFailsWith<RelayError.Conflict> { RelayApi.mapOffer(409) }
        assertFailsWith<RelayError.NotFound> { RelayApi.mapOffer(404) }
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapOffer(429) }
        assertFailsWith<RelayError.Transport> { RelayApi.mapOffer(400) }
    }

    @Test
    fun requestBuilders_hitTheDocumentedPathsAndBodies() {
        val create = RelayApi.createRequest("https://push.matron.chat")
        assertEquals("https://push.matron.chat/link/rendezvous", create.url.toString())
        assertEquals("POST", create.method)

        val poll = RelayApi.pollRequest("https://push.matron.chat", "RID23456789BCDFGHJKMNPQRST", "SEC")
        assertEquals("https://push.matron.chat/link/rendezvous/RID23456789BCDFGHJKMNPQRST?secret=SEC", poll.url.toString())
        assertEquals("GET", poll.method)

        val offer = RelayApi.offerRequest("https://push.matron.chat", "RID23456789BCDFGHJKMNPQRST", "https://j.example.com", "2345-6789")
        assertEquals("https://push.matron.chat/link/rendezvous/RID23456789BCDFGHJKMNPQRST/offer", offer.url.toString())
        assertEquals("POST", offer.method)
        val buffer = okio.Buffer().also { offer.body!!.writeTo(it) }
        assertEquals("""{"server":"https://j.example.com","code":"2345-6789"}""", buffer.readUtf8())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.journal.RelayApiTest'`
Expected: FAIL (compile error — `RelayApi` unresolved).

- [ ] **Step 3: Implement**

Create `app/src/main/java/chat/matron/android/journal/RelayApi.kt`:

```kotlin
package chat.matron.android.journal

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/// The one shared piece of Matron infrastructure. Forks: change this constant.
object MatronRelay {
    const val BASE_URL = "https://push.matron.chat"
}

data class Rendezvous(val rid: String, val secret: String, val expiresIn: Int)

sealed interface RendezvousPollResult {
    data object Waiting : RendezvousPollResult
    data class Offered(val server: String, val code: String) : RendezvousPollResult
}

sealed class RelayError : Exception() {
    class NotFound : RelayError()      // unknown/expired rendezvous — regenerate
    class Conflict : RelayError()      // someone offered first
    class Forbidden : RelayError()     // secret mismatch (never happens for the creator)
    class RateLimited : RelayError()
    class Transport(val detail: String) : RelayError()
}

/// Talks to the shared relay's rendezvous endpoints. Unauthenticated by
/// design — the relay carries only {server, code}, never a token, and the
/// approve tap on the signed-in phone remains the only credential gate.
interface RelayRendezvousing {
    suspend fun createRendezvous(): Rendezvous
    suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult
    suspend fun offerRendezvous(rid: String, server: String, code: String)
}

class RelayApi(
    private val baseUrl: String = MatronRelay.BASE_URL,
    private val client: OkHttpClient = OkHttpClient(),
) : RelayRendezvousing {

    override suspend fun createRendezvous(): Rendezvous {
        val (status, body) = execute(createRequest(baseUrl))
        return mapCreate(status, body)
    }

    override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult {
        val (status, body) = execute(pollRequest(baseUrl, rid, secret))
        return mapPoll(status, body)
    }

    override suspend fun offerRendezvous(rid: String, server: String, code: String) {
        val (status, _) = execute(offerRequest(baseUrl, rid, server, code))
        mapOffer(status)
    }

    // Same shape as JournalApi's execute: enqueue + suspendCancellableCoroutine
    // so a cancelled coroutine cancels the in-flight call.
    private suspend fun execute(request: Request): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(RelayError.Transport(e.message ?: "network error"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use { cont.resume(it.code to (it.body?.string() ?: "")) }
                }
            })
        }

    // Pure request builders / response mappers — unit-tested without networking.
    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        fun createRequest(baseUrl: String): Request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegment("link").addPathSegment("rendezvous").build())
            .post(ByteArray(0).toRequestBody(null))
            .build()

        fun pollRequest(baseUrl: String, rid: String, secret: String): Request = Request.Builder()
            .url(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("link").addPathSegment("rendezvous").addPathSegment(rid)
                    .addQueryParameter("secret", secret).build(),
            )
            .get()
            .build()

        fun offerRequest(baseUrl: String, rid: String, server: String, code: String): Request {
            val body = buildJsonObject {
                put("server", server)
                put("code", code)
            }
            return Request.Builder()
                .url(
                    baseUrl.toHttpUrl().newBuilder()
                        .addPathSegment("link").addPathSegment("rendezvous").addPathSegment(rid)
                        .addPathSegment("offer").build(),
                )
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
        }

        fun mapCreate(status: Int, body: String): Rendezvous {
            mapError(status, success = 201)
            val obj = parseObject(body)
            return Rendezvous(
                rid = obj?.get("rid")?.jsonPrimitive?.content ?: throw RelayError.Transport("malformed relay response"),
                secret = obj["secret"]?.jsonPrimitive?.content ?: throw RelayError.Transport("malformed relay response"),
                expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: throw RelayError.Transport("malformed relay response"),
            )
        }

        fun mapPoll(status: Int, body: String): RendezvousPollResult {
            if (status == 204) return RendezvousPollResult.Waiting
            mapError(status, success = 200)
            val obj = parseObject(body)
            return RendezvousPollResult.Offered(
                server = obj?.get("server")?.jsonPrimitive?.content ?: throw RelayError.Transport("malformed relay response"),
                code = obj["code"]?.jsonPrimitive?.content ?: throw RelayError.Transport("malformed relay response"),
            )
        }

        fun mapOffer(status: Int) = mapError(status, success = 204)

        private fun mapError(status: Int, success: Int) {
            when (status) {
                success -> Unit
                404 -> throw RelayError.NotFound()
                409 -> throw RelayError.Conflict()
                403 -> throw RelayError.Forbidden()
                429 -> throw RelayError.RateLimited()
                else -> throw RelayError.Transport("HTTP $status")
            }
        }

        private fun parseObject(body: String) = runCatching {
            Json.parseToJsonElement(body).jsonObject
        }.getOrNull()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.journal.RelayApiTest'` — Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/journal/RelayApi.kt app/src/test/java/chat/matron/android/journal/RelayApiTest.kt
git commit -m "Add RelayApi for the shared rendezvous relay

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `RendezvousSignInViewModel` (signed-out Show side)

**Files:**
- Create: `app/src/main/java/chat/matron/android/viewmodels/RendezvousSignInViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/RendezvousSignInViewModelTest.kt`

**Interfaces:**
- Consumes: `RelayRendezvousing`, `Rendezvous`, `RendezvousPollResult`, `RelayError`, `RendezvousURI` (Tasks 1–2); the existing `LinkSignInViewModel` (`serverURL`, `codeInput`, `submitManual()` — untouched); `okhttp3.HttpUrl` for host extraction.
- Produces (used by Task 5):

```kotlin
class RendezvousSignInViewModel(
    private val relay: RelayRendezvousing,
    private val link: LinkSignInViewModel,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Showing(val qrPayload: String) : State
        data class Connecting(val serverHost: String) : State
        data class Error(val message: String) : State
    }
    val state: StateFlow<State>
    suspend fun start()
    fun stop()
}
```

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/chat/matron/android/viewmodels/RendezvousSignInViewModelTest.kt`. Reuse the repo's conventions: `runBlocking` + injected scope + `waitUntil` from `TestSupport.kt`. `FakeLinkClaimer` (top-level in `LinkSignInViewModelTest.kt`, same package) and `chat.matron.android.auth.FakeAuthService` are reused directly — only the relay needs a new fake.

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.auth.FakeAuthService
import chat.matron.android.journal.LinkApproval
import chat.matron.android.journal.LinkPollResult
import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.Rendezvous
import chat.matron.android.journal.RendezvousPollResult
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RID_1 = "23456789BCDFGHJKMNPQRSTVWX"
private val RID_2 = "X".repeat(26)
private val SECRET = "a".repeat(64)

class RendezvousSignInViewModelTest {

    private class FakeRelay : RelayRendezvousing {
        var createResults = mutableListOf<Result<Rendezvous>>(Result.success(Rendezvous(RID_1, SECRET, 180)))
        var createCount = 0
        var pollScript = mutableListOf<Result<RendezvousPollResult>>(Result.success(RendezvousPollResult.Waiting))
        var pollCount = 0
        var gatePoll = false
        val pollGateReached = CompletableDeferred<Unit>()
        private val pollRelease = CompletableDeferred<Unit>()
        fun releasePoll() { pollRelease.complete(Unit) }
        var offers = mutableListOf<Triple<String, String, String>>()

        override suspend fun createRendezvous(): Rendezvous {
            createCount += 1
            return (if (createResults.size > 1) createResults.removeAt(0) else createResults[0]).getOrThrow()
        }
        override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult {
            pollCount += 1
            if (gatePoll) {
                pollGateReached.complete(Unit)
                // Models a transport-delivered response the cancel can't reach
                // (same pattern as FakeLinkClaimer.linkPoll).
                withContext(NonCancellable) { pollRelease.await() }
            }
            return (if (pollScript.size > 1) pollScript.removeAt(0) else pollScript[0]).getOrThrow()
        }
        override suspend fun offerRendezvous(rid: String, server: String, code: String) {
            offers.add(Triple(rid, server, code))
        }
    }

    private fun makeVMs(relay: FakeRelay, claimer: FakeLinkClaimer, scope: CoroutineScope, auth: FakeAuthService):
        Pair<RendezvousSignInViewModel, LinkSignInViewModel> {
        val link = LinkSignInViewModel(
            auth = auth, deviceDisplayName = "Matron Android", scope = scope,
            apiFactory = { claimer }, pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        val vm = RendezvousSignInViewModel(
            relay = relay, link = link, scope = scope,
            pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        return vm to link
    }

    @Test
    fun start_showsRlinkQR_thenOfferDrivesLinkSignInToCompletion() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.pollScript = mutableListOf(
                Result.success(RendezvousPollResult.Waiting),
                Result.success(RendezvousPollResult.Offered("https://chat.example.com", "2345-6789")),
            )
            val claimer = FakeLinkClaimer()
            claimer.pollScript = mutableListOf(
                Result.success(LinkPollResult.Approved(LinkApproval("tok99", 42, 7, "dan"))),
            )
            val auth = FakeAuthService()
            val (vm, link) = makeVMs(relay, claimer, scope, auth)

            vm.start()
            assertEquals(
                RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_1"),
                vm.state.value,
            )
            waitUntil { link.state.value is LinkSignInViewModel.State.SignedIn }
            assertEquals(RendezvousSignInViewModel.State.Connecting("chat.example.com"), vm.state.value)
            assertEquals(listOf("2345-6789"), claimer.claimedCodes)
            val session = (link.state.value as LinkSignInViewModel.State.SignedIn).session
            assertEquals("dan", session.userID)
            assertEquals(listOf(session), auth.persistedSessions)
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun expiredRendezvous_silentlyRegenerates() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.createResults = mutableListOf(
                Result.success(Rendezvous(RID_1, SECRET, 180)),
                Result.success(Rendezvous(RID_2, "b".repeat(64), 180)),
            )
            relay.pollScript = mutableListOf(
                Result.failure(RelayError.NotFound()),
                Result.success(RendezvousPollResult.Waiting),
            )
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            waitUntil { relay.createCount == 2 }
            waitUntil { vm.state.value == RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_2") }
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun createFailure_isARetryableError() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.createResults = mutableListOf(Result.failure(RelayError.Transport("down")))
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            assertEquals(
                RendezvousSignInViewModel.State.Error("Couldn't reach the Matron relay — check your connection and try again."),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun transientPollFailure_keepsPolling() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.pollScript = mutableListOf(
                Result.failure(RelayError.Transport("blip")),
                Result.success(RendezvousPollResult.Waiting),
                Result.success(RendezvousPollResult.Waiting),
            )
            val (vm, _) = makeVMs(relay, FakeLinkClaimer(), scope, FakeAuthService())
            vm.start()
            waitUntil { relay.pollCount >= 3 }
            assertEquals(
                RendezvousSignInViewModel.State.Showing("matron://rlink?v=1&rid=$RID_1"),
                vm.state.value,
            )
        } finally { scope.cancel() }
        Unit
    }

    @Test
    fun stop_duringInFlightPoll_dropsTheLateOffer() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val relay = FakeRelay()
            relay.gatePoll = true
            relay.pollScript = mutableListOf(
                Result.success(RendezvousPollResult.Offered("https://chat.example.com", "2345-6789")),
            )
            val claimer = FakeLinkClaimer()
            val auth = FakeAuthService()
            val (vm, link) = makeVMs(relay, claimer, scope, auth)
            vm.start()
            relay.pollGateReached.await()
            vm.stop()
            relay.releasePoll()
            delay(50)
            assertEquals(RendezvousSignInViewModel.State.Idle, vm.state.value)
            assertEquals(LinkSignInViewModel.State.Idle, link.state.value)
            assertTrue(claimer.claimedCodes.isEmpty())
            assertTrue(auth.persistedSessions.isEmpty())
        } finally { scope.cancel() }
        Unit
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.viewmodels.RendezvousSignInViewModelTest'`
Expected: FAIL (compile error — `RendezvousSignInViewModel` unresolved).

- [ ] **Step 3: Implement**

Create `app/src/main/java/chat/matron/android/viewmodels/RendezvousSignInViewModel.kt`:

```kotlin
package chat.matron.android.viewmodels

import chat.matron.android.journal.RelayError
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.journal.RendezvousPollResult
import chat.matron.android.journal.RendezvousURI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Show-side of the reverse QR flow (spec §2): a signed-out device that
/// can't scan asks the shared relay for a rendezvous, renders it as a QR,
/// and polls. When a signed-in phone scans it and posts {server, code},
/// this VM hands both values to the existing [LinkSignInViewModel] — from
/// there the flow is byte-for-byte the shipped claim → approve → token
/// path against the user's own journal. The relay never sees a token.
class RendezvousSignInViewModel(
    private val relay: RelayRendezvousing,
    private val link: LinkSignInViewModel,
    private val scope: CoroutineScope,
    private val pollInterval: Duration = 2.seconds,
    private val errorPollInterval: Duration = 5.seconds,
) {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Showing(val qrPayload: String) : State

        /// Shown before and during the claim so the user can see WHICH
        /// server the relay pointed us at (spec §4: compromised-relay
        /// transparency). The link VM's own states drive the rest.
        data class Connecting(val serverHost: String) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    // Same stale-async discipline as LinkSignInViewModel/DeviceLinkViewModel:
    // stop() bumps the generation; every post-suspension branch re-checks it
    // before touching state.
    private var generation = 0L
    private var pollTask: Job? = null

    suspend fun start() {
        generation++
        val gen = generation
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Loading
        createAndShow(gen)
    }

    fun stop() {
        generation++
        pollTask?.cancel()
        pollTask = null
        _state.value = State.Idle
    }

    private suspend fun createAndShow(gen: Long) {
        val rendezvous = try {
            relay.createRendezvous()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Throwable) {
            if (gen != generation) return
            _state.value = State.Error("Couldn't reach the Matron relay — check your connection and try again.")
            return
        }
        if (gen != generation) return
        _state.value = State.Showing(RendezvousURI.format(rendezvous.rid))
        startPolling(rendezvous.rid, rendezvous.secret, gen)
    }

    private fun startPolling(rid: String, secret: String, gen: Long) {
        pollTask = scope.launch {
            while (isActive) {
                val result = try {
                    relay.pollRendezvous(rid, secret)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (e: RelayError.NotFound) {
                    if (gen != generation || !isActive) return@launch
                    // Rendezvous expired: silently regenerate — the mirror of
                    // the show-side's link-expiry regeneration.
                    createAndShow(gen)
                    return@launch
                } catch (e: Throwable) {
                    if (gen != generation || !isActive) return@launch
                    delay(errorPollInterval)
                    continue
                }
                if (gen != generation || !isActive) return@launch
                when (result) {
                    is RendezvousPollResult.Waiting -> delay(pollInterval)
                    is RendezvousPollResult.Offered -> {
                        _state.value = State.Connecting(
                            result.server.toHttpUrlOrNull()?.host ?: result.server,
                        )
                        link.serverURL = result.server
                        link.codeInput = result.code
                        link.submitManual()
                        return@launch
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.viewmodels.RendezvousSignInViewModelTest'` — Expected: PASS (5/5).
Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest` — Expected: full suite PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/RendezvousSignInViewModel.kt app/src/test/java/chat/matron/android/viewmodels/RendezvousSignInViewModelTest.kt
git commit -m "Add RendezvousSignInViewModel (show-side reverse QR)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `DeviceLinkViewModel.offerScanned` (signed-in Scan side)

**Files:**
- Modify: `app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/DeviceLinkViewModelTest.kt` (append)

**Interfaces:**
- Consumes: `RelayRendezvousing`, `RelayError`, `RendezvousURI`; the VM's own live session code (`Phase.Showing`).
- Produces (used by Task 6): `DeviceLinkViewModel` constructor gains `private val relay: RelayRendezvousing? = null` (after `serverURL`, before `scope` — keep existing positional call sites compiling by placing it with a default); new method `suspend fun offerScanned(payload: String)`. Outcomes land in the EXISTING `noticeMessage` flow — no new phase (the desktop's claim flips the status poll to `Claimed` exactly like a normal claim).

**Design note (why no `linkStart` here):** the screen's `DeviceLinkViewModel` already called `linkStart()` when it opened — a live session exists whichever tab is selected, and `link/start` REPLACES a starter's session, so starting another would kill the code being offered. The scan handler offers the session the VM already holds.

- [ ] **Step 1: Write failing tests**

Append to `app/src/test/java/chat/matron/android/viewmodels/DeviceLinkViewModelTest.kt` — reuse its existing top-level `FakeDeviceLinker` (scriptable via `startResults: MutableList<Result<LinkStart>>`, default code `KTNM-3VQ8`); add a relay fake and these tests:

```kotlin
private const val RLINK_RID = "23456789BCDFGHJKMNPQRSTVWX"
private const val RLINK_PAYLOAD = "matron://rlink?v=1&rid=23456789BCDFGHJKMNPQRSTVWX"

private class FakeRelayOffer : RelayRendezvousing {
    var offerResult: Result<Unit> = Result.success(Unit)
    val offers = mutableListOf<Triple<String, String, String>>()
    override suspend fun createRendezvous(): Rendezvous = error("unused")
    override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult = error("unused")
    override suspend fun offerRendezvous(rid: String, server: String, code: String) {
        offers.add(Triple(rid, server, code))
        offerResult.getOrThrow()
    }
}

// In the test class:

@Test
fun offerScanned_sendsTheLiveSessionCodeAndServer() = runBlocking {
    val scope = CoroutineScope(coroutineContext + Job())
    try {
        val fake = FakeDeviceLinker().apply {
            startResults = mutableListOf(Result.success(LinkStart("2345-6789", 120)))
        }
        val relay = FakeRelayOffer()
        val vm = DeviceLinkViewModel(
            api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
            pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        vm.start()
        waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
        vm.offerScanned(RLINK_PAYLOAD)
        assertEquals(listOf(Triple(RLINK_RID, "https://chat.example.com", "2345-6789")), relay.offers)
        assertEquals("Sent — approve the request when it appears.", vm.noticeMessage.value)
    } finally { scope.cancel() }
    Unit
}

@Test
fun offerScanned_parseFailures_neverTouchTheRelay() = runBlocking {
    val scope = CoroutineScope(coroutineContext + Job())
    try {
        val fake = FakeDeviceLinker()
        val relay = FakeRelayOffer()
        val vm = DeviceLinkViewModel(
            api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
            pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        vm.start()
        waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
        vm.offerScanned("matron://rlink?v=9&rid=$RLINK_RID")
        assertEquals("This QR code needs a newer version of Matron.", vm.noticeMessage.value)
        vm.offerScanned("https://not-matron.example.com")
        assertEquals("Not a Matron link code.", vm.noticeMessage.value)
        assertTrue(relay.offers.isEmpty())
    } finally { scope.cancel() }
    Unit
}

@Test
fun offerScanned_relayOutcomes_mapToNotices() = runBlocking {
    val cases = listOf(
        Result.failure<Unit>(RelayError.Conflict()) to "That code was already used by another device.",
        Result.failure<Unit>(RelayError.NotFound()) to "That code expired — ask the computer to show a fresh one.",
        Result.failure<Unit>(RelayError.Transport("down")) to "Couldn't reach the Matron relay — try again.",
    )
    for ((result, notice) in cases) {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeDeviceLinker()
            val relay = FakeRelayOffer().apply { offerResult = result }
            val vm = DeviceLinkViewModel(
                api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
                pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
            )
            vm.start()
            waitUntil { vm.phase.value is DeviceLinkViewModel.Phase.Showing }
            vm.offerScanned(RLINK_PAYLOAD)
            assertEquals(notice, vm.noticeMessage.value)
        } finally { scope.cancel() }
    }
    Unit
}

@Test
fun offerScanned_withoutALiveCode_asksToRetry() = runBlocking {
    val scope = CoroutineScope(coroutineContext + Job())
    try {
        val fake = FakeDeviceLinker()
        val relay = FakeRelayOffer()
        val vm = DeviceLinkViewModel(
            api = fake, serverURL = "https://chat.example.com", relay = relay, scope = scope,
            pollInterval = 1.milliseconds, errorPollInterval = 1.milliseconds,
        )
        vm.offerScanned(RLINK_PAYLOAD) // start() never called — no Showing phase yet
        assertTrue(relay.offers.isEmpty())
        assertEquals("Still fetching a link code — try scanning again in a moment.", vm.noticeMessage.value)
    } finally { scope.cancel() }
    Unit
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.viewmodels.DeviceLinkViewModelTest'`
Expected: FAIL (compile error — no `relay` parameter / `offerScanned` unresolved).

- [ ] **Step 3: Implement**

In `app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt`:

1. Constructor gains `private val relay: RelayRendezvousing? = null` (defaulted, so existing call sites compile unchanged).
2. Imports: `chat.matron.android.journal.RelayError`, `chat.matron.android.journal.RelayRendezvousing`, `chat.matron.android.journal.RendezvousURI`.
3. Add the method:

```kotlin
    /// Settings → Link a Device → Scan tab: the signed-in phone scanned a
    /// signed-out device's `matron://rlink` QR. Offers THIS VM's live link
    /// session to the relay — start() already minted a session when the
    /// screen opened, and link/start replaces a starter's session, so
    /// minting another here would kill the code being offered. After a
    /// successful offer the desktop claims within seconds and the existing
    /// status poll flips to Claimed → the normal approve card.
    suspend fun offerScanned(payload: String) {
        val relay = relay ?: return
        val gen = generation
        val rid = try {
            RendezvousURI.parse(payload)
        } catch (e: RendezvousURI.ParseError.UnsupportedVersion) {
            _noticeMessage.value = "This QR code needs a newer version of Matron."
            return
        } catch (e: RendezvousURI.ParseError) {
            _noticeMessage.value = "Not a Matron link code."
            return
        }
        val showing = _phase.value as? Phase.Showing
        if (showing == null) {
            _noticeMessage.value = "Still fetching a link code — try scanning again in a moment."
            return
        }
        try {
            relay.offerRendezvous(rid = rid, server = serverURL, code = showing.code)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: RelayError.Conflict) {
            if (gen != generation) return
            _noticeMessage.value = "That code was already used by another device."
            return
        } catch (e: RelayError.NotFound) {
            if (gen != generation) return
            _noticeMessage.value = "That code expired — ask the computer to show a fresh one."
            return
        } catch (e: Throwable) {
            if (gen != generation) return
            _noticeMessage.value = "Couldn't reach the Matron relay — try again."
            return
        }
        if (gen != generation) return
        _noticeMessage.value = "Sent — approve the request when it appears."
    }
```

(Adapt `_phase`/`_noticeMessage` to the file's actual private property names — they back the public `phase`/`noticeMessage` StateFlows.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest` — Expected: full suite PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/DeviceLinkViewModel.kt app/src/test/java/chat/matron/android/viewmodels/DeviceLinkViewModelTest.kt
git commit -m "Add offerScanned to DeviceLinkViewModel

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Sign-in screen — Scan/Show tabs

**Files:**
- Modify: `app/src/main/java/chat/matron/android/features/onboarding/SignInScreen.kt`
- Modify: `app/src/main/java/chat/matron/android/MainActivity.kt` (construct + pass the rendezvous VM)

**Interfaces:**
- Consumes: `RendezvousSignInViewModel` (Task 3), `RelayApi` + `MatronRelay` (Task 2), existing `QRCode.bitmap` (designsystem) and the Gms scanner block.
- Produces: `SignInScreen` gains parameter `rendezvousViewModel: RendezvousSignInViewModel`.

- [ ] **Step 1: Wire the VM in `MainActivity.kt`**

Where `linkVm` is built (~line 91–105), add:

```kotlin
val rendezvousVm = remember {
    RendezvousSignInViewModel(
        relay = RelayApi(client = deps.sharedClient),
        link = linkVm,
        scope = scope,
    )
}
```

and pass `rendezvousViewModel = rendezvousVm` to `SignInScreen`.

- [ ] **Step 2: Add the tab pair to `SignInScreen.kt`**

Add the parameter `rendezvousViewModel: RendezvousSignInViewModel` to the composable. Replace the current `"From another device"` block layout: keep the label, then a two-option Material3 segmented row, then the tab content. The EXISTING "Scan QR code" button block and the "Have a link code?" manual entry become the Scan tab's content, unchanged. New state + tabs:

```kotlin
var qrTab by remember { mutableStateOf(0) } // 0 = Scan, 1 = Show
val rendezvousState by rendezvousViewModel.state.collectAsStateWithLifecycle()

SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    listOf("Scan", "Show").forEachIndexed { index, label ->
        SegmentedButton(
            selected = qrTab == index,
            onClick = { qrTab = index },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
        ) { Text(label) }
    }
}

LaunchedEffect(qrTab) {
    if (qrTab == 1) rendezvousViewModel.start() else rendezvousViewModel.stop()
}
```

Show-tab content (rendered when `qrTab == 1`, replacing the scan button + manual entry):

```kotlin
when (val phase = rendezvousState) {
    is RendezvousSignInViewModel.State.Showing -> {
        val bitmap = remember(phase.qrPayload) { QRCode.bitmap(phase.qrPayload) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Sign-in QR code",
                modifier = Modifier.size(240.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan this with a phone that's signed in to Matron",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    is RendezvousSignInViewModel.State.Connecting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text("Connecting to ${phase.serverHost}…", style = MaterialTheme.typography.bodySmall)
    }
    is RendezvousSignInViewModel.State.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(phase.message, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { scope.launch { rendezvousViewModel.start() } }) { Text("Retry") }
    }
    else -> CircularProgressIndicator()
}
```

Lifecycle: extend the existing `DisposableEffect(Unit) { onDispose { linkViewModel.cancel() } }` to also call `rendezvousViewModel.stop()`. The existing link-VM-driven branches (`WaitingForApproval` full-screen spinner, `Error` display) already handle everything after the offer arrives — when `linkViewModel` state is `Error` and the Show tab is selected, add a `TextButton("Show a new code")` that calls `scope.launch { rendezvousViewModel.start() }`.

- [ ] **Step 3: Build + full unit tests**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: build + tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/chat/matron/android/features/onboarding/SignInScreen.kt app/src/main/java/chat/matron/android/MainActivity.kt
git commit -m "Sign-in screen: Scan/Show tabs with rendezvous QR

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Link a Device — Show/Scan tabs + sharpened copy

**Files:**
- Modify: `app/src/main/java/chat/matron/android/features/settings/DeviceLinkScreen.kt`
- Modify: `app/src/main/java/chat/matron/android/MainActivity.kt` (pass the relay at the `"link-device"` route)

**Interfaces:**
- Consumes: `DeviceLinkViewModel.offerScanned` + `relay` param (Task 4), `RelayApi` (Task 2), the Gms scanner block (same as `SignInScreen`).
- Produces: `DeviceLinkScreen` gains parameter `relay: RelayRendezvousing`.

- [ ] **Step 1: Add tabs + scan to `DeviceLinkScreen.kt`**

1. Signature: `fun DeviceLinkScreen(api: DeviceLinking, serverURL: String, relay: RelayRendezvousing, onBack: () -> Unit)`; forward `relay = relay` into the `remember { DeviceLinkViewModel(...) }` construction.
2. Tab state + segmented row at the top of the content (Show default). The ENTIRE existing phase-driven content (`Loading`/`Showing` QR + code + help text) becomes the Show tab. The `Claimed`/`Approved`/`Denied`/`Unsupported`/`Error` branches stay OUTSIDE the tab switch — they take over the screen exactly as today (the offer's whole point is that the status poll flips to `Claimed`). `noticeMessage` rendering also stays outside the tabs, visible from both.

```kotlin
var linkTab by remember { mutableStateOf(0) } // 0 = Show, 1 = Scan
SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    listOf("Show", "Scan").forEachIndexed { index, label ->
        SegmentedButton(
            selected = linkTab == index,
            onClick = { linkTab = index },
            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
        ) { Text(label) }
    }
}
```

3. Scan tab content (mirrors the sign-in screen's scanner invocation):

```kotlin
var scannerUnavailable by remember { mutableStateOf(false) }
Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
        "If your computer is showing a Matron QR code, scan it to sign it in as you.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Button(onClick = {
        scannerUnavailable = false
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.let { payload ->
                    scope.launch { viewModel.offerScanned(payload) }
                }
            }
            .addOnFailureListener { scannerUnavailable = true }
    }) { Text("Scan the computer's QR code") }
    if (scannerUnavailable) {
        Text("Couldn't open the scanner on this device.", style = MaterialTheme.typography.bodySmall)
    }
}
```

(Needs `val context = LocalContext.current` and `val scope = rememberCoroutineScope()` if not already in scope in this file, plus the three Gms/Barcode imports used by `SignInScreen.kt`.)

4. Sharpened copy: replace the footnote string `"Approving signs that device in with full access to your account."` with:

```kotlin
"This signs a computer into your account — only approve if it's yours, in front of you."
```

- [ ] **Step 2: Pass the relay at the call site**

In `MainActivity.kt` (~line 238–244), the `"link-device"` route call gains `relay = RelayApi(client = deps.sharedClient)`.

- [ ] **Step 3: Build + full unit tests**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: build + tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/chat/matron/android/features/settings/DeviceLinkScreen.kt app/src/main/java/chat/matron/android/MainActivity.kt
git commit -m "Link a Device: Show/Scan tabs, sharpened approve copy

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final verification

- [ ] `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest :app:assembleDebug` — green.
- [ ] Grep check: `push.matron.chat` appears exactly once in `app/src/main` (the `MatronRelay.BASE_URL` constant).
- [ ] Open a non-draft PR against `main` titled "Link rendezvous: Show tab on sign-in + Scan tab on Link a Device" — body summarizes the two flows and links the spec; ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- [ ] Note for reviewers in the PR body: until the journal/relay PR is deployed, the Show tab reports the relay-unreachable error — expected.
