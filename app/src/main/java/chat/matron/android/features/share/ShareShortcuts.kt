package chat.matron.android.features.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.matron.android.R
import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.MatronDebug

/// Offers the most recent conversations as direct-share targets in the
/// system share sheet, so sharing into one of them is one tap. Android's
/// counterpart of matron-apple's `ShareTargetsPublisher`: where iOS leaves
/// the list in a file for the share extension, Android takes it as sharing
/// shortcuts (category [CATEGORY], declared in `res/xml/share_targets.xml`).
///
/// The share screen itself reads the chat list straight from the app's local
/// store; these shortcuts only seed the system sheet.
object ShareShortcuts {
    /// The share-target category in `res/xml/share_targets.xml`.
    const val CATEGORY = "chat.matron.android.category.SHARE_TARGET"

    /// Names the conversation on a shortcut's own intent (opened from a
    /// surface other than the share sheet, which passes the shortcut id).
    const val EXTRA_CONVERSATION_ID = "chat.matron.android.extra.CONVERSATION_ID"

    private const val PREFIX = "convo:"

    /// How many conversations to offer. The system shows only the first few;
    /// more just churns its ranking.
    const val COUNT = 4

    fun shortcutID(conversationID: String): String = PREFIX + conversationID

    fun conversationID(shortcutID: String): String? =
        shortcutID.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotEmpty() }

    /// The conversations to offer, most relevant first: the share screen's
    /// own order, cut to [count].
    fun targets(summaries: List<ChatSummary>, coordinatorID: String?, count: Int = COUNT): List<ShareTarget> =
        ShareTargets.from(summaries, coordinatorID, limit = count)

    /// Replaces the published shortcuts with [targets]. An empty list means
    /// the chat list has not loaded yet, not that there is nothing to offer,
    /// so it changes nothing.
    fun publish(context: Context, targets: List<ShareTarget>) {
        if (targets.isEmpty()) return
        val icon = IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val shortcuts = targets.mapIndexed { rank, target ->
            ShortcutInfoCompat.Builder(context, shortcutID(target.id))
                .setShortLabel(target.title.take(25).ifBlank { "Untitled" })
                .setLongLabel(target.title.ifBlank { "Untitled" })
                .setIcon(icon)
                .setRank(rank)
                .setLongLived(true)
                .setCategories(setOf(CATEGORY))
                // Not excluded from the launcher: a shortcut hidden there is
                // not offered to the share sheet either. From the launcher's
                // long-press menu it opens the share screen on that chat,
                // ready for a message.
                .setIntent(
                    Intent(context, ShareActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(EXTRA_CONVERSATION_ID, target.id),
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
            .onSuccess { if (!it) MatronDebug.breadcrumb("share shortcuts publish refused (rate limit)") }
            .onFailure { MatronDebug.breadcrumb("share shortcuts publish failed: $it") }
    }

    /// Removes them, for sign-out: the next account must not be offered the
    /// last one's conversations.
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
    }
}
