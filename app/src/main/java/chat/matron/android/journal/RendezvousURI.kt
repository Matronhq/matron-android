package chat.matron.android.journal

/// The rendezvous QR payload — the single place the format is known:
/// `matron://rlink?v=1&rid=<26-char rid>`. The reverse of [LinkURI]: this QR
/// is SHOWN by a signed-out device and SCANNED by a signed-in phone. It
/// carries only the rendezvous id — never the poll secret, never a server.
/// Apple carries an equivalent parser; the relay never sees the URI.
///
/// Parsed by hand (prefix + query split, like [LinkURI]) so plain JVM unit
/// tests cover it without Robolectric.
///
/// Controller amendment (parity with matron-apple's RendezvousURI):
/// scheme/host matching is case-insensitive — RFC 3986 schemes and hosts are
/// case-insensitive, and QR alphanumeric mode is uppercase-only, so an
/// uppercase-scanned `MATRON://RLINK?...` must still parse. Query values
/// (the `v`/`rid` params) stay case-sensitive.
object RendezvousURI {
    sealed class ParseError : Exception() {
        /// Not ours at all — scanner shows "Not a Matron link code."
        class NotALink : ParseError()
        /// Ours, but a future version — scanner shows "update the app".
        class UnsupportedVersion : ParseError()
        /// Ours and v=1, but the rid doesn't parse.
        class Malformed : ParseError()
    }

    private const val PREFIX = "matron://rlink?"
    // Same alphabet as PairingCode / link codes; 26 chars ≈ 128 bits.
    private val RID_RE = Regex("^[0-9BCDFGHJKMNPQRSTVWXYZ]{26}$")

    fun format(rid: String): String = "${PREFIX}v=1&rid=$rid" // rid alphabet needs no encoding

    fun parse(raw: String): String {
        if (!raw.startsWith(PREFIX, ignoreCase = true)) throw ParseError.NotALink()
        val params = raw.substring(PREFIX.length).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val rid = params["rid"] ?: throw ParseError.Malformed()
        if (!RID_RE.matches(rid)) throw ParseError.Malformed()
        return rid
    }
}
