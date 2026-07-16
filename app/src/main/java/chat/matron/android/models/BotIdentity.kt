package chat.matron.android.models

data class BotIdentity(
    val matrixID: String,
    val displayName: String,
    val avatarURL: String?,
)
