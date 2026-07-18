package chat.matron.android.journal

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
                rid = obj.stringField("rid") ?: throw RelayError.Transport("malformed relay response"),
                secret = obj.stringField("secret") ?: throw RelayError.Transport("malformed relay response"),
                expiresIn = obj.intField("expires_in") ?: throw RelayError.Transport("malformed relay response"),
            )
        }

        fun mapPoll(status: Int, body: String): RendezvousPollResult {
            if (status == 204) return RendezvousPollResult.Waiting
            mapError(status, success = 200)
            val obj = parseObject(body)
            return RendezvousPollResult.Offered(
                server = obj.stringField("server") ?: throw RelayError.Transport("malformed relay response"),
                code = obj.stringField("code") ?: throw RelayError.Transport("malformed relay response"),
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

        // Field access must never throw: a field present with the wrong JSON
        // shape (e.g. {"rid": {}}) would otherwise crash via jsonPrimitive's
        // `error()` instead of degrading to RelayError.Transport.
        private fun JsonObject?.stringField(key: String): String? =
            runCatching { this?.get(key)?.jsonPrimitive?.content }.getOrNull()

        private fun JsonObject?.intField(key: String): Int? =
            runCatching { this?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()
    }
}
