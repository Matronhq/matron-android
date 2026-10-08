package chat.matron.android.designsystem

import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.storage.LRUCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/// Markdown attachment preview: the pure half (which attachments qualify, how
/// the bytes become safe-to-render text, the per-session cache and the
/// open/replace/close state). The sheet itself is
/// `features/chat/MarkdownPreviewSheet.kt`.
object MarkdownPreview {
    /// Larger markdown keeps the download/open behaviour.
    const val MAX_BYTES: Long = 2L * 1024 * 1024

    private val markdownMimes = setOf("text/markdown", "text/x-markdown")
    private val markdownExtensions = listOf(".md", ".markdown", ".mdown")
    /// Mimes a producer sends when it does not know better: a `.md` name
    /// under one of these is still markdown.
    private val genericMimes = setOf("", "text/plain", "application/octet-stream")

    /// Whether tapping this attachment opens the preview. [size] `null` (or
    /// not positive) means unknown: it qualifies, and the loader falls back
    /// to the download path if the bytes turn out larger than [MAX_BYTES].
    fun isPreviewableMarkdown(name: String, mime: String?, size: Long?): Boolean {
        if (size != null && size > MAX_BYTES) return false
        val m = mime.orEmpty().substringBefore(';').trim().lowercase()
        if (m in markdownMimes) return true
        val lower = name.trim().lowercase()
        return m in genericMimes && markdownExtensions.any { lower.endsWith(it) }
    }

    /// Tracker attachments carry `size: 0` when the producer sent none.
    fun isPreviewableMarkdown(attachment: TrackerAttachment): Boolean =
        isPreviewableMarkdown(attachment.name, attachment.mime, attachment.size.takeIf { it > 0 })

    /// UTF-8, invalid sequences replaced (the JDK decoder substitutes U+FFFD),
    /// a leading byte-order mark dropped.
    fun decode(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    /// Link schemes the preview keeps tappable; they open the way chat links
    /// do. Everything else (relative paths, `#anchor`, `file:`, `javascript:`)
    /// renders as its label.
    private val allowedLinkSchemes = setOf("http", "https", "mailto", "matron")

    fun isAllowedLink(url: String): Boolean {
        val trimmed = url.trim()
        val colon = trimmed.indexOf(':')
        if (colon <= 0) return false
        val scheme = trimmed.substring(0, colon)
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return false
        return scheme.lowercase() in allowedLinkSchemes
    }

    private val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)]*)\\)")
    private val linkRegex = Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)")
    /// The renderer's own fence rule ([MarkdownAttributed]): a line starting
    /// with ``` (after any indent) opens a code block and the next such line
    /// closes it. Only what the renderer shows as code is left unsanitised.
    private val fenceRegex = Regex("^\\s*```")

    /// Rewrites [raw] so rendering it can never fetch anything: every image
    /// (`![alt](url)`, remote or relative) becomes its alt text, or the URL
    /// text when the alt is empty; links whose target is not http(s), mailto
    /// or matron become their plain label. Fenced code blocks and inline
    /// code spans are left untouched — they render literally anyway.
    fun sanitise(raw: String): String {
        val out = StringBuilder(raw.length)
        var inFence = false
        val lines = raw.split('\n')
        lines.forEachIndexed { index, line ->
            when {
                fenceRegex.containsMatchIn(line) -> { inFence = !inFence; out.append(line) }
                inFence -> out.append(line)
                else -> out.append(sanitiseLine(line))
            }
            if (index < lines.lastIndex) out.append('\n')
        }
        return out.toString()
    }

    /// One line outside a fence: inline code spans pass through, the prose
    /// between them is rewritten. A span is the renderer's: one backtick to
    /// the next backtick; an unmatched backtick is plain text.
    private fun sanitiseLine(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        var proseStart = 0
        while (i < line.length) {
            if (line[i] != '`') { i++; continue }
            val close = line.indexOf('`', i + 1)
            if (close == -1) break
            out.append(rewriteProse(line.substring(proseStart, i)))
            out.append(line, i, close + 1)
            i = close + 1
            proseStart = i
        }
        out.append(rewriteProse(line.substring(proseStart)))
        return out.toString()
    }

    private fun rewriteProse(text: String): String {
        if (text.isEmpty()) return text
        val noImages = imageRegex.replace(text) { m ->
            val alt = m.groupValues[1]
            val target = m.groupValues[2].trim().substringBefore(' ')
            literal(alt.ifBlank { target })
        }
        // Repeat until stable: a pass can expose a link that an earlier,
        // overlapping match hid. [literal] output never forms a new one.
        var current = noImages
        repeat(4) {
            val next = linkRegex.replace(current) { m ->
                if (isAllowedLink(m.groupValues[2])) m.value else literal(m.groupValues[1])
            }
            if (next == current) return current
            current = next
        }
        return current
    }

    /// Text that must render as-is: a zero-width space after every `]` so
    /// the renderer's `[label](url)` pattern (which needs `](` adjacent) can
    /// never re-form a link out of it — the renderer has no backslash escapes.
    private fun literal(text: String): String = text.replace("]", "]\u200B")

    /// What the loader produced.
    sealed interface Content {
        data object Loading : Content
        data class Loaded(val raw: String) : Content {
            /// What the rendered view shows (images and relative links
            /// defused); the Source view and Copy/Share use [raw].
            val rendered: String by lazy { sanitise(raw) }
        }
        /// The bytes were over [MAX_BYTES]: fall back to download/open.
        data object TooLarge : Content
        /// [expired]: the server said 404 — retrying will not help.
        data class Failed(val expired: Boolean) : Content
    }

    /// Fetches through [fetch] (the authenticated media path) unless [cache]
    /// already holds the text for [key].
    suspend fun load(key: String, cache: MarkdownPreviewCache, fetch: suspend () -> MediaFetchOutcome): Content {
        cache[key]?.let { return Content.Loaded(it) }
        return when (val outcome = fetch()) {
            is MediaFetchOutcome.Data ->
                if (outcome.bytes.size > MAX_BYTES) Content.TooLarge
                else decode(outcome.bytes).also { cache[key] = it }.let { Content.Loaded(it) }
            MediaFetchOutcome.NotFound -> Content.Failed(expired = true)
            MediaFetchOutcome.Failure -> Content.Failed(expired = false)
        }
    }
}

/// Decoded markdown per blob (keyed by its media URL, which carries the blob
/// ref), kept for the app session. Bounded so a run of large files cannot
/// pin memory.
class MarkdownPreviewCache(limit: Int = 16) {
    private val cache = LRUCache<String, String>(limit)

    operator fun get(key: String): String? = synchronized(cache) { cache[key] }
    operator fun set(key: String, value: String) = synchronized(cache) { cache[key] = value }

    companion object {
        val shared = MarkdownPreviewCache()
    }
}

/// The attachment a preview is showing. [key] is the media URL (unique per
/// blob ref) and doubles as the cache key.
data class MarkdownPreviewTarget(val key: String, val name: String)

/// Which preview is open on a screen. Opening another replaces it.
/// Compose snapshot state, so a screen holding one recomposes on change.
class MarkdownPreviewState {
    var target: MarkdownPreviewTarget? by mutableStateOf(null)
        private set

    fun open(target: MarkdownPreviewTarget) { this.target = target }
    fun close() { target = null }
}
