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
/// [enabledAtMillis] is when it was turned on, if the journal says.
data class TwoFactorStatus(
    val enabled: Boolean,
    val recoveryCodesLeft: Int?,
    val required: Boolean,
    val available: Boolean,
    val enabledAtMillis: Long? = null,
)

/// `POST /2fa/enable` — the recovery codes, shown this once, and how many
/// other devices `sign_out_others` signed out.
data class TwoFactorEnabled(val recoveryCodes: List<String>, val signedOut: Int)

/// Settings → Security's routes (the signed-in `/2fa` ones). A journal
/// predating two-factor 404s `GET /2fa` ([JournalApiError.NotFound]), which
/// hides the section. [JournalApi] implements it; tests fake it.
interface TwoFactorProviding {
    suspend fun twoFactorStatus(): TwoFactorStatus
    suspend fun twoFactorSetup(password: String, code: String?): TwoFactorSetup
    suspend fun twoFactorEnable(code: String, signOutOthers: Boolean): TwoFactorEnabled
    suspend fun twoFactorDisable(password: String, code: String)
    suspend fun twoFactorRecoveryCodes(password: String, code: String): List<String>
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
        return TwoFactorEnrolment(login, recoveryCodes(obj) ?: return null)
    }

    fun enabled(obj: JsonObject): TwoFactorEnabled? {
        val codes = recoveryCodes(obj) ?: return null
        return TwoFactorEnabled(codes, obj.intOrNull("signed_out") ?: 0)
    }

    /// `recovery_codes`: a non-empty list of strings, else `null`.
    fun recoveryCodes(obj: JsonObject): List<String>? = obj.arrayOrNull("recovery_codes")
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotEmpty() } }
        ?.takeIf { it.isNotEmpty() }

    fun status(obj: JsonObject): TwoFactorStatus? {
        val enabled = obj.boolOrNull("enabled") ?: return null
        return TwoFactorStatus(
            enabled = enabled,
            recoveryCodesLeft = obj.intOrNull("recovery_codes_left"),
            required = obj.boolOrNull("required") ?: false,
            available = obj.boolOrNull("available") ?: true,
            enabledAtMillis = obj.longOrNull("enabled_at"),
        )
    }

    /// How the account shows up in the authenticator app, as Matron Web
    /// words it; `null` when the journal named neither.
    fun shownAs(setup: TwoFactorSetup): String? {
        val issuer = setup.issuer?.takeIf { it.isNotEmpty() }
        val account = setup.account?.takeIf { it.isNotEmpty() }
        return when {
            issuer != null && account != null -> "It shows up as $issuer ($account)."
            issuer != null -> "It shows up as $issuer."
            account != null -> "It shows up as $account."
            else -> null
        }
    }

    /// The setup key as people type it: groups of four.
    fun groupedSecret(secret: String): String = secret.chunked(4).joinToString(" ")
}
