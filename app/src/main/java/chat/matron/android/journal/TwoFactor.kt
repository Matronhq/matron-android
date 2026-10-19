package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// matron-journal two-factor sign-in (authenticator-app codes). The routes live
// on [JournalApi] (its request plumbing is private); the decoders are pure so
// tests pin the shapes without a server.

/// `POST /login/2fa` — a password sign-in finished with a code. The token is
/// stored exactly as `/login`'s. [recoveryCodesLeft] is `null` when the
/// journal leaves it out.
data class TwoFactorLoginResponse(
    val login: LoginResponse,
    val usedRecoveryCode: Boolean,
    val recoveryCodesLeft: Int?,
)

/// `POST /login/2fa/setup` (and the signed-in `/2fa/setup`): the new secret
/// for an authenticator app, as a key to type and as an `otpauth://` URI for
/// the QR code.
data class TwoFactorSetup(
    val secret: String,
    val otpauthURI: String,
    val issuer: String?,
    val account: String?,
)

/// `POST /login/2fa/enable` — enrolment at sign-in done: a token, and the
/// recovery codes, which the journal shows this once.
data class TwoFactorEnrolment(
    val login: LoginResponse,
    val recoveryCodes: List<String>,
)

/// `GET /2fa` — the signed-in user's two-factor state. [available] is false
/// when the journal has no token key, so nobody can turn it on.
data class TwoFactorStatus(
    val enabled: Boolean,
    val recoveryCodesLeft: Int?,
    val required: Boolean,
    val available: Boolean,
)

/// `GET /2fa`. A journal predating two-factor 404s it
/// ([JournalApiError.NotFound]), which hides Settings → Security. [JournalApi]
/// implements it; tests fake it.
interface TwoFactorProviding {
    suspend fun twoFactorStatus(): TwoFactorStatus
}

object TwoFactorDecoding {
    fun login(obj: JsonObject): LoginResponse? {
        val token = obj.stringOrNull("token") ?: return null
        val deviceID = obj.longOrNull("device_id") ?: return null
        val userID = obj.longOrNull("user_id") ?: return null
        return LoginResponse(token, deviceID, userID)
    }

    fun verified(obj: JsonObject): TwoFactorLoginResponse? {
        val login = login(obj) ?: return null
        return TwoFactorLoginResponse(
            login = login,
            usedRecoveryCode = obj.boolOrNull("used_recovery_code") ?: false,
            recoveryCodesLeft = obj.intOrNull("recovery_codes_left"),
        )
    }

    fun setup(obj: JsonObject): TwoFactorSetup? {
        val secret = obj.stringOrNull("secret")?.takeIf { it.isNotEmpty() } ?: return null
        val uri = obj.stringOrNull("otpauth_uri")?.takeIf { it.startsWith("otpauth://") } ?: return null
        return TwoFactorSetup(secret, uri, obj.stringOrNull("issuer"), obj.stringOrNull("account"))
    }

    fun enrolment(obj: JsonObject): TwoFactorEnrolment? {
        val login = login(obj) ?: return null
        val codes = obj.arrayOrNull("recovery_codes")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }
            ?: return null
        if (codes.isEmpty()) return null
        return TwoFactorEnrolment(login, codes)
    }

    fun status(obj: JsonObject): TwoFactorStatus? {
        val enabled = obj.boolOrNull("enabled") ?: return null
        return TwoFactorStatus(
            enabled = enabled,
            recoveryCodesLeft = obj.intOrNull("recovery_codes_left"),
            required = obj.boolOrNull("required") ?: false,
            available = obj.boolOrNull("available") ?: true,
        )
    }

    /// The setup key as people type it: groups of four.
    fun groupedSecret(secret: String): String = secret.chunked(4).joinToString(" ")
}
