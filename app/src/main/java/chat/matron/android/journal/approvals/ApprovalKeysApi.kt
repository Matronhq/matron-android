package chat.matron.android.journal.approvals

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The approval-key routes' shapes (protocol "Approval keys (step-up)"). The
// route implementations live on `JournalApi`, which implements
// [ApprovalKeysProviding].

/// One approval key of the account, as `GET /approval-keys` lists it.
data class ApprovalKeyDTO(
    val keyID: String,
    val publicKey: String = "",
    val name: String,
    /// The client device that may sign with it; `null` while it is attached
    /// to none (its device signed out).
    val deviceID: Long?,
    val enrolledVia: EnrolledVia? = EnrolledVia.PASSWORD,
    val createdAt: Instant = Instant.EPOCH,
    val revokedAt: Instant? = null,
) {
    enum class EnrolledVia(val wire: String) {
        /// With the password and a two-factor code.
        PASSWORD("password"),
        /// Approved from a device that already had a key.
        DEVICE("device"),
    }

    val fingerprint: String get() = ApprovalKeyEncoding.fingerprint(keyID)
    val isLive: Boolean get() = revokedAt == null

    companion object {
        fun fromJson(json: JsonObject?): ApprovalKeyDTO? {
            val keyID = json?.stringOrNull("key_id") ?: return null
            return ApprovalKeyDTO(
                keyID = keyID,
                publicKey = json.stringOrNull("public_key") ?: "",
                name = json.stringOrNull("name") ?: "",
                deviceID = json.longOrNull("device_id"),
                enrolledVia = json.stringOrNull("enrolled_via")?.let { v -> EnrolledVia.entries.firstOrNull { it.wire == v } },
                createdAt = json.longOrNull("created_at")?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
                revokedAt = json.longOrNull("revoked_at")?.let(Instant::ofEpochMilli),
            )
        }
    }
}

/// A new device's request to have its key added, waiting (for ten minutes)
/// for a device that already has a key to approve it.
data class ApprovalKeyRequestDTO(
    val id: String,
    val keyID: String,
    val name: String,
    val createdAt: Instant = Instant.EPOCH,
) {
    val fingerprint: String get() = ApprovalKeyEncoding.fingerprint(keyID)

    companion object {
        fun fromJson(json: JsonObject): ApprovalKeyRequestDTO? {
            val id = json.stringOrNull("id") ?: return null
            val keyID = json.stringOrNull("key_id") ?: return null
            return ApprovalKeyRequestDTO(
                id, keyID, json.stringOrNull("name") ?: "",
                json.longOrNull("created_at")?.let(Instant::ofEpochMilli) ?: Instant.EPOCH,
            )
        }
    }
}

/// `GET /approval-keys`.
data class ApprovalKeysState(
    /// False while the journal has no public URL: keys cannot be used.
    val enabled: Boolean,
    /// The host every statement must name; `null` while not enabled.
    val journal: String?,
    /// Every key of the account, revoked ones included, oldest first.
    val keys: List<ApprovalKeyDTO>,
    val requests: List<ApprovalKeyRequestDTO> = emptyList(),
) {
    /// The account has a key while one is not revoked, attached or not: from
    /// then on the guarded requests need a signature.
    val hasActiveKey: Boolean get() = keys.any { it.isLive }

    fun key(keyID: String): ApprovalKeyDTO? = keys.firstOrNull { it.keyID == keyID }

    companion object {
        fun fromJson(obj: JsonObject): ApprovalKeysState = ApprovalKeysState(
            enabled = obj.boolOrNull("enabled") ?: false,
            journal = obj.stringOrNull("journal"),
            keys = obj.arrayOrNull("keys")?.objects()?.mapNotNull(ApprovalKeyDTO::fromJson) ?: emptyList(),
            requests = obj.arrayOrNull("requests")?.objects()?.mapNotNull(ApprovalKeyRequestDTO::fromJson) ?: emptyList(),
        )
    }
}

/// A nonce from `POST /approvals/nonce`: good for one statement, for five
/// minutes, from the device it was issued to.
data class ApprovalNonce(val nonce: String, val expiresAt: Instant)

/// The answers of the key routes that are not a plain HTTP failure.
sealed class ApprovalKeyError(message: String) : Exception(message) {
    /// 409 `approval_keys_disabled`: the journal has no public URL.
    data object Disabled : ApprovalKeyError("Approval keys are switched off on this server.")
    /// 400 `bad_public_key`.
    data object BadPublicKey : ApprovalKeyError("The server refused this device's key.")
    /// 429 `enrolment_locked`: too many wrong passwords or codes; the
    /// password-and-code path is shut until [retryAt].
    data class EnrolmentLocked(val retryAt: Instant?) : ApprovalKeyError(lockedText(retryAt))
    /// 409 `two_factor_required`: two-factor must be on to enrol a key with
    /// the password.
    data object TwoFactorRequired : ApprovalKeyError(
        "Turn on two-factor authentication first: a key is set up with your password and a code.",
    )
    /// 400 `totp_required`: a recovery code was sent.
    data object TotpRequired : ApprovalKeyError("Use a code from your authenticator app. A recovery code can't set up a key.")
    /// 401 `bad_password`.
    data object BadPassword : ApprovalKeyError("That password is incorrect.")
    /// 403 `bad_code`.
    data object BadCode : ApprovalKeyError("That code is incorrect.")
    /// 409 `key_exists`: this key is, or was, enrolled. A removed key can
    /// never be added again: the device makes a new one.
    data object KeyExists : ApprovalKeyError("This key has already been added, or was removed. Create a new key.")
    /// 503 `two_factor_unavailable`.
    data object TwoFactorUnavailable : ApprovalKeyError("Two-factor authentication is unavailable on the server right now.")

    companion object {
        internal fun lockedText(retryAt: Instant?): String {
            if (retryAt == null) {
                return "Too many wrong passwords or codes. Try again later, or add this key from a device that already has one."
            }
            val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(retryAt)
            return "Too many wrong passwords or codes. Try again after $time, or add this key from a device that already has one."
        }

        /// The key routes' own refusals, or null for the generic table.
        internal fun from(status: Int, obj: JsonObject?): ApprovalKeyError? = when (status to obj?.stringOrNull("error")) {
            409 to "approval_keys_disabled" -> Disabled
            400 to "bad_public_key" -> BadPublicKey
            429 to "enrolment_locked" -> EnrolmentLocked(obj?.longOrNull("retry_at")?.let(Instant::ofEpochMilli))
            409 to "two_factor_required" -> TwoFactorRequired
            400 to "totp_required" -> TotpRequired
            // Before the generic table, which would read any 401 as signed out.
            401 to "bad_password" -> BadPassword
            403 to "bad_code" -> BadCode
            409 to "key_exists" -> KeyExists
            503 to "two_factor_unavailable" -> TwoFactorUnavailable
            else -> null
        }
    }
}

/// One sentence for each reason the journal gives for refusing a signed
/// approval (`400 approval_invalid`).
fun approvalInvalidText(reason: String): String = when (reason) {
    "mismatch" -> "The request changed while you were approving it, or this device's clock is wrong. Check it and approve again."
    "nonce", "nonce_reused" -> "The approval took too long to send. Approve again."
    "stale" -> "The approval was too old when it arrived. Approve again."
    "revoked_key" -> "This device's approval key has been removed from the account. Set up a new key in Settings."
    "wrong_device" -> "This device's approval key is not attached to this sign-in. Re-attach it in Settings, then approve again."
    "unknown_key" -> "The account does not have this device's approval key. Set up a new key in Settings."
    "signature", "bad_statement" -> "The approval signature was refused."
    else -> if (reason.isEmpty()) "The approval was refused." else "The approval was refused ($reason)."
}

/// The approval-key routes. An interface so [chat.matron.android.viewmodels.ApprovalCenter]'s
/// tests fake it. [JournalApi][chat.matron.android.journal.JournalApi] implements it.
interface ApprovalKeysProviding {
    suspend fun approvalKeys(): ApprovalKeysState
    suspend fun approvalNonce(): ApprovalNonce
    /// Enrols the calling device's key with the password and a TOTP code: the
    /// first key on an account, and the way back when no enrolled device is left.
    suspend fun enrolApprovalKey(publicKey: String, name: String, password: String, code: String): ApprovalKeyDTO
    /// Asks for the calling device's key to be added; a device that has a key
    /// approves it within ten minutes. Returns the request's id.
    suspend fun requestApprovalKey(publicKey: String, name: String): String
    suspend fun approveApprovalKeyRequest(id: String, approval: SignedApproval): ApprovalKeyDTO
    suspend fun revokeApprovalKey(keyID: String, approval: SignedApproval)
    suspend fun revokeApprovalKey(keyID: String, password: String, code: String)
    suspend fun attachApprovalKey(approval: SignedApproval): ApprovalKeyDTO
}

/// `approval` as a request body field.
fun SignedApproval.toJson(): JsonObject = buildJsonObject {
    put("statement", statement)
    put("signature", signature)
}

/// Thrown by a route that got a key back without one.
internal fun malformed(what: String) = JournalApiError.Transport("malformed $what response")
