package chat.matron.android.journal

import java.net.URI
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

// What a tracker link points at, for the chip shown beside it in a message
// body. Agents sometimes link a real number that is not the one they meant,
// and a bare `#412` gives the reader no way to tell, so a tracker link that
// does not already name its target shows the target's kind and title, and a
// link whose text names something else stands out. The web client does the same, and the bridge refuses an
// agent's link whose written title does not match, with the same rule.

/// The owner's login name and the number in their one per-user number space:
/// what `GET /lookup` takes.
data class TrackerLinkTarget(val user: String, val num: Int)

/// The number and title written in a link's text: `#12 Title`,
/// `item #12: Title`, or just `Title` (no number).
data class WrittenLink(val num: Int?, val title: String)

/// What a lookup said about a link's number.
sealed interface TrackerLinkInfo {
    data class Found(val kind: NumberKind, val title: String?) : TrackerLinkInfo

    /// The journal answered: no such number, or not visible to this user.
    data object Missing : TrackerLinkInfo

    /// The journal could not be asked. Neither this nor [Missing] is cached.
    data object Failed : TrackerLinkInfo
}

object TrackerLinkTitleMatch {
    private val stopwords = setOf(
        "a", "an", "the", "of", "to", "for", "and", "or", "on", "in", "at", "by", "with", "from",
        "is", "it", "this", "that", "item", "one", "new",
    )
    /// Words a link's text uses to point rather than to name: "click here",
    /// "read more", "see this" claim nothing about the target.
    private val generic = setOf("here", "click", "read", "more", "see", "link", "open", "view", "details", "this", "that", "it")
    private val marks = Regex("\\p{M}+")
    private val ellipsis = Regex("(\\.\\.\\.|…)\\s*$")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val textRe = Regex(
        "^(?:(?:item|mission|milestone|project|question|task|decision|notice)\\s+)?#([1-9][0-9]{0,9})(?![\\p{L}\\p{N}])(.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /// An address in a title is one word, compared whole: its pieces (https,
    /// the host, the path's words) would otherwise match any other title
    /// that carries an address on the same site.
    private val address = Regex("[a-z][a-z0-9+.-]*://[^\\s<>()\"']+")
    private val addressEnd = Regex("[.,;:!?/]+$")

    /// The words of a title, and whether it ended with an ellipsis (only
    /// then may an address at its end be a cut-off prefix of the real one).
    private data class Words(val list: List<String>, val cut: Boolean)

    private fun words(text: String): Words {
        val full = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(marks, "").lowercase()
        val plain = full.replace(ellipsis, "")
        val out = mutableListOf<String>()
        fun addPlain(s: String) = s.split(separators).filterTo(out) { it.isNotEmpty() }
        var last = 0
        for (m in address.findAll(plain)) {
            addPlain(plain.substring(last, m.range.first))
            out += m.value.replace(addressEnd, "")
            last = m.range.last + 1
        }
        addPlain(plain.substring(last))
        return Words(out, plain != full)
    }

    fun tokens(text: String): List<String> = words(text).list

    private fun isAddress(t: String) = "://" in t

    private fun sameWord(a: String, b: String): Boolean =
        a == b ||
            // Addresses match whole: ".../pull/1" is not ".../pull/10".
            (!isAddress(a) && !isAddress(b) && minOf(a.length, b.length) >= 3 && (a.startsWith(b) || b.startsWith(a)))

    /// Does a title written in a link name this record? A prefix (the last
    /// word possibly cut short), an abbreviation, or a paraphrase that keeps
    /// most of the words passes; a title about something else does not.
    fun titleMatches(written: String, actual: String): Boolean {
        val written = words(written)
        val w = written.list
        val a = tokens(actual)
        if (w.isEmpty() || a.isEmpty()) return false
        fun cutShort(t: String, x: String) = x.startsWith(t) && (!isAddress(t) || written.cut)
        if (w.size <= a.size && w.withIndex().all { (i, t) -> t == a[i] || (i == w.size - 1 && cutShort(t, a[i])) }) return true
        val content = w.filter { it !in stopwords }
        val words = content.ifEmpty { w }
        val hits = words.count { t -> a.any { sameWord(t, it) } }
        return hits >= minOf(2, words.size) && hits.toDouble() / words.size >= 0.6
    }

    /// Does a link's text say which record it means? A number does, and so
    /// do two or more words beyond the stopwords and pointing words.
    fun claims(written: WrittenLink): Boolean =
        written.num != null || tokens(written.title).count { it !in stopwords && it !in generic } >= 2

    fun writtenLink(text: String): WrittenLink {
        val plain = text.replace(Regex("[*_`~]+"), " ").trim()
        val match = textRe.find(plain)
        val title = (match?.groupValues?.get(2) ?: plain)
            .trim()
            .replace(Regex("^[\\s:–—\\-·|,.(]+"), "")
            .replace(Regex("\\)+$"), "")
            .trim()
            .replace(Regex("^[\"'“‘«]+|[\"'”’»]+$"), "")
            .trim()
        return WrittenLink(match?.groupValues?.get(1)?.toIntOrNull(), title)
    }
}

/// One session's tracker link lookups. A found target is kept for
/// [TTL_MILLIS], so a chat full of links to the same number asks once;
/// concurrent asks for one number share a request; a missing number and a
/// failure are not kept, so the next render asks again.
class TrackerLinkTitles(
    private val journalBaseURL: String,
    private val sessionUser: String,
    private val lookup: suspend (user: String, num: Int) -> NumberLookup,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val TTL_MILLIS = 5 * 60_000L
        private const val MAX_ENTRIES = 500
        private val inApp = Regex("^matron://(item|mission|project)/([1-9][0-9]{0,9})$", RegexOption.IGNORE_CASE)
    }

    /// A shared lookup. Completes with `null` when the coroutine running it
    /// was cancelled, so whoever waited on it asks again.
    private class Entry(val answer: CompletableDeferred<TrackerLinkInfo?>, val until: Long)

    private val entries = LinkedHashMap<TrackerLinkTarget, Entry>()

    /// The number a link names: a `/u/<name>/<n>` link on this session's
    /// journal, or an in-app `matron://item|mission|project/<n>` link (read
    /// against the signed-in user). `null` for anything else.
    fun target(url: String): TrackerLinkTarget? {
        TrackerLinkURL.parse(url, journalBaseURL)?.let { return TrackerLinkTarget(it.user, it.num) }
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        val match = inApp.matchEntire(url.trim()) ?: return null
        val num = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return TrackerLinkTarget(sessionUser, num)
    }

    suspend fun info(target: TrackerLinkTarget): TrackerLinkInfo {
        while (true) {
            var owner = false
            val answer = synchronized(entries) {
                val cached = entries[target]
                if (cached != null && cached.until > now()) return@synchronized cached.answer
                if (entries.size >= MAX_ENTRIES) entries.keys.firstOrNull()?.let { entries.remove(it) }
                val fresh = CompletableDeferred<TrackerLinkInfo?>()
                entries[target] = Entry(fresh, now() + TTL_MILLIS)
                owner = true
                fresh
            }
            if (!owner) {
                // null: the asker running it went away (its composable left)
                // before an answer; ask again rather than report a failure.
                val got = answer.await()
                if (got != null) return got
                continue
            }
            val result = try {
                val hit = lookup(target.user, target.num)
                TrackerLinkInfo.Found(hit.kind, hit.title)
            } catch (cancel: CancellationException) {
                forget(target, answer)
                answer.complete(null)
                throw cancel
            } catch (e: JournalApiError.NotFound) {
                TrackerLinkInfo.Missing
            } catch (e: Exception) {
                TrackerLinkInfo.Failed
            }
            // Only a found target is kept: a missing number can be made a
            // moment later, and a failure is worth asking again.
            if (result !is TrackerLinkInfo.Found) forget(target, answer)
            answer.complete(result)
            return result
        }
    }

    private fun forget(target: TrackerLinkTarget, answer: CompletableDeferred<TrackerLinkInfo?>) {
        synchronized(entries) { if (entries[target]?.answer === answer) entries.remove(target) }
    }
}
