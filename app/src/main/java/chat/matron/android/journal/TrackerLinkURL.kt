package chat.matron.android.journal

import java.net.URI
import java.net.URLDecoder

/// A shareable tracker link: `https://<journal>/u/<user>/<num>`, the URL the
/// web client and the journal hand out for an item, mission, milestone or
/// project. [user] is the owner's login name and [num] the number in their
/// one per-user number space, so the pair is exactly what `GET /lookup`
/// takes.
data class TrackerLinkURL(val user: String, val num: Int) {
    companion object {
        /// The journal's own limit on a login name in a lookup.
        private const val USER_MAX = 64

        /// The link in [url] when it points at the journal at [journalBaseURL]
        /// (the session's server URL, as [chat.matron.android.auth.ServerURLValidator]
        /// normalised it), else `null`.
        ///
        /// Strict on the parts that decide whether a tap leaves the app:
        /// scheme, host and port must be the journal's (scheme and host
        /// case-insensitively, a default port matching an absent one), there
        /// is no user info, and the RAW path must be the journal's base path
        /// followed by exactly `/u/<user>/<num>` — no trailing slash, no
        /// extra segment, plain ASCII digits greater than zero. A query or a
        /// fragment does not change what the link names and is ignored.
        fun parse(url: String, journalBaseURL: String): TrackerLinkURL? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val base = runCatching { URI(journalBaseURL.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "http") return null
            if (scheme != base.scheme?.lowercase()) return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host ?: return null
            if (!host.equals(base.host ?: return null, ignoreCase = true)) return null
            if (effectivePort(uri) != effectivePort(base)) return null

            val basePath = (base.rawPath ?: "").trimEnd('/')
            val path = uri.rawPath ?: return null
            if (!path.startsWith("$basePath/u/")) return null
            val segments = path.removePrefix("$basePath/u/").split('/')
            if (segments.size != 2) return null
            val user = decode(segments[0])?.takeIf { it.isNotEmpty() && it.length <= USER_MAX && '/' !in it } ?: return null
            val digits = segments[1]
            if (digits.isEmpty() || digits.length > 10 || !digits.all { it in '0'..'9' }) return null
            val num = digits.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return TrackerLinkURL(user, num)
        }

        private fun effectivePort(uri: URI): Int = when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

        /// Percent-decodes one path segment. `URLDecoder` is a form decoder,
        /// so a literal `+` is protected first: in a path it is a plus sign,
        /// not a space.
        private fun decode(segment: String): String? =
            runCatching { URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrNull()
    }
}
