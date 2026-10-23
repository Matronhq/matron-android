package chat.matron.android.journal

/// `GET /account/email` — the address a password-reset link goes to, `null`
/// when none is on file. [resetByEmail] is false while the journal can't
/// send mail, so a reset link would never arrive.
data class AccountEmail(val email: String?, val resetByEmail: Boolean)

/// Settings → Security's "Email address". [JournalApi] implements it; tests
/// fake it. A journal predating account email 404s the GET
/// ([JournalApiError.NotFound]) and the row stays hidden. A wrong current
/// password on the PUT is [JournalApiError.BadPassword], never
/// [JournalApiError.Unauthenticated].
interface AccountEmailProviding {
    suspend fun accountEmail(): AccountEmail

    /// Sets the address, or removes it when [email] is null. Returns the
    /// address now on file.
    suspend fun setAccountEmail(password: String, email: String?): String?
}
