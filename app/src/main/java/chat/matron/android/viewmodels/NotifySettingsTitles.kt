package chat.matron.android.viewmodels

import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifyMuteDuration
import chat.matron.android.journal.NotifySettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/// The words Settings → Notifications and each chat's Notifications menu
/// use, in one place so they never disagree (and match the Apple apps).
object NotifySettingsTitles {
    fun title(mode: NotifySettings.Mode): String = when (mode) {
        NotifySettings.Mode.COORDINATOR -> "Coordinator mode"
        NotifySettings.Mode.ALL -> "Every session"
        NotifySettings.Mode.CUSTOM -> "Custom"
    }

    fun subtitle(mode: NotifySettings.Mode): String = when (mode) {
        NotifySettings.Mode.COORDINATOR -> "Only what lands in For you and anything else that needs you"
        NotifySettings.Mode.ALL -> "Every session's turns and anything that needs you"
        NotifySettings.Mode.CUSTOM -> "Your own choice of the events below"
    }

    fun title(event: NotifySettings.Event): String = when (event) {
        NotifySettings.Event.PROMPTS -> "Permission prompts & secret requests"
        NotifySettings.Event.QUESTIONS -> "Questions and items awaiting me"
        NotifySettings.Event.NOTICES -> "Things to read"
        NotifySettings.Event.COORDINATOR_DONE -> "Coordinator finished a turn"
        NotifySettings.Event.OTHER_DONE -> "Other sessions finished a turn"
        NotifySettings.Event.STOPPED -> "A session stopped or crashed mid-turn"
        NotifySettings.Event.ROOMS -> "Agent room messages"
        NotifySettings.Event.ACTIVITY -> "Routine activity (batched)"
    }

    fun title(level: NotifySettings.ConvoLevel): String = when (level) {
        NotifySettings.ConvoLevel.ALL -> "All"
        NotifySettings.ConvoLevel.NEEDS_ME -> "Needs me"
        NotifySettings.ConvoLevel.NONE -> "None"
    }

    fun title(level: NotifyDeviceLevel): String = when (level) {
        NotifyDeviceLevel.ALL -> "All of the above"
        NotifyDeviceLevel.NEEDS_ME -> "Needs-me only"
        NotifyDeviceLevel.OFF -> "Off"
    }

    fun title(duration: NotifyMuteDuration): String = when (duration) {
        NotifyMuteDuration.ONE_HOUR -> "Mute for 1 hour"
        NotifyMuteDuration.EIGHT_HOURS -> "Mute for 8 hours"
        NotifyMuteDuration.UNTIL_TOMORROW_MORNING -> "Mute until tomorrow 08:00"
    }

    /// A conversation with no level of its own.
    const val DEFAULT_LEVEL = "Default (follow mode)"

    const val PROMPTS_ALWAYS_ON = "Always on: an unanswered prompt blocks an agent."
    const val NO_COORDINATOR = "Until a Coordinator is set, Coordinator mode acts as Every session."
    const val SWITCH_MOVES_TO_CUSTOM = "Changing a switch moves you to Custom."
    const val UNSUPPORTED = "This server doesn't support notification settings yet."
    const val FOOTER =
        "Events that don't notify still update the app's lists and unread dots. " +
            "For quiet hours, use Do Not Disturb."

    /// Android does not receive push notifications yet, so "On this device"
    /// would otherwise read as if it already worked here.
    const val ANDROID_PUSH_NOTE =
        "This phone doesn't receive push notifications yet. Your choices above are saved on your " +
            "server and apply to your other devices now; this device's level applies once it does."

    /// One line for an overrides row: the level and the running mute,
    /// e.g. "Needs me · muted until 14:00".
    fun summary(state: ConvoNotifyState, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = buildList {
            state.level?.let { add(title(it)) }
            state.mutedUntil?.let { add("muted until ${time(it, nowMs, zone)}") }
        }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /// The time alone today, with the weekday otherwise.
    fun time(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at)
        return if (at.toLocalDate() == today) clock else "${DateTimeFormatter.ofPattern("EEE", locale).format(at)} $clock"
    }
}
