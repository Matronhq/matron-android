package chat.matron.android.features.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyMuteDuration
import chat.matron.android.journal.NotifySettings
import chat.matron.android.viewmodels.ConvoNotifyState
import chat.matron.android.viewmodels.NotifySettingsStore
import chat.matron.android.viewmodels.NotifySettingsTitles

/// The chat menu's "Notifications" entry: a bell, or a bell-off while
/// nothing from this conversation pushes (level None, or a running mute).
fun convoNotifyIcon(state: ConvoNotifyState): ImageVector =
    if (state.isSilenced) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications

/// One row of a conversation's Notifications menu, in the order it lists
/// them: the four level choices (the conversation's own level, or Default to
/// follow the mode), then Unmute while a mute runs, then the mute choices.
/// Pure so the menu's content is a plain test.
sealed interface ConvoNotifyMenuRow {
    data class Level(val level: NotifySettings.ConvoLevel?, val title: String, val selected: Boolean) : ConvoNotifyMenuRow
    data class Unmute(val title: String) : ConvoNotifyMenuRow
    data class Mute(val duration: NotifyMuteDuration, val title: String) : ConvoNotifyMenuRow

    companion object {
        fun rows(state: ConvoNotifyState, nowMs: Long): List<ConvoNotifyMenuRow> = buildList {
            NotifySettings.ConvoLevel.entries.forEach { add(Level(it, NotifySettingsTitles.title(it), state.level == it)) }
            add(Level(null, NotifySettingsTitles.DEFAULT_LEVEL, state.level == null))
            state.mutedUntil?.let { add(Unmute("Unmute (muted until ${NotifySettingsTitles.time(it, nowMs)})")) }
            NotifyMuteDuration.entries.forEach { add(Mute(it, NotifySettingsTitles.title(it))) }
        }
    }
}

/**
 * A conversation's Notifications menu: its own level (All / Needs me / None,
 * or Default to follow the mode) and a mute. Opened from the chat's "⋮"
 * menu. Writes go through [NotifySettingsStore], which shows them at once
 * and takes them back if the journal refuses. Port of matron-apple's
 * `ConvoNotifyMenu`.
 */
@Composable
fun ConvoNotifyMenu(
    store: NotifySettingsStore,
    convoID: String,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val view by store.view.collectAsStateWithLifecycle()
    val now by store.clock.collectAsStateWithLifecycle()
    val state = store.state(convoID, view, now)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag("convo-notify-menu")) {
        var lastWasLevel = true
        ConvoNotifyMenuRow.rows(state, now).forEach { row ->
            if (row !is ConvoNotifyMenuRow.Level && lastWasLevel) {
                HorizontalDivider()
                lastWasLevel = false
            }
            when (row) {
                is ConvoNotifyMenuRow.Level -> DropdownMenuItem(
                    text = { Text(row.title) },
                    leadingIcon = { if (row.selected) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = {
                        onDismiss()
                        if (!row.selected) store.post(NotifyChange.ConvoLevel(convoID, row.level))
                    },
                )
                is ConvoNotifyMenuRow.Unmute -> DropdownMenuItem(
                    text = { Text(row.title) },
                    leadingIcon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    onClick = { onDismiss(); store.post(NotifyChange.ConvoMute(convoID, null)) },
                )
                is ConvoNotifyMenuRow.Mute -> DropdownMenuItem(
                    text = { Text(row.title) },
                    onClick = { onDismiss(); store.post(store.muteChange(convoID, row.duration)) },
                )
            }
        }
    }
}
