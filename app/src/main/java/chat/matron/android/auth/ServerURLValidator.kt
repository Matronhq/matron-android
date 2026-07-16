package chat.matron.android.auth

import java.net.URI

/// Normalises and validates a user-typed server URL. Ported from matron-apple's
/// `ServerURLValidator` (which returns a `URL`); here it returns the normalised
/// URL *string* so the assertions match the Apple suite's `absoluteString`
/// comparisons (OkHttp's `HttpUrl.toString()` always re-adds a trailing `/`,
/// which the Apple validator deliberately strips).
object ServerURLValidator {
    /// Validation failure reasons. Modeled as exceptions so they throw through
    /// the `normalize` surface the way the Swift `throws` do; `data object`
    /// gives value-equality for `assertEquals`.
    sealed class ValidationError : Exception() {
        data object Empty : ValidationError()
        data object InsecureScheme : ValidationError()
        data object NoHost : ValidationError()
        data object Malformed : ValidationError()
    }

    /// `https://` everywhere except localhost-ish dev hosts. Plain http to
    /// localhost is the standard pattern for a local dev homeserver (the Docker
    /// harness listens on `http://localhost:6167`). Production always runs
    /// behind HTTPS, so the carve-out can't expose remote creds over plaintext.
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw ValidationError.Empty

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: throw ValidationError.Malformed
        val scheme = uri.scheme ?: throw ValidationError.Malformed
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: throw ValidationError.NoHost

        val isLocalhostHost = host == "localhost" ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host == "[::1]"
        when {
            scheme == "http" -> if (!isLocalhostHost) throw ValidationError.InsecureScheme
            scheme != "https" -> throw ValidationError.InsecureScheme
        }

        var path = uri.rawPath ?: ""
        // Swift: a bare "/" path becomes "", any other trailing "/" is stripped.
        if (path == "/") path = "" else if (path.endsWith("/")) path = path.dropLast(1)
        val portPart = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$host$portPart$path"
    }
}
