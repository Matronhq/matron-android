package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyEncoding
import chat.matron.android.journal.approvals.ApprovalKeyError
import chat.matron.android.journal.approvals.ApprovalKeyRequestDTO
import chat.matron.android.journal.approvals.ApprovalKeysProviding
import chat.matron.android.journal.approvals.ApprovalKeysState
import chat.matron.android.journal.approvals.ApprovalNonce
import chat.matron.android.journal.approvals.SignedApproval
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// A journal's approval-key routes, in memory, checking what the real one
/// checks on every signed request: the statement names this journal and
/// user, the key is the account's, live and attached to the caller, the
/// subject is what the route expects, the nonce was issued and is spent
/// once, and the ES256 signature verifies over the statement's bytes.
class FakeApprovalJournal(
    val journal: String = "journal.example",
    val user: String = "alice",
    var callerDevice: Long = 7,
) : ApprovalKeysProviding {
    var enabled = true
    val keys = mutableListOf<ApprovalKeyDTO>()
    val requests = mutableListOf<ApprovalKeyRequestDTO>()
    private val issued = mutableSetOf<String>()
    private var nonceCount = 0
    /// Every approval the fake accepted, in order.
    val accepted = mutableListOf<JsonObject>()
    var refuseEnrolWith: Throwable? = null
    var keysReadError: Throwable? = null

    override suspend fun approvalKeys(): ApprovalKeysState {
        keysReadError?.let { throw it }
        return ApprovalKeysState(enabled, if (enabled) journal else null, keys.toList(), requests.toList())
    }

    override suspend fun approvalNonce(): ApprovalNonce {
        val nonce = "nonce-${++nonceCount}"
        issued += nonce
        return ApprovalNonce(nonce, Instant.now().plusSeconds(300))
    }

    override suspend fun enrolApprovalKey(publicKey: String, name: String, password: String, code: String): ApprovalKeyDTO {
        refuseEnrolWith?.let { throw it }
        if (password != "secret" || code != "123456") throw ApprovalKeyError.BadPassword
        val keyID = ApprovalKeyEncoding.keyID(ApprovalKeyEncoding.data(publicKey)!!)
        if (keys.any { it.keyID == keyID }) throw ApprovalKeyError.KeyExists
        val key = ApprovalKeyDTO(keyID, publicKey, name, callerDevice).also { keys += it }
        if (loseEnrolAnswer) { loseEnrolAnswer = false; throw JournalApiError.Transport("connection lost") }
        return key
    }
    /// The next enrolment is made, but its answer never arrives.
    var loseEnrolAnswer = false

    override suspend fun requestApprovalKey(publicKey: String, name: String): String {
        val keyID = ApprovalKeyEncoding.keyID(ApprovalKeyEncoding.data(publicKey)!!)
        if (keys.any { it.keyID == keyID }) throw ApprovalKeyError.KeyExists
        val id = "kr_${++requestCount}"
        requests += ApprovalKeyRequestDTO(id, keyID, name)
        pendingPublicKeys[id] = publicKey
        return id
    }
    private val pendingPublicKeys = mutableMapOf<String, String>()
    private var requestCount = 0

    override suspend fun approveApprovalKeyRequest(id: String, approval: SignedApproval): ApprovalKeyDTO {
        val request = requests.firstOrNull { it.id == id } ?: throw JournalApiError.NotFound
        check(approval, "account", mapOf("op" to "enrol_key", "key_id" to request.keyID))
        requests.remove(request)
        return ApprovalKeyDTO(request.keyID, pendingPublicKeys.remove(id) ?: "", request.name, 99, ApprovalKeyDTO.EnrolledVia.DEVICE)
            .also { keys += it }
    }

    override suspend fun revokeApprovalKey(keyID: String, approval: SignedApproval) {
        check(approval, "account", mapOf("op" to "revoke_key", "key_id" to keyID))
        revoke(keyID)
    }

    override suspend fun revokeApprovalKey(keyID: String, password: String, code: String) {
        if (password != "secret" || code != "123456") throw ApprovalKeyError.BadPassword
        revoke(keyID)
    }

    private fun revoke(keyID: String) {
        val i = keys.indexOfFirst { it.keyID == keyID && it.isLive }
        if (i < 0) throw JournalApiError.NotFound
        keys[i] = keys[i].copy(revokedAt = Instant.now())
    }

    override suspend fun attachApprovalKey(approval: SignedApproval): ApprovalKeyDTO {
        val signer = parseJsonObjectOrNull(approval.statement)?.stringOrNull("key_id") ?: ""
        check(approval, "account", mapOf("op" to "attach_key", "key_id" to signer), anyDevice = true)
        val i = keys.indexOfFirst { it.keyID == signer }
        keys[i] = keys[i].copy(deviceID = callerDevice)
        return keys[i]
    }

    /// The journal's own check (approval-keys.js `check`), for the fields
    /// the routes here use. Throws `ApprovalInvalid` as the journal answers.
    fun check(approval: SignedApproval, type: String, subject: Map<String, String>, anyDevice: Boolean = false): JsonObject {
        val st = parseJsonObjectOrNull(approval.statement) ?: throw JournalApiError.ApprovalInvalid("bad_statement")
        if (st.stringOrNull("journal") != journal || st.stringOrNull("user") != user) throw JournalApiError.ApprovalInvalid("mismatch")
        val key = keys.firstOrNull { it.keyID == st.stringOrNull("key_id") } ?: throw JournalApiError.ApprovalInvalid("unknown_key")
        if (!key.isLive) throw JournalApiError.ApprovalInvalid("revoked_key")
        if (!anyDevice && key.deviceID != callerDevice) throw JournalApiError.ApprovalInvalid("wrong_device")
        val signedSubject = st.objectOrNull("subject")?.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
        if (st.stringOrNull("type") != type || signedSubject != subject) throw JournalApiError.ApprovalInvalid("mismatch")
        if (!verifies(key.publicKey, approval)) throw JournalApiError.ApprovalInvalid("signature")
        if (!issued.remove(st.stringOrNull("nonce"))) throw JournalApiError.ApprovalInvalid("nonce")
        accepted += st
        return st
    }

    companion object {
        fun verifies(publicKeyB64: String, approval: SignedApproval): Boolean {
            val raw = ApprovalKeyEncoding.data(publicKeyB64) ?: return false
            if (raw.size != 65) return false
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
            val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            return runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(approval.statement.toByteArray(Charsets.UTF_8))
                    verify(ApprovalKeyEncoding.data(approval.signature))
                }
            }.getOrDefault(false)
        }
    }
}
