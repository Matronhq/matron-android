package chat.matron.android.journal

/// `POST /password` answered: [signedOut] is how many of the user's OTHER
/// client devices the journal signed out (0 when not asked to, and on a
/// journal that predates the `sign_out_others` flag). Agent boxes are never
/// signed out, and the calling device stays signed in.
data class PasswordChange(val signedOut: Int)

/// Settings → Security's "Change password". [JournalApi] implements it;
/// tests fake it. A wrong current password throws
/// [JournalApiError.BadPassword], never [JournalApiError.Unauthenticated]:
/// the session itself is fine.
interface PasswordChanging {
    suspend fun changePassword(oldPassword: String, newPassword: String, signOutOthers: Boolean): PasswordChange
}
