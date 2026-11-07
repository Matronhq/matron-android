package chat.matron.android.journal

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject

// Contacts, and contacts on other journals made by invite link
// (protocol.md "Contacts", "Contacts across journals"). The wording shown to
// the user is shared with the web and Apple apps: keep it verbatim.

/// One row of `GET /contacts`. [address] is `name` on this journal and
/// `name@journal` on another one.
data class Contact(
    val id: String,
    val address: String,
    val peerUser: String,
    val peerJournal: String? = null,
    val displayName: String? = null,
    val state: String,
    val keyChanged: Boolean = false,
    /// When their journal was last found unreachable, in ms. Not sent by
    /// the journal yet; null when absent.
    val unreachableSince: Long? = null,
) {
    /// A contact on another journal: its address names the journal.
    val isOtherJournal: Boolean get() = !peerJournal.isNullOrEmpty() || address.contains('@')

    /// The row's first line.
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: peerUser.ifBlank { address }

    /// The row's second line: the full address for another journal's
    /// contact, nothing for one on this journal.
    val addressLine: String? get() = if (isOtherJournal) address else null

    val isPendingOut: Boolean get() = state == "pending_out"

    companion object {
        fun fromJson(json: JsonObject): Contact? {
            val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            val address = json.stringOrNull("address")?.takeIf { it.isNotEmpty() } ?: return null
            return Contact(
                id = id,
                address = address,
                peerUser = json.stringOrNull("peer_user") ?: address.substringBefore('@'),
                peerJournal = json.stringOrNull("peer_journal")?.takeIf { it.isNotEmpty() },
                displayName = json.stringOrNull("display_name"),
                state = json.stringOrNull("state") ?: "",
                keyChanged = json.boolOrNull("key_changed") ?: false,
                unreachableSince = json.longOrNull("unreachable_since"),
            )
        }

        fun listFromJson(json: JsonObject): List<Contact> =
            json.arrayOrNull("contacts")?.objects()?.mapNotNull(::fromJson).orEmpty()
    }
}

/// One row of `GET /contacts/invites`. Times are ms.
data class ContactInvite(
    val id: String,
    val state: String,
    val createdAt: Long,
    val expiresAt: Long,
) {
    val isOpen: Boolean get() = state == "open"

    companion object {
        fun fromJson(json: JsonObject): ContactInvite? {
            val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            return ContactInvite(
                id = id,
                state = json.stringOrNull("state") ?: "",
                createdAt = json.longOrNull("created_at") ?: 0,
                expiresAt = json.longOrNull("expires_at") ?: 0,
            )
        }

        /// The open invites, in the journal's order (newest first). Used,
        /// expired and withdrawn ones are not listed.
        fun openFromJson(json: JsonObject): List<ContactInvite> =
            json.arrayOrNull("invites")?.objects()?.mapNotNull(::fromJson)?.filter { it.isOpen }.orEmpty()
    }
}

/// A freshly made invite and its link. The link is shown once and never
/// stored: hold it in memory only, and drop it when the user is done.
data class MintedInvite(val invite: ContactInvite?, val link: String) {
    companion object {
        fun fromJson(json: JsonObject): MintedInvite? {
            val link = json.stringOrNull("link")?.takeIf { it.isNotEmpty() } ?: return null
            return MintedInvite(json.objectOrNull("invite")?.let(ContactInvite::fromJson), link)
        }
    }
}

/// `POST /contacts/invites/open`: who a link is from. Nothing is sent.
data class InvitePreview(
    val address: String,
    val name: String,
    val journal: String,
    /// Already grouped in fours by the journal; shown as given.
    val fingerprint: String,
    val keyChanged: Boolean = false,
    /// The user's existing live row for that address, if any.
    val existing: Contact? = null,
) {
    /// "<address> invites you to be contacts"
    val headline: String get() = "$address invites you to be contacts"

    /// An active contact already: said, but Accept stays (a reconnect).
    val alreadyContacts: Boolean get() = existing?.state == "active"

    /// Blocked by the user: the only case Accept is hidden.
    val blocked: Boolean get() = existing?.state == "blocked"

    companion object {
        fun fromJson(json: JsonObject): InvitePreview? {
            val inviter = json.objectOrNull("inviter") ?: return null
            val name = inviter.stringOrNull("name") ?: return null
            val journal = inviter.stringOrNull("journal") ?: return null
            return InvitePreview(
                address = inviter.stringOrNull("address")?.takeIf { it.isNotEmpty() } ?: "$name@$journal",
                name = name,
                journal = journal,
                fingerprint = inviter.stringOrNull("fingerprint") ?: "",
                keyChanged = json.boolOrNull("key_changed") ?: false,
                existing = json.objectOrNull("contact")?.let(Contact::fromJson),
            )
        }
    }
}

/// The answer to an agent's invite ask: the ask's new state and, on an
/// approve, the link (shown once, on this device).
data class InviteAskAnswer(val state: String?, val link: String?) {
    companion object {
        fun fromJson(json: JsonObject): InviteAskAnswer = InviteAskAnswer(
            state = json.objectOrNull("ask")?.stringOrNull("state"),
            link = json.stringOrNull("link")?.takeIf { it.isNotEmpty() },
        )
    }
}

/// A contacts route refused: `409 {error:'conflict', blocked_by}` or
/// `502 {error:'unreachable'|'bad_journal'}`. [reason] is `blocked_by`, or
/// the 502's `error`.
class ContactRefusal(val status: Int, val reason: String) : Exception(reason)

/// The contacts slice of the journal API.
interface ContactsProviding {
    suspend fun contacts(): List<Contact>
    suspend fun contactInvites(): List<ContactInvite>
    suspend fun createContactInvite(): MintedInvite
    suspend fun revokeContactInvite(id: String)
    suspend fun openContactInvite(link: String): InvitePreview
    suspend fun acceptContactInvite(link: String)
}

/// The one call that answers an agent's invite ask, so the chat view model
/// depends on just this.
interface InviteAskAnswering {
    suspend fun answerInviteAsk(askId: String, approve: Boolean): InviteAskAnswer
}

/// Reads an invite link: `matron://contact?v=1&j=<host>&u=<name>&t=<token>&fp=<fp>`.
object ContactInviteLink {
    private val FIELDS = setOf("v", "j", "u", "t", "fp")

    /// The link inside pasted text (trimmed; the first `matron://contact?…`
    /// run when the text carries more), or null when there is none.
    fun extract(text: String): String? {
        val trimmed = text.trim()
        val start = trimmed.indexOf("matron://contact", ignoreCase = true)
        if (start < 0) return null
        val rest = trimmed.substring(start)
        val end = rest.indexOfFirst { it.isWhitespace() || it == '"' || it == '\'' || it == '<' || it == '>' }
        return if (end < 0) rest else rest.substring(0, end)
    }

    /// Whether [raw] is a well-formed invite link: scheme `matron`, host
    /// `contact`, exactly the five fields once each, `v=1`, none empty. The
    /// journal checks again; this only spares a round trip for a typo.
    fun isValid(raw: String): Boolean = fields(raw) != null

    /// The link's fields, or null when it is not a well-formed invite link.
    fun fields(raw: String): Map<String, String>? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("matron", ignoreCase = true)) return null
        // `matron://contact?…`: an opaque-looking authority with no path.
        if (!uri.host.equals("contact", ignoreCase = true) && !uri.rawAuthority.equals("contact", ignoreCase = true)) return null
        val path = uri.rawPath.orEmpty()
        if (path.isNotEmpty() && path != "/") return null
        if (uri.rawFragment != null) return null
        val query = uri.rawQuery ?: return null
        val out = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) return null
            val key = pair.substring(0, eq)
            val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull() ?: return null
            if (key !in FIELDS || key in out || value.isEmpty()) return null
            out[key] = value
        }
        if (out.keys != FIELDS || out["v"] != "1") return null
        return out
    }
}

/// The plain words for a refused contacts call.
object ContactsWording {
    const val OTHER = "Something went wrong. Try again."
    const val INVALID_LINK = "This is not a valid invite link."
    const val FEDERATION_OFF = "Contacts on other Matrons are turned off on your journal."
    const val ALREADY_CONTACTS = "You are already contacts."
    const val BLOCKED = "You have blocked this person. Unblock them first."
    const val PENDING = "You have already accepted; waiting for their journal."
    const val ACCEPT_SENT = "Sent. You become contacts when their journal confirms."
    const val TOO_MANY_INVITES = "You already have 20 open invites. Withdraw one first."
    const val CANNOT_INVITE = "This account cannot make invites."

    private val REASONS = mapOf(
        "fingerprint_mismatch" to "This link's fingerprint does not match their journal. Do not accept it; ask them for a new link.",
        "journal_blocked" to "Your journal has blocked that journal.",
        "own_journal" to "This link is from your own journal. Add people here by name instead.",
        "unreachable" to "Their journal could not be reached. Try again later.",
        "bad_journal" to "That address is not a Matron journal.",
        "federation_off" to FEDERATION_OFF,
        "already_contact" to ALREADY_CONTACTS,
        "pending" to PENDING,
        "blocked" to BLOCKED,
        "too_many_contacts" to "You have too many contacts on other journals.",
        "too_many_invites" to TOO_MANY_INVITES,
    )

    /// Opening or accepting a link. [journal] is the inviter's journal, the
    /// link's `j`, which the journal-not-allowed refusal names.
    fun linkError(error: Throwable, journal: String? = null): String = when (error) {
        is ContactRefusal ->
            if (error.reason == "journal_not_allowed") "Your journal doesn't allow contacts on ${journal ?: "that journal"}."
            else REASONS[error.reason] ?: OTHER
        is JournalApiError.Http -> if (error.status == 400) INVALID_LINK else OTHER
        else -> OTHER
    }

    /// Making an invite.
    fun inviteError(error: Throwable): String = when (error) {
        is ContactRefusal -> when (error.reason) {
            "too_many_invites" -> TOO_MANY_INVITES
            "federation_off" -> FEDERATION_OFF
            else -> OTHER
        }
        JournalApiError.Forbidden -> CANNOT_INVITE
        else -> OTHER
    }
}

/// The dates the People screen shows: "Made <date> · expires <date>" and
/// "Their journal hasn't answered since <date>.".
object ContactsDates {
    private fun format(ms: Long, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(ms).atZone(zone))

    fun inviteRow(invite: ContactInvite, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "Made ${format(invite.createdAt, zone, locale)} · expires ${format(invite.expiresAt, zone, locale)}"

    fun unreachable(since: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        "Their journal hasn't answered since ${format(since, zone, locale)}."
}
