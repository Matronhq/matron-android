package chat.matron.android.chat

import chat.matron.android.models.BotIdentity
import java.time.Instant
import java.time.ZoneId

data class ChatSummary(
    val id: String,
    val title: String,
    val bot: BotIdentity,
    /// `null` when the conversation's timeline hasn't been hydrated yet. UI
    /// hides the relative-time label and grouping when null.
    val lastActivity: Instant?,
    val unreadCount: Int,
    /// One-line preview of the newest message. Empty when the conversation has
    /// no messages yet — rows hide the preview line rather than showing a blank.
    val snippet: String = "",
    /// The parent conversation's id when this is a subagent child chat, else
    /// `null`. Immutable server-side. Children never appear in the main chat
    /// list — reachable only through their parent's running-subagent strip.
    val parentConvoID: String? = null,
)

/// A subagent child conversation as surfaced in its parent's running-subagent
/// strip and the sub-chat switcher menu. Deliberately smaller than
/// [ChatSummary]: the strip needs only identity, a label, and whether the
/// subagent is still running.
data class SubChatSummary(
    val id: String,
    val title: String,
    /// `true` while the subagent is active (`session_state == "running"`),
    /// `false` once finished (`"done"`).
    val isRunning: Boolean,
)

enum class ChatRecencyGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_SEVEN_DAYS("Last 7 days"),
    EARLIER("Earlier"),
    /// Used for chats whose timeline isn't hydrated yet so we don't stamp them
    /// with a misleading "Today" label.
    NO_ACTIVITY("No recent activity");

    companion object {
        fun bucket(
            date: Instant?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): ChatRecencyGroup {
            if (date == null) return NO_ACTIVITY
            val nowDay = now.atZone(zone).toLocalDate()
            val day = date.atZone(zone).toLocalDate()
            if (day == nowDay) return TODAY
            if (day == nowDay.minusDays(1)) return YESTERDAY
            val sevenDaysAgo = now.minus(java.time.Duration.ofDays(7))
            return if (!date.isBefore(sevenDaysAgo)) LAST_SEVEN_DAYS else EARLIER
        }
    }
}
