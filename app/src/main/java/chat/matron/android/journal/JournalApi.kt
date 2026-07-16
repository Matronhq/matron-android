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

/// `POST /pair/preview` — who is asking to join, shown before approve.
data class PairPreview(val requesterIP: String, val expiresIn: Int)

/// Errors surfaced by the REST client. Exceptions so they throw through the
/// suspend surface the way the Swift `throws` do.
sealed class JournalApiError(message: String? = null) : Exception(message) {
    data object BadCredentials : JournalApiError()
    data class LockedOut(val retryAfterSeconds: Int) : JournalApiError()
    data object RateLimited : JournalApiError()
    data object Unauthenticated : JournalApiError()
    data object Forbidden : JournalApiError()
    data object NotFound : JournalApiError()
    /// 409 — currently only `POST /pair/approve`: the code was already approved.
    data object Conflict : JournalApiError()
    data class Http(val status: Int, val serverMessage: String) : JournalApiError()
    data class Transport(val detail: String) : JournalApiError()
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
                sessionState = c.stringOrNull("session_state") ?: "running",
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
    suspend fun uploadMedia(data: ByteArray, contentType: String): String {
        val (status, respData) = raw(
            path = "/media", method = "POST", rawBody = data, rawContentType = contentType,
        )
        if (status != 200) throw error(status, respData)
        val obj = parseJsonObjectOrNull(String(respData, Charsets.UTF_8))
        val mediaID = obj?.stringOrNull("media_id")
            ?: throw JournalApiError.Transport("malformed media upload response")
        return mediaID
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
