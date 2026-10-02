package chat.matron.android.journal

import java.net.URLEncoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// The agent-pairing QR payload the bridge shows — the single place the
/// format is known:
/// `matron://pair?v=1&server=<URL-encoded https base>&code=XXXX-XXXX`, where
/// the code is the 8-char pair code from the journal's `/pair/start`.
///
/// Deliberately a separate parser from [LinkURI] (sign-in): a pairing QR must
/// never be accepted as a sign-in link or vice versa. The two share the query
/// split and server-URL rule via [MatronQueryURI], so validation is identical:
/// `v=1`, https (or http to a localhost-ish dev host), a normalised 8-char code.
object PairURI {
    sealed class ParseError : Exception() {
        /// Not a pairing payload at all.
        class NotAPairURI : ParseError()
        /// A pairing payload, but a future version — "update the app".
        class UnsupportedVersion : ParseError()
        /// A v=1 pairing payload whose parts don't parse.
        class Malformed : ParseError()
    }

    data class Parsed(val serverURL: String, val code: String)

    private const val PREFIX = "matron://pair?"

    /// Where pairing QRs belong, for scanners that catch one by mistake.
    const val ADD_AGENT_PATH = "Settings → Manage Devices → Add Agent (+)"

    /// Whether [raw] claims to be a pairing payload (prefix only, no
    /// validation) — for other scanners to recognise a pairing QR scanned in
    /// the wrong place and point the user at Add Agent.
    fun isPairURI(raw: String): Boolean = MatronQueryURI.hasPrefix(raw.trim(), PREFIX)

    fun format(serverURL: String, code: String): String {
        val server = URLEncoder.encode(serverURL, "UTF-8")
        val encodedCode = URLEncoder.encode(code, "UTF-8")
        return "${PREFIX}v=1&server=$server&code=$encodedCode"
    }

    fun parse(raw: String): Parsed {
        if (!MatronQueryURI.hasPrefix(raw, PREFIX)) throw ParseError.NotAPairURI()
        val params = MatronQueryURI.params(raw, PREFIX)
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val server = params["server"] ?: throw ParseError.Malformed()
        if (!MatronQueryURI.isAcceptableServer(server)) throw ParseError.Malformed()
        val code = params["code"] ?: throw ParseError.Malformed()
        if (!PairingCode.isPlausible(code)) throw ParseError.Malformed()
        return Parsed(serverURL = server, code = PairingCode.display(code))
    }

    /// Whether two server URLs share an origin: scheme, host (case-folded) and
    /// effective port (default ports filled in). Paths are ignored — a journal
    /// is identified by where it lives, not by a trailing slash. Null when
    /// either side isn't an http(s) URL.
    fun sameOrigin(a: String, b: String): Boolean? {
        val left = a.trim().toHttpUrlOrNull() ?: return null
        val right = b.trim().toHttpUrlOrNull() ?: return null
        return left.scheme == right.scheme && left.host == right.host && left.port == right.port
    }

    /// `host` or `host:port` (port only when non-default) for user-facing
    /// copy; the raw string when it isn't an http(s) URL.
    fun displayHost(server: String): String {
        val url = server.trim().toHttpUrlOrNull() ?: return server
        return if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
    }
}
