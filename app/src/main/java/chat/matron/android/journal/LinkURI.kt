package chat.matron.android.journal

import java.net.URLEncoder

/// The QR sign-in payload — the single place the format is known:
/// `matron://link?v=1&server=<URL-encoded base server URL>&code=XXXX-XXXX`.
/// Apple carries an equivalent parser; the server never sees the URI.
///
/// Parsed by hand (scheme/host prefix + query split) rather than
/// `android.net.Uri` so plain JVM unit tests cover it without Robolectric.
///
/// Controller amendment (parity with matron-apple's LinkURI): scheme/host
/// matching is case-insensitive — RFC 3986 schemes and hosts are
/// case-insensitive, and QR alphanumeric mode is uppercase-only, so an
/// uppercase-scanned `MATRON://LINK?...` must still parse. Query values
/// (`server`/`code`) stay case-sensitive; [PairingCode] already normalizes
/// case on the code separately.
object LinkURI {
    sealed class ParseError : Exception() {
        /// Not ours at all — scanner shows "Not a Matron sign-in code."
        class NotALink : ParseError()
        /// Ours, but a future version — scanner shows "update the app".
        class UnsupportedVersion : ParseError()
        /// Ours and v=1, but the parts don't parse.
        class Malformed : ParseError()
    }

    data class Parsed(val serverURL: String, val code: String)

    private const val PREFIX = "matron://link?"

    fun format(serverURL: String, code: String): String {
        val server = URLEncoder.encode(serverURL, "UTF-8")
        val encodedCode = URLEncoder.encode(code, "UTF-8")
        return "${PREFIX}v=1&server=$server&code=$encodedCode"
    }

    fun parse(raw: String): Parsed {
        if (!MatronQueryURI.hasPrefix(raw, PREFIX)) throw ParseError.NotALink()
        val params = MatronQueryURI.params(raw, PREFIX)
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val server = params["server"] ?: throw ParseError.Malformed()
        // Plan-owner amendment (mirrors matron-apple's LinkURI parser): https
        // is accepted from any host, but http is only ever accepted to
        // localhost-ish dev hosts. Any other http host is a malformed link,
        // not a silently-accepted plaintext one.
        if (!MatronQueryURI.isAcceptableServer(server)) throw ParseError.Malformed()
        val code = params["code"] ?: throw ParseError.Malformed()
        if (!PairingCode.isPlausible(code)) throw ParseError.Malformed()
        return Parsed(serverURL = server, code = PairingCode.display(code))
    }
}
