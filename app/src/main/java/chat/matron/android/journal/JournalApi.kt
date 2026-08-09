package chat.matron.android.journal

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.buffer

data class LoginResponse(val token: String, val deviceID: Long, val userID: Long)

data class SnapshotResponse(val conversations: List<ConvoSummaryDTO>, val seq: Long)

/// The narrow slice of [JournalApi] the sync engine depends on: the WebSocket
/// URL and the cold-start / refresh snapshot fetch. Extracted as an interface
/// so engine tests can supply a scriptable fake (with request gating) instead
/// of standing up a real HTTP server — the Apple original stubs `URLProtocol`
/// globally, which has no clean Kotlin analogue. [JournalApi] implements it.
interface SnapshotSource {
    val wsUrl: String
    suspend fun snapshot(): SnapshotResponse
}

/// Server-side conversation summary (shape of /snapshot rows). Also the input to
/// store upserts.
data class ConvoSummaryDTO(
    val id: String,
    val title: String,
    val sessionState: String,
    val lastSeq: Long,
    val snippet: String,
    val createdAt: Long,
    /// Timestamp (ms) of the conversation's newest event, when the server
    /// includes it (`last_ts`). `null` on older servers.
    val lastTS: Long? = null,
    /// Parent conversation id for a subagent child, else `null`.
    val parentConvoID: String? = null,
)

/// One row of `GET /devices`. Timestamps are epoch ms; `lastSeenAt` is null for
/// a device that has never connected.
data class DeviceDTO(
    val id: Long,
    val kind: String,   // "client" | "agent"
    val name: String,
    val createdAt: Long,
    val cursor: Long,
    /// User's head seq minus this device's cursor. 0 = up to date.
    val lag: Long,
    val lastSeenAt: Long?,
    val isSelf: Boolean,
    /// Whether the device has a live journal connection right now. Defaults
    /// false when the server predates the flag.
    val connected: Boolean = false,
)

/// The user's answer to an agent-chat consent card. Mirrors the `decision`
/// field of `POST /agent-chat/answer`.
enum class AgentChatDecision(val wire: String) {
    APPROVE("approve"),
    DENY("deny"),
}

/// One row of `GET /agent-chat/pending` — an agent's request to chat that is
/// parked waiting on this user. The durable form of the consent card, for asks
/// that arrived while no client was connected.
///
/// `roomID` + `targetDeviceID` are the answer key; the two names are the
/// devices', already sanitised server-side and null when a device has since
/// been revoked.
data class AgentChatPendingDTO(
    val roomID: String,
    val targetDeviceID: Long,
    val initiatorDeviceID: Long,
    val initiatorName: String?,
    val targetName: String?,
    val topic: String?,
    val justification: String?,
    val roomTitle: String,
    val createdAt: Long,
) {
    /// Unique per parked row: the server's own primary key for one
    /// (`convo_agents.convo_id`, `agent_device_id`).
    val id: String get() = "$roomID/$targetDeviceID"

    /// Who to name on the card. Falls back to the device id rather than going
    /// blank when the requesting device has been revoked mid-ask.
    val requesterLabel: String
        get() = initiatorName?.trim()?.takeIf { it.isNotEmpty() } ?: "Device $initiatorDeviceID"

    /// Same fallback for the far end. Only meaningful on an invite: a join
    /// self-targets, so this would name the joiner twice.
    val targetLabel: String
        get() = targetName?.trim()?.takeIf { it.isNotEmpty() } ?: "Device $targetDeviceID"

    /// One line stating what is being asked. A join self-targets (the
    /// requester IS the target), which is what tells the two apart without a
    /// separate field.
    val headline: String
        get() = if (initiatorDeviceID == targetDeviceID) {
            "$requesterLabel wants to join a chat."
        } else {
            "$requesterLabel wants to start a chat with $targetLabel."
        }
}

/// One row of `GET /agent-chat/allowances` — a directed pair the user chose to
/// trust with "always allow", which skips the consent card entirely from then
/// on. Directed: A→B says nothing about B→A.
data class AgentChatAllowanceDTO(
    val fromDeviceID: Long,
    val targetDeviceID: Long,
    val fromName: String?,
    val targetName: String?,
    val createdAt: Long,
) {
    val id: String get() = "$fromDeviceID->$targetDeviceID"
    val fromLabel: String get() = label(fromName, fromDeviceID)
    val targetLabel: String get() = label(targetName, targetDeviceID)

    private fun label(name: String?, id: Long): String =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: "Device $id"
}

/// `POST /pair/preview` — who is asking to join, shown before approve.
data class PairPreview(val requesterIP: String, val expiresIn: Int)

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

/// Errors surfaced by the REST client. Exceptions so they throw through the
/// suspend surface the way the Swift `throws` do. The messages are
/// human-readable because they surface verbatim in UI banners via
/// `error.message` (chat error overlay, composer send error, sign-in form) —
/// without them a rate-limit on a flaky link rendered as an enum dump
/// (Dan's 2026-07-30 screenshot on iOS).
sealed class JournalApiError(message: String) : Exception(message) {
    data object BadCredentials : JournalApiError("Invalid credentials.")
    data class LockedOut(val retryAfterSeconds: Int) :
        JournalApiError("Too many attempts — try again in ${retryAfterSeconds}s.")
    data object RateLimited : JournalApiError("The server is busy — trying again shortly.")
    data object Unauthenticated : JournalApiError("Signed out by the server — please sign in again.")
    data object Forbidden : JournalApiError("The server refused the request.")
    data object NotFound : JournalApiError("Not found on the server.")
    /// 409 — exactly-once semantics: `pair/approve` (already approved),
    /// `link/claim` (code already claimed), `link/approve` (nothing to
    /// approve yet, or already resolved).
    data object Conflict : JournalApiError("Already handled — possibly on another device.")
    data class Http(val status: Int, val serverMessage: String) :
        JournalApiError(serverMessage.ifEmpty { "Server error (HTTP $status)." })
    data class Transport(val detail: String) :
        JournalApiError(if (detail.isEmpty()) "Couldn't reach the server." else "Couldn't reach the server — $detail")
}

/// Thin HTTP surface of the journal server: login, snapshot, pagination, media,
/// devices, and pairing. Suspend functions wrap OkHttp's async enqueue.
class JournalApi(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient = OkHttpClient(),
    token: String? = null,
) : SnapshotSource {
    constructor(baseUrl: String, client: OkHttpClient = OkHttpClient(), token: String? = null)
        : this(baseUrl.toHttpUrl(), client, token)

    @Volatile
    private var token: String? = token

    fun setToken(token: String?) {
        this.token = token
    }

    enum class PushEnvironment(val wire: String) { SANDBOX("sandbox"), PROD("prod") }

    /// The server URL's own path, normalized so endpoint paths can be appended:
    /// "" or "/" → "", "/prefix/" → "/prefix". Appending (not replacing) keeps
    /// a server hosted under a subpath working.
    private val basePath: String = baseUrl.encodedPath.trimEnd('/')

    /// The server's base URL. Used by media-URL construction (the timeline
    /// mapper builds `serverURL/media/<blobRef>`) and blob-ref extraction (the
    /// media service). Apple exposes the same `serverURL`.
    val serverURL: HttpUrl get() = baseUrl

    /// The WebSocket URL for `/ws`, preserving any path prefix.
    override val wsUrl: String
        get() {
            val scheme = if (baseUrl.scheme == "http") "ws" else "wss"
            val portPart = if (baseUrl.port == HttpUrl.defaultPort(baseUrl.scheme)) "" else ":${baseUrl.port}"
            return "$scheme://${baseUrl.host}$portPart$basePath/ws"
        }

    suspend fun login(username: String, password: String, deviceName: String): LoginResponse {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            put("device_name", deviceName)
        }
        val obj = request(path = "/login", method = "POST", jsonBody = body, authenticated = false)
        val token = obj.stringOrNull("token")
        val deviceID = obj.longOrNull("device_id")
        val userID = obj.longOrNull("user_id")
        if (token == null || deviceID == null || userID == null) {
            throw JournalApiError.Transport("malformed login response")
        }
        this.token = token
        return LoginResponse(token, deviceID, userID)
    }

    override suspend fun snapshot(): SnapshotResponse {
        val obj = request(path = "/snapshot")
        val conversations = (obj.arrayOrNull("conversations")?.objects() ?: emptyList()).mapNotNull { c ->
            val id = c.stringOrNull("id") ?: return@mapNotNull null
            ConvoSummaryDTO(
                id = id,
                title = c.stringOrNull("title") ?: "",
                sessionState = c.stringOrNull("session_state") ?: SessionState.RUNNING,
                lastSeq = c.longOrNull("last_seq") ?: 0,
                snippet = c.stringOrNull("snippet") ?: "",
                createdAt = c.longOrNull("created_at") ?: 0,
                lastTS = c.longOrNull("last_ts"),
                parentConvoID = c.stringOrNull("parent_convo_id"),
            )
        }
        return SnapshotResponse(conversations, obj.longOrNull("seq") ?: 0)
    }

    suspend fun messages(convoID: String, beforeSeq: Long?, limit: Int): List<JournalEvent> {
        val query = buildList {
            add("limit" to limit.toString())
            if (beforeSeq != null) add("before_seq" to beforeSeq.toString())
        }
        val obj = request(path = "/convo/${pathSegment(convoID)}/messages", query = query)
        return (obj.arrayOrNull("events")?.objects() ?: emptyList()).mapNotNull(JournalEvent::fromFrame)
    }

    suspend fun mediaData(blobRef: String): ByteArray {
        val (status, data) = raw(path = "/media/${pathSegment(blobRef)}", method = "GET")
        if (status != 200) throw error(status, data)
        return data
    }

    /// Uploads raw media bytes and returns the server's `media_id`, which
    /// callers pass back as the `blob_ref` on a subsequent media `send`.
    ///
    /// [progress] (optional) receives the fraction of the request body sent
    /// (0…1), delivered on an OkHttp writer thread — the whole point on a slow
    /// uplink, where a multi-MB screenshot otherwise looks frozen. The write/
    /// read timeouts are raised well past the client default for the same
    /// reason: a legitimate slow upload must not die mid-body.
    suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)? = null): String {
        val body = data.toRequestBody((contentType.ifEmpty { "application/octet-stream" }).toMediaTypeOrNull())
        val counted = if (progress != null) ProgressRequestBody(body, data.size.toLong(), progress) else body
        val builder = Request.Builder().url(buildUrl("/media", emptyList()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        builder.method("POST", counted)
        val uploadClient = client.newBuilder()
            .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val (status, respData) = execute(uploadClient.newCall(builder.build()))
        if (status != 200) throw error(status, respData)
        val obj = parseJsonObjectOrNull(String(respData, Charsets.UTF_8))
        val mediaID = obj?.stringOrNull("media_id")
            ?: throw JournalApiError.Transport("malformed media upload response")
        return mediaID
    }

    /// Counts bytes as OkHttp writes the request body, reporting the running
    /// fraction. `writeTo` can run more than once (OkHttp retries); the
    /// counter is per-invocation so a retry restarts cleanly at 0.
    private class ProgressRequestBody(
        private val delegate: okhttp3.RequestBody,
        private val totalBytes: Long,
        private val onProgress: (Double) -> Unit,
    ) : okhttp3.RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = totalBytes
        override fun writeTo(sink: okio.BufferedSink) {
            var written = 0L
            val counting = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    if (totalBytes > 0) onProgress(written.toDouble() / totalBytes)
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    /// The signed-in user's device roster. Order is not guaranteed — callers
    /// sort. Pull-based: refresh on screen enter and after mutations.
    suspend fun devices(): List<DeviceDTO> {
        val obj = request(path = "/devices")
        return (obj.arrayOrNull("devices")?.objects() ?: emptyList()).mapNotNull { d ->
            val id = d.longOrNull("device_id") ?: return@mapNotNull null
            DeviceDTO(
                id = id,
                kind = d.stringOrNull("kind") ?: "client",
                name = d.stringOrNull("name") ?: "",
                createdAt = d.longOrNull("created_at") ?: 0,
                cursor = d.longOrNull("cursor") ?: 0,
                lag = d.longOrNull("lag") ?: 0,
                lastSeenAt = d.longOrNull("last_seen_at"),
                isSelf = d.boolOrNull("is_self") ?: false,
                connected = d.boolOrNull("connected") ?: false,
            )
        }
    }

    /// Immediate, permanent revocation. 404 (`NotFound`) means already revoked
    /// elsewhere — callers treat it as success.
    suspend fun revokeDevice(id: Long) {
        request(path = "/devices/$id/revoke", method = "POST", jsonBody = buildJsonObject { })
    }

    // MARK: Agent chat consent

    /// Asks parked waiting on this user, across every room. The durable
    /// counterpart to the live consent card — an ask minted while no client was
    /// connected is only ever visible here.
    suspend fun agentChatPending(): List<AgentChatPendingDTO> {
        val obj = request(path = "/agent-chat/pending")
        return (obj.arrayOrNull("pending")?.objects() ?: emptyList()).mapNotNull { p ->
            val roomID = p.stringOrNull("convo_id") ?: return@mapNotNull null
            val target = p.longOrNull("agent_device_id") ?: return@mapNotNull null
            val initiator = p.longOrNull("initiator_device_id") ?: return@mapNotNull null
            AgentChatPendingDTO(
                roomID = roomID,
                targetDeviceID = target,
                initiatorDeviceID = initiator,
                initiatorName = p.stringOrNull("initiator_name"),
                targetName = p.stringOrNull("agent_name"),
                topic = nonEmpty(p.stringOrNull("topic")),
                justification = nonEmpty(p.stringOrNull("justification")),
                roomTitle = p.stringOrNull("title") ?: "",
                createdAt = p.longOrNull("created_at") ?: 0,
            )
        }
    }

    /// Answers one parked ask. The ONLY path that resolves a consent card — a
    /// `prompt_reply` into the room never touches the parked row.
    ///
    /// `alwaysAllow` on an approval records a standing allowance for the
    /// directed pair, so future asks between those two agents skip the card. It
    /// is also the only way to create one; [revokeAgentChatAllowance] is the
    /// way back.
    ///
    /// Returns the server's `delivered` flag: whether the approved invite
    /// reached the target's socket right now, or is still owed to it. Throws
    /// `Conflict` if the row is no longer awaiting an answer (already answered
    /// here or elsewhere, or expired) and `NotFound` if the room isn't this
    /// user's.
    suspend fun answerAgentChat(
        roomID: String,
        targetDeviceID: Long,
        decision: AgentChatDecision,
        alwaysAllow: Boolean = false,
    ): Boolean {
        val obj = request(
            path = "/agent-chat/answer", method = "POST",
            jsonBody = buildJsonObject {
                put("room_id", roomID)
                put("target_device_id", targetDeviceID)
                put("decision", decision.wire)
                // Sent only when true: the server treats `always_allow` as
                // strictly `=== true`, and a denial has no allowance to record
                // either way.
                if (alwaysAllow && decision == AgentChatDecision.APPROVE) put("always_allow", true)
            },
        )
        return obj.boolOrNull("delivered") ?: false
    }

    /// Directed pairs the user has granted "always allow".
    suspend fun agentChatAllowances(): List<AgentChatAllowanceDTO> {
        val obj = request(path = "/agent-chat/allowances")
        return (obj.arrayOrNull("allowances")?.objects() ?: emptyList()).mapNotNull { a ->
            val from = a.longOrNull("from_device_id") ?: return@mapNotNull null
            val target = a.longOrNull("target_device_id") ?: return@mapNotNull null
            AgentChatAllowanceDTO(
                fromDeviceID = from,
                targetDeviceID = target,
                fromName = a.stringOrNull("from_name"),
                targetName = a.stringOrNull("target_name"),
                createdAt = a.longOrNull("created_at") ?: 0,
            )
        }
    }

    /// Withdraws a standing allowance, so that pair has to ask again.
    /// Idempotent server-side — revoking one that is already gone succeeds.
    suspend fun revokeAgentChatAllowance(fromDeviceID: Long, targetDeviceID: Long) {
        request(
            path = "/agent-chat/allowances/revoke", method = "POST",
            jsonBody = buildJsonObject {
                put("from_device_id", fromDeviceID)
                put("target_device_id", targetDeviceID)
            },
        )
    }

    /// The journal defaults an absent topic/justification to `""` rather than
    /// omitting the key, so "absent" and "empty" arrive identically.
    private fun nonEmpty(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

    /// Previews a pairing code before approval. 404 = unknown/expired/approved.
    suspend fun pairPreview(code: String): PairPreview {
        val obj = request(path = "/pair/preview", method = "POST",
            jsonBody = buildJsonObject { put("pair_code", code) })
        val ip = obj.stringOrNull("requester_ip")
        val expiresIn = obj.intOrNull("expires_in")
        if (ip == null || expiresIn == null) {
            throw JournalApiError.Transport("malformed pair preview response")
        }
        return PairPreview(ip, expiresIn)
    }

    /// Approves a pairing code. Exactly-once: `Conflict` = already approved.
    suspend fun pairApprove(code: String, agentName: String) {
        request(path = "/pair/approve", method = "POST", jsonBody = buildJsonObject {
            put("pair_code", code)
            put("agent_name", agentName)
        })
    }

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

    /// Registers this device for pushes (client devices only).
    suspend fun registerPush(tokenHex: String, environment: PushEnvironment) {
        request(path = "/push/register", method = "POST", jsonBody = buildJsonObject {
            put("apns_token", tokenHex)
            put("environment", environment.wire)
        })
    }

    /// Clears this device's push registration (apns_token: null).
    suspend fun unregisterPush() {
        request(path = "/push/register", method = "POST", jsonBody = buildJsonObject {
            put("apns_token", kotlinx.serialization.json.JsonNull)
        })
    }

    // MARK: Internals

    /// Escapes one path segment: everything but unreserved characters is
    /// percent-encoded, including "/".
    private fun pathSegment(raw: String): String {
        val sb = StringBuilder()
        for (byte in raw.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(code))
            }
        }
        return sb.toString()
    }

    private fun buildUrl(path: String, query: List<Pair<String, String>>): HttpUrl {
        val builder = baseUrl.newBuilder().encodedPath(basePath + path)
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        jsonBody: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): JsonObject {
        val (status, data) = raw(path, method, jsonBody, query, authenticated)
        if (status != 200) throw error(status, data)
        return parseJsonObjectOrNull(String(data, Charsets.UTF_8))
            ?: throw JournalApiError.Transport("non-JSON response for $path")
    }

    private suspend fun raw(
        path: String,
        method: String,
        jsonBody: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
        rawBody: ByteArray? = null,
        rawContentType: String? = null,
    ): Pair<Int, ByteArray> {
        val builder = Request.Builder().url(buildUrl(path, query))
        if (authenticated) token?.let { builder.header("Authorization", "Bearer $it") }
        // A raw body (media upload) sends bytes verbatim under its own content
        // type; the JSON body path is mutually exclusive with it.
        val body = when {
            rawBody != null ->
                rawBody.toRequestBody((rawContentType ?: "application/octet-stream").toMediaTypeOrNull())
            jsonBody != null ->
                jsonBody.toString().toRequestBody("application/json".toMediaTypeOrNull())
            else -> null
        }
        builder.method(method, body)
        return execute(client.newCall(builder.build()))
    }

    private suspend fun execute(call: Call): Pair<Int, ByteArray> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(JournalApiError.Transport(e.message ?: "transport error"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val bytes = it.body?.bytes() ?: ByteArray(0)
                        cont.resumeWith(Result.success(it.code to bytes))
                    }
                }
            })
        }

    private fun error(status: Int, data: ByteArray): JournalApiError {
        val obj = parseJsonObjectOrNull(String(data, Charsets.UTF_8))
        val code = obj?.stringOrNull("error")
        return when {
            status == 403 && code == "bad_credentials" -> JournalApiError.BadCredentials
            status == 429 && code == "locked_out" ->
                JournalApiError.LockedOut(obj?.intOrNull("retry_after") ?: 60)
            status == 429 -> JournalApiError.RateLimited
            status == 401 -> JournalApiError.Unauthenticated
            status == 403 -> JournalApiError.Forbidden
            status == 404 -> JournalApiError.NotFound
            status == 409 -> JournalApiError.Conflict
            else -> JournalApiError.Http(status, obj?.stringOrNull("message") ?: code ?: "")
        }
    }
}
