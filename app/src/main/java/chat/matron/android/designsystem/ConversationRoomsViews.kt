package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ConversationRoom
import chat.matron.android.models.RoomSessionState

/// The words of a chat header's rooms control, pure so each is a plain test.
/// Port of matron-apple's `ConversationRoomsFormat`.
object ConversationRoomsFormat {
    /// "Rooms · 2"; `null` for a chat that is in no room (no control).
    fun label(count: Int): String? = if (count > 0) "Rooms · $count" else null

    fun accessibilityLabel(count: Int): String =
        "$count agent chat room${if (count == 1) "" else "s"}"

    /// What a screen reader reads for one room in the list: state, then title.
    fun rowAccessibilityLabel(room: ConversationRoom): String = "${room.state.label}, ${room.title}"

    /// The sheet's title over the list of several rooms.
    const val LIST_TITLE = "Rooms"

    /// A room's title: the chat's live list first, then the room's own
    /// stored title (a room opened from another room's timeline is not in
    /// the list), then a plain word.
    fun title(roomID: String, rooms: List<ConversationRoom>, stored: String?): String =
        rooms.firstOrNull { it.id == roomID }?.title ?: stored ?: "Room"
}

/// Running green / waiting amber / done grey.
@Composable
fun RoomStateDot(state: RoomSessionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        RoomSessionState.RUNNING -> Color(0xFF34C759)
        RoomSessionState.WAITING -> Color(0xFFFF9500)
        RoomSessionState.DONE -> Color(0xFF8E8E93)
    }
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/// The chat header's "Rooms · n" chip, on the line under the title. Draws
/// nothing for a chat that is in no room.
@Composable
fun RoomsChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = ConversationRoomsFormat.label(count) ?: return
    val a11y = ConversationRoomsFormat.accessibilityLabel(count)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .clickable(onClickLabel = "Show the agent chat rooms this conversation is in", onClick = onClick)
            .semantics { contentDescription = a11y; role = Role.Button }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/// Every room a conversation is in, as rows: the rooms sheet's list when
/// there is more than one. A tap opens the room.
@Composable
fun ConversationRoomsList(rooms: List<ConversationRoom>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    // Scrolls: a conversation can be in more rooms than fit under the header.
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        rooms.forEachIndexed { index, room ->
            if (index > 0) HorizontalDivider()
            val a11y = ConversationRoomsFormat.rowAccessibilityLabel(room)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(room.id) }
                    .clearAndSetSemantics {
                        contentDescription = a11y
                        role = Role.Button
                        onClick { onOpen(room.id); true }
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RoomStateDot(room.state)
                Text(
                    room.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
