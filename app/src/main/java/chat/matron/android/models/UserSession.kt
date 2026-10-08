package chat.matron.android.models

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val userID: String,
    val deviceID: String,
    val homeserverURL: String,
    val accessToken: String,
    val refreshToken: String? = null,
) {
    /// Per-user preferences key for the persisted "this user has completed the
    /// post-login verification gate" flag. Scoped by `userID` so multi-account
    /// on the same device re-runs the gate per account.
    val verifyDoneKey: String
        get() = "matron.verify-done.$userID"
}
