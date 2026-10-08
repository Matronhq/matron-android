package chat.matron.android.integration

import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/// A raw OkHttp `WebSocket` playing the *agent* side of the wire protocol
/// against a real matron-journal server — deliberately independent of
/// [chat.matron.android.journal.JournalConnection] / `JournalSyncEngine` (those
/// are the code under test, from the client's POV). Wire shapes per the server
/// (`src/ws.js`): `convo_upsert {op,convo_id,title?,session_state?}`, `publish
/// {op,convo_id,type,payload,idem_key?}`, `stream {op,convo_id,message_ref,
/// replace_text?}`, `finalize {op,convo_id,message_ref,type?,payload}`. Agents
/// are live-only listeners (`hello {op:"hello",token,cursor:null}` — no replay).
///
/// Faithful port of the matron-apple `FakeAgent` (URLSessionWebSocketTask push
/// pump → OkHttp `WebSocketListener` accumulating frames under a lock).
class FakeAgent private constructor(
    private val ws: WebSocket,
    private val client: OkHttpClient,
    private val lock: Any,
    private val receivedFrames: MutableList<JsonObject>,
) {
    class FakeAgentException(message: String) : Exception(message)

    fun convoUpsert(id: String, title: String? = null, sessionState: String? = null) {
        sendRaw(buildJsonObject {
            put("op", "convo_upsert")
            put("convo_id", id)
            if (title != null) put("title", title)
            if (sessionState != null) put("session_state", sessionState)
        })
    }

    fun publish(convoID: String, type: String, payload: JsonObject, idemKey: String? = null) {
        sendRaw(buildJsonObject {
            put("op", "publish")
            put("convo_id", convoID)
            put("type", type)
            put("payload", payload)
            if (idemKey != null) put("idem_key", idemKey)
        })
    }

    fun stream(convoID: String, ref: String, replaceText: String) {
        sendRaw(buildJsonObject {
            put("op", "stream")
            put("convo_id", convoID)
            put("message_ref", ref)
            put("replace_text", replaceText)
        })
    }

    fun finalize(convoID: String, ref: String, body: JsonObject, type: String = "text") {
        sendRaw(buildJsonObject {
            put("op", "finalize")
            put("convo_id", convoID)
            put("message_ref", ref)
            put("type", type)
            put("payload", body)
        })
    }

    /// Every decoded frame received so far (control + journal + ephemeral).
    fun framesSnapshot(): List<JsonObject> = synchronized(lock) { receivedFrames.toList() }

    /// Polls [framesSnapshot] until a frame matches [predicate] or [timeoutMs]
    /// elapses (then throws).
    fun waitForFrame(timeoutMs: Long, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            framesSnapshot().firstOrNull(predicate)?.let { return it }
            Thread.sleep(20)
        }
        throw FakeAgentException("timed out waiting for a matching frame")
    }

    fun close() {
        ws.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun sendRaw(obj: JsonObject) {
        ws.send(obj.toString())
    }

    private fun waitForControlHello(timeoutMs: Long) {
        val frame = waitForFrame(timeoutMs) { it.stringOrNull("kind") == "control" }
        if (frame.stringOrNull("op") == "error") {
            throw FakeAgentException("agent hello rejected by server: ${frame.stringOrNull("code") ?: "unknown"}")
        }
    }

    companion object {
        /// Opens `/ws`, sends the live-only agent hello, and waits for `hello_ok`
        /// (throws on a control `error` frame, e.g. a bad/revoked token).
        fun connect(baseURL: HttpUrl, token: String, timeoutMs: Long = 5_000): FakeAgent {
            val lock = Any()
            val receivedFrames = mutableListOf<JsonObject>()
            val client = OkHttpClient()

            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    parseJsonObjectOrNull(text)?.let { synchronized(lock) { receivedFrames.add(it) } }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    parseJsonObjectOrNull(bytes.utf8())?.let { synchronized(lock) { receivedFrames.add(it) } }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // Surface as a synthetic control-error frame so a waiter on
                    // hello_ok fails fast instead of only timing out.
                    synchronized(lock) {
                        parseJsonObjectOrNull(
                            """{"kind":"control","op":"error","code":"transport"}"""
                        )?.let { receivedFrames.add(it) }
                    }
                }
            }

            // OkHttp rewrites ws:// to an http upgrade; the scheme is fine as-is.
            val wsUrl = "ws://${baseURL.host}:${baseURL.port}/ws"
            val ws = client.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            val agent = FakeAgent(ws, client, lock, receivedFrames)
            // OkHttp buffers the send until the upgrade completes.
            agent.sendRaw(buildJsonObject {
                put("op", "hello")
                put("token", token)
                put("cursor", JsonNull)
            })
            agent.waitForControlHello(timeoutMs)
            return agent
        }
    }
}

/// Convenience: `payload.body` of a decoded frame (the agent-published text
/// bodies and the client's own echoed sends).
internal fun JsonObject.frameBody(): String? = objectOrNull("payload")?.stringOrNull("body")
