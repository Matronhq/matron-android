package chat.matron.android.journal

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Shared mechanics of the `matron://<host>?v=1&server=…&code=…` QR payloads
/// ([LinkURI] for sign-in, [PairURI] for agent pairing). Each payload keeps its
/// own parser and error type; only the query split and the server-URL rule
/// live here so the two can never drift apart.
///
/// Parsed by hand (prefix + query split) rather than `android.net.Uri` so
/// plain JVM unit tests cover it without Robolectric.
internal object MatronQueryURI {
    /// Whether [raw] starts with [prefix]. Scheme/host matching is
    /// case-insensitive — RFC 3986 schemes and hosts are, and QR alphanumeric
    /// mode is uppercase-only, so an uppercase-scanned `MATRON://LINK?...`
    /// must still match.
    fun hasPrefix(raw: String, prefix: String): Boolean = raw.startsWith(prefix, ignoreCase = true)

    /// The query parameters after [prefix], URL-decoded. Duplicate keys are
    /// last-wins (`List<Pair>.toMap()` semantics). Values stay case-sensitive.
    fun params(raw: String, prefix: String): Map<String, String?> =
        raw.substring(prefix.length).split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else pair.substring(0, idx) to runCatching {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

    /// Whether [server] is an acceptable embedded server URL: https from any
    /// host, http only to localhost-ish dev hosts — the same carve-out
    /// ServerURLValidator applies to typed server entry. Keep this condition
    /// in sync with the localhost check in auth/ServerURLValidator.kt.
    fun isAcceptableServer(server: String): Boolean {
        val url = server.toHttpUrlOrNull() ?: return false
        // toHttpUrlOrNull() only ever returns http/https URLs, so this check
        // is belt-and-braces — keep it; it documents the constraint and
        // survives a parser swap.
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.scheme == "http" && !isLocalhostHost(url.host)) return false
        return true
    }

    private fun isLocalhostHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}
