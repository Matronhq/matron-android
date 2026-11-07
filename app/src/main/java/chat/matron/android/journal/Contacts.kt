package chat.matron.android.journal

import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.update
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
/// [link] is the `matron://` form, [webLink] the https one (null from a
/// journal that does not send it yet).
data class MintedInvite(val invite: ContactInvite?, val link: String, val webLink: String? = null) {
    /// The link to show, copy, share and put in the QR code.
    val shareLink: String get() = webLink ?: link

    companion object {
        fun fromJson(json: JsonObject): MintedInvite? {
            val link = json.stringOrNull("link")?.takeIf { it.isNotEmpty() } ?: return null
            return MintedInvite(json.objectOrNull("invite")?.let(ContactInvite::fromJson), link, InviteLinks.webLinkOf(json))
        }
    }
}

/// Which of the two forms an answer carrying a new link is shared in.
object InviteLinks {
    /// The https form when the journal sent it — chat apps and mail make it
    /// tappable — else the `matron://` one.
    fun shareable(link: String?, webLink: String?): String? = webLink ?: link

    fun webLinkOf(json: JsonObject): String? = json.stringOrNull("web_link")?.takeIf { it.isNotEmpty() }

    /// The link to show from an answer carrying `link` and maybe `web_link`.
    fun shareableOf(json: JsonObject): String? =
        shareable(json.stringOrNull("link")?.takeIf { it.isNotEmpty() }, webLinkOf(json))
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
/// approve, the link to share (shown once, on this device): the https form
/// when the journal sends it, else the `matron://` one.
data class InviteAskAnswer(val state: String?, val link: String?) {
    companion object {
        fun fromJson(json: JsonObject): InviteAskAnswer = InviteAskAnswer(
            state = json.objectOrNull("ask")?.stringOrNull("state"),
            link = InviteLinks.shareableOf(json),
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

/// Reads an invite link, in either form the journal makes:
///
///   matron://contact?v=1&j=<host>&u=<name>&t=<token>&fp=<fp>
///   https://<host>/contact#v=1&j=<host>&u=<name>&t=<token>&fp=<fp>
///
/// As strict as the journal's own reader (protocol.md "Contacts across
/// journals"): exactly the five fields, each once and in its own shape. An
/// https link must be on the journal it names, at `/contact` exactly, with
/// no query or user info, every field in the fragment.
object ContactInviteLink {
    private val FIELDS = setOf("v", "j", "u", "t", "fp")
    private const val MAX_LENGTH = 1024
    private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    private val FINGERPRINT = Regex("^[a-z2-7]{26}$")
    private val USER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    private val HOST = Regex("^(?=.{1,253}(?::\\d{1,5})?$)$LABEL(?:\\.$LABEL)*(?::\\d{1,5})?$")

    /// A link's fields, read. [journal] and [fingerprint] are lowercased.
    data class Fields(val journal: String, val user: String, val token: String, val fingerprint: String)

    /// The link inside pasted text: the first invite link, in either form,
    /// among its words (trimmed of quotes, brackets and trailing
    /// punctuation), or null when there is none.
    fun extract(text: String): String? {
        val words = text.split(Regex("[\\s\"'<>]+")).filter { it.isNotEmpty() }
        for (word in words) {
            var candidate = word.trimStart('(', '[', '{')
            while (candidate.isNotEmpty()) {
                if (parse(candidate) != null) return candidate
                if (candidate.last() !in TRAILING) break
                candidate = candidate.dropLast(1)
            }
        }
        return null
    }

    private const val TRAILING = ".,;:!?)]}"

    /// Whether [raw] is a well-formed invite link in either form. The
    /// journal checks again; this only spares a round trip for a typo.
    fun isValid(raw: String): Boolean = parse(raw) != null

    /// Whether [url] is meant as an invite link — `matron://contact…` or an
    /// https link to `/contact` — well-formed or not. Such a link opens
    /// People's accept card, which says when it is not a valid one, rather
    /// than going anywhere else.
    fun isInviteShaped(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith("matron://contact", ignoreCase = true)) {
            val rest = trimmed.substring("matron://contact".length)
            return rest.isEmpty() || rest[0] == '?' || rest[0] == '/' || rest[0] == '#'
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.rawPath == "/contact"
    }

    /// The link's fields, or null when it is not a well-formed invite link.
    fun parse(raw: String): Fields? {
        if (raw.length > MAX_LENGTH) return null
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val pairs: String = when {
            uri.scheme.equals("matron", ignoreCase = true) -> {
                // `matron://contact?…`: an opaque-looking authority with no path.
                if (!uri.host.equals("contact", ignoreCase = true) && !uri.rawAuthority.equals("contact", ignoreCase = true)) return null
                val path = uri.rawPath.orEmpty()
                if (path.isNotEmpty() && path != "/") return null
                if (uri.rawFragment != null) return null
                uri.rawQuery ?: return null
            }
            uri.scheme.equals("https", ignoreCase = true) -> {
                if (uri.rawPath != "/contact") return null
                if (!uri.rawQuery.isNullOrEmpty() || !uri.rawUserInfo.isNullOrEmpty()) return null
                uri.rawFragment?.takeIf { it.isNotEmpty() } ?: return null
            }
            else -> return null
        }
        val fields = fieldsOf(pairs) ?: return null
        if (fields["v"] != "1") return null
        val journal = fields["j"].orEmpty().lowercase(Locale.ROOT)
        val user = fields["u"].orEmpty()
        val token = fields["t"].orEmpty()
        val fingerprint = fields["fp"].orEmpty().lowercase(Locale.ROOT)
        if (!USER.matches(user) || !HOST.matches(journal) || !TOKEN.matches(token) || !FINGERPRINT.matches(fingerprint)) return null
        if (uri.scheme.equals("https", ignoreCase = true)) {
            val named = authorityOf(journal) ?: return null
            if (authorityOf(uri) != named) return null
        }
        return Fields(journal, user, token, fingerprint)
    }

    /// `host[:port]` as a browser reads it: lowercased, the default port
    /// dropped. Null for an authority that is not a host.
    private fun authorityOf(uri: URI): String? {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return withPort(host, uri.port)
    }

    /// A link's `j` as a URL writes its host, the way the journal compares
    /// it: an explicit default port (`:443`) dropped, and null for a port a
    /// URL cannot have (over 65535).
    private fun authorityOf(journal: String): String? {
        val colon = journal.lastIndexOf(':')
        if (colon < 0) return journal
        val port = journal.substring(colon + 1).toIntOrNull() ?: return null
        return withPort(journal.substring(0, colon), port)
    }

    private fun withPort(host: String, port: Int): String? = when {
        port == -1 || port == 443 -> host
        port !in 0..65535 -> null
        else -> "$host:$port"
    }

    /// `a=1&b=2` read like a browser's URLSearchParams (empty pairs
    /// skipped), or null when a key is not one of the five or comes twice,
    /// or one is missing.
    private fun fieldsOf(pairs: String): Map<String, String>? {
        val out = linkedMapOf<String, String>()
        for (pair in pairs.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val key = decode(rawKey) ?: return null
            val value = decode(rawValue) ?: return null
            if (key !in FIELDS || key in out) return null
            out[key] = value
        }
        return out.takeIf { it.keys == FIELDS }
    }

    private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()
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

/// The invite links waiting to be shown, for one signed-in session. Held by
/// the session's core, outside any screen, so a link survives the app lock
/// (which takes the whole signed-in shell down) and a tap made while no
/// screen is listening. In memory only: never stored, cached or logged.
///
/// [scope] runs the calls that bring a link back (making an invite,
/// approving an agent's ask), so a lock mid-call cannot cancel it after the
/// journal has made the link.
class RevealedInviteLinks(val scope: kotlinx.coroutines.CoroutineScope) {
    enum class Source { INVITE, ASK }

    data class Reveal(val link: String, val source: Source)

    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<List<Reveal>>(emptyList())

    /// The links waiting to be shown, oldest first: the first is on screen.
    val current: kotlinx.coroutines.flow.StateFlow<List<Reveal>> = _pending

    fun show(link: String, source: Source) {
        _pending.update { it + Reveal(link, source) }
    }

    /// The user is done with [reveal]: it is dropped for good.
    fun done(reveal: Reveal) {
        _pending.update { list ->
            val index = list.indexOf(reveal)
            if (index < 0) list else list.toMutableList().apply { removeAt(index) }
        }
    }

    fun clearAll() {
        _pending.value = emptyList()
    }
}
