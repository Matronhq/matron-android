package chat.matron.android.models

/*
 * One sign-in: the account, the journal, and the device id the journal gave
 * this app when it signed in. Every sign-in gets a new device id, so two
 * sessions for the same account (signed out, then signed in again, or linked
 * again by QR) are different sign-ins even though their user ids match.
 *
 * The app keys each journal stack, its revocation check and its local
 * database by this, never by the user id alone: a late call for the old
 * sign-in must never reach, or sign out, the new one.
 */

/// Equal for two values of the same sign-in. The access token is left out on
/// purpose: unlocking a locked account gives the same device a new token, and
/// that is still the same sign-in.
val UserSession.signInKey: String
    get() = "$userID|$deviceID|$homeserverURL"

/// Whether [other] is the same sign-in as this one (see [signInKey]).
fun UserSession.isSameSignIn(other: UserSession): Boolean = signInKey == other.signInKey

/// The file name of this sign-in's local journal mirror. Per sign-in, so a
/// fresh sign-in's wipe can never delete the file an older sign-in's stack
/// still holds open.
val UserSession.journalStoreFileName: String
    get() {
        val device = deviceID.filter { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }
        return "${userID.sanitizedForFilename()}.d$device.sqlite"
    }

/// The name the mirror had before it was per sign-in. A restored session
/// moves its old file to [journalStoreFileName] once, so an update keeps the
/// local copy instead of syncing it all again.
val UserSession.legacyJournalStoreFileName: String
    get() = "${userID.sanitizedForFilename()}.sqlite"

/// Keeps a user id usable as a filename (ids are opaque but may carry `/` `:`).
internal fun String.sanitizedForFilename(): String =
    map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
