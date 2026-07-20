package chat.matron.android.journal

/// The rendezvous QR payload — the single place the format is known:
/// `matron://rlink?v=2&rid=<26-char rid>&k=<base64url 32-byte key>`. The
/// reverse of [LinkURI]: this QR is SHOWN by a signed-out device and SCANNED
/// by a signed-in phone. It carries the rendezvous id and the single-use
/// offer key — never the poll secret, never a server. The key never reaches
/// the relay; it travels only screen→camera. Apple carries an equivalent
/// parser (rendezvous-offer-encryption spec).
///
/// Parsed by hand (prefix + query split, like [LinkURI]) so plain JVM unit
/// tests cover it without Robolectric. Scheme/host matching is
/// case-insensitive (RFC 3986 + uppercase-only QR alphanumeric mode); query
/// values (`v`/`rid`/`k`) stay case-sensitive.
object RendezvousURI {
    data class Parsed(val rid: String, val key: ByteArray) {
        // ByteArray needs content-based equality for a value type.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Parsed && rid == other.rid && key.contentEquals(other.key))
        override fun hashCode(): Int = 31 * rid.hashCode() + key.contentHashCode()
    }

    sealed class ParseError : Exception() {
        /// Not ours at all — scanner shows "Not a Matron link code."
        class NotALink : ParseError()
        /// Ours, but an unsupported version — scanner shows "update the app".
        class UnsupportedVersion : ParseError()
        /// Ours and v=2, but the rid or key doesn't parse.
        class Malformed : ParseError()
    }

    private const val PREFIX = "matron://rlink?"
    private val RID_RE = Regex("^[0-9BCDFGHJKMNPQRSTVWXYZ]{26}$")
    private const val KEY_BYTES = 32

    fun format(rid: String, key: ByteArray): String =
        "${PREFIX}v=2&rid=$rid&k=${Base64URL.encode(key)}" // rid + base64url need no encoding

    fun parse(raw: String): Parsed {
        if (!raw.startsWith(PREFIX, ignoreCase = true)) throw ParseError.NotALink()
        val params = raw.substring(PREFIX.length).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "2") throw ParseError.UnsupportedVersion()
        val rid = params["rid"] ?: throw ParseError.Malformed()
        if (!RID_RE.matches(rid)) throw ParseError.Malformed()
        val k = params["k"] ?: throw ParseError.Malformed()
        val key = Base64URL.decode(k) ?: throw ParseError.Malformed()
        if (key.size != KEY_BYTES) throw ParseError.Malformed()
        return Parsed(rid, key)
    }
}
