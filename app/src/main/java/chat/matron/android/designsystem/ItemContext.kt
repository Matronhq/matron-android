package chat.matron.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ItemHandover
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerItem
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

// The item detail's context block (so the reader can see which
// conversation an item is connected to): under the "#N · Kind" line, the
// mission the item belongs to and the one conversation that owns it — the
// conversation it was filed from. Pure mapping here, so the rules are pinned
// by `ItemContextTest` without a view.

/// The tappable owner row: the conversation id to open and its one-line label.
data class ItemContextConversation(val convoID: String, val label: String)

/// Everything the context block draws. [missionLabel] is `null` when the
/// item has no mission; [missionID] opens the mission page. [owner] is the
/// conversation that filed the item, `null` for a granted item (no origin)
/// or when the detail is shown inside that very conversation.
data class ItemContext(
    val missionID: String?,
    val missionLabel: String?,
    val owner: ItemContextConversation?,
    val handover: ItemHandover? = null,
) {
    val isEmpty: Boolean get() = missionLabel == null && owner == null && handover == null
}

/// The label of a conversation nobody could name: not cached on this device
/// and untitled on the journal.
const val ITEM_CONTEXT_GENERIC_CONVERSATION = "Conversation"

/// `#61 Launch` — the mission's short name, else its title — from the local
/// mission row; `Mission #61` while that row is not cached (not loaded yet,
/// or invisible to this user).
fun itemContextMissionLabel(num: Int, mission: Mission?): String =
    if (mission == null) "Mission #$num" else "#$num ${mission.displayName}"

/// The context block for [item].
///
/// - Mission row whenever the item carries a mission number, labelled from
///   [mission] (the cached row for `item.missionID`, `null` when not cached).
/// - Owner row whenever the item has an origin conversation, EXCEPT when the
///   detail is shown inside it ([currentConvoID]). Labelled with the local
///   label ([originLocalLabel], box + title) when this device has the
///   conversation, else the journal's `origin_convo_title`, else
///   [ITEM_CONTEXT_GENERIC_CONVERSATION] — never silently missing.
fun itemContext(
    item: TrackerItem,
    currentConvoID: String?,
    originLocalLabel: String?,
    mission: Mission?,
): ItemContext {
    val missionID = item.missionID
    val missionLabel = item.missionNum?.let { num -> itemContextMissionLabel(num, mission?.takeIf { it.id == missionID }) }
    val originID = item.originConvoID
    val owner = if (originID.isEmpty() || originID == currentConvoID) {
        null
    } else {
        ItemContextConversation(originID, originLocalLabel ?: item.originConvoTitle ?: ITEM_CONTEXT_GENERIC_CONVERSATION)
    }
    return ItemContext(missionID = missionID, missionLabel = missionLabel, owner = owner, handover = item.handover)
}

/// The mission and owner rows truncate; the pending offer wraps so its
/// waiting state stays readable on narrow screens.
@Composable
internal fun ItemContextBlock(
    context: ItemContext,
    onOpenMission: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val handover = context.handover
    var now by remember(handover) { mutableStateOf(Instant.now()) }
    LaunchedEffect(handover) {
        val expiresAt = handover?.expiresAt ?: return@LaunchedEffect
        // Round up: Instant has finer precision than delay's milliseconds.
        delay((Duration.between(Instant.now(), expiresAt).toMillis() + 1).coerceAtLeast(0))
        now = Instant.now()
    }
    val handoverCaption = handover?.caption(now)
    if (context.missionLabel == null && context.owner == null && handoverCaption == null) return
    val missionID = context.missionID
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        context.missionLabel?.let { label ->
            ItemContextRow(
                icon = MissionGlyph.icon(), text = label, clickLabel = "Open mission",
                onClick = missionID?.let { id -> { onOpenMission(id) } },
            )
        }
        context.owner?.let { owner ->
            ItemContextRow(icon = Icons.Outlined.Forum, text = owner.label, clickLabel = "Open conversation") {
                onOpenConversation(owner.convoID)
            }
        }
        handoverCaption?.let { caption ->
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ItemContextRow(icon: ImageVector, text: String, clickLabel: String, onClick: (() -> Unit)?) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val tappable = if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier
    Row(
        tappable.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
