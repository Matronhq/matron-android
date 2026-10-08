package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject

// The `/settings` route's shapes (matron-journal "For you" settings): the
// per-user settings that are neither the Coordinator's nor a device's own.
// The route implementations live on [JournalApi] (its request plumbing is
// private), which implements [UserSettingsProviding]; the decoder is pure so
// tests pin the shape without a server.

/// The user's journal-held settings. [notices] — "Send things I need to read
/// to For you": agents file what the user should read as `notice` items
/// instead of leaving it in chat. The journal defaults it to on.
data class UserSettings(val notices: Boolean)

/// `GET /settings` and `PATCH /settings`. A journal predating the route 404s
/// the GET ([JournalApiError.NotFound]), which hides the setting. [JournalApi]
/// implements it; tests fake it.
interface UserSettingsProviding {
    suspend fun settings(): UserSettings
    suspend fun updateSettings(notices: Boolean): UserSettings
}

object UserSettingsDecoding {
    /// `{notices}` — the GET/PATCH body, and the `settings` object a
    /// `hello_ok` or `settings` control frame carries. `null` when the
    /// object has no boolean `notices` (malformed, or a journal that sends
    /// no settings at all).
    fun settings(obj: JsonObject?): UserSettings? =
        obj?.boolOrNull("notices")?.let { UserSettings(notices = it) }
}
