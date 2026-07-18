package chat.matron.android.journal

import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// The QR sign-in payload — the single place the format is known:
/// `matron://link?v=1&server=<URL-encoded base server URL>&code=XXXX-XXXX`.
/// Apple carries an equivalent parser; the server never sees the URI.
///
/// Parsed by hand (scheme/host prefix + query split) rather than
/// `android.net.Uri` so plain JVM unit tests cover it without Robolectric.
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
        if (!raw.startsWith(PREFIX)) throw ParseError.NotALink()
        val params = raw.removePrefix(PREFIX).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else pair.substring(0, idx) to runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull()
        }.toMap()
        val version = params["v"] ?: throw ParseError.Malformed()
        if (version != "1") throw ParseError.UnsupportedVersion()
        val server = params["server"] ?: throw ParseError.Malformed()
        val url = server.toHttpUrlOrNull() ?: throw ParseError.Malformed()
        // toHttpUrlOrNull() only ever returns http/https URLs, so this check
        // is belt-and-braces — keep it; it documents the constraint and
        // survives a parser swap.
        if (url.scheme != "http" && url.scheme != "https") throw ParseError.Malformed()
        // Plan-owner amendment (mirrors matron-apple's LinkURI parser): https
        // is accepted from any host, but http is only ever accepted to
        // localhost-ish dev hosts — the same carve-out ServerURLValidator
        // applies to typed server entry. Any other http host is a malformed
        // link, not a silently-accepted plaintext one. Keep this condition in
        // sync with the localhost check in auth/ServerURLValidator.kt.
        if (url.scheme == "http" && !isLocalhostHost(url.host)) throw ParseError.Malformed()
        val code = params["code"] ?: throw ParseError.Malformed()
        if (!PairingCode.isPlausible(code)) throw ParseError.Malformed()
        return Parsed(serverURL = server, code = PairingCode.display(code))
    }

    private fun isLocalhostHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
