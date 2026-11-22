package chat.matron.android.features.share

import chat.matron.android.chat.ChatSummary
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/// A conversation the share screen can send into. Ported from matron-apple's
/// `ShareTarget`.
data class ShareTarget(
    val id: String,
    val title: String,
    /// A short second line, such as the name of the box the conversation runs
    /// on. Null when there is nothing worth saying.
    val detail: String? = null,
    val isCoordinator: Boolean = false,
    val lastActivity: Instant? = null,
) {
    /// The second line the picker shows: the Coordinator is labelled as such,
    /// unless its title already says so.
    val shownDetail: String?
        get() = when {
            !isCoordinator -> detail
            title.contains("coordinator", ignoreCase = true) -> detail
            else -> "Coordinator"
        }
}

object ShareTargets {
    /// How many conversations the picker offers. The share screen is for
    /// reaching something recent; anything older is found in the app.
    const val LIMIT = 300

    /// The app's own chat list as share targets: top-level conversations
    /// only (a subagent's child chat is not somewhere to send things), in
    /// [ordered] order. Port of matron-apple's `ShareTargetsPublisher.targets`.
    fun from(summaries: List<ChatSummary>, coordinatorID: String?, limit: Int = LIMIT): List<ShareTarget> =
        ordered(
            summaries.filter { it.parentConvoID == null }.map { summary ->
                ShareTarget(
                    id = summary.id,
                    title = summary.title.ifBlank { "Untitled" },
                    detail = summary.boxName,
                    isCoordinator = summary.id == coordinatorID,
                    lastActivity = summary.lastActivity,
                )
            },
            limit,
        )

    /// The picker's order: the Coordinator first, then newest activity first,
    /// conversations with no activity last. Ties keep a stable order by id so
    /// the list does not shuffle between two loads.
    fun ordered(targets: List<ShareTarget>, limit: Int = LIMIT): List<ShareTarget> =
        targets.sortedWith(
            compareByDescending<ShareTarget> { it.isCoordinator }
                .thenBy { it.lastActivity == null }
                .thenByDescending { it.lastActivity }
                .thenBy { it.id },
        ).take(limit)

    /// Case- and accent-insensitive match on the title, every word of the
    /// query required. A blank query matches everything.
    fun filter(targets: List<ShareTarget>, query: String): List<ShareTarget> {
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::folded)
        if (words.isEmpty()) return targets
        return targets.filter { target ->
            val title = folded(target.title)
            words.all { title.contains(it) }
        }
    }

    private val marks = Regex("\\p{Mn}+")

    private fun folded(text: String): String =
        marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}
