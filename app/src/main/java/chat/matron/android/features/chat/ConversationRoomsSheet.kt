package chat.matron.android.features.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ConversationRoomsFormat
import chat.matron.android.designsystem.ConversationRoomsList
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.models.ConversationRoom
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.SubChatStripViewModel

/// What a chat needs to offer its rooms: the live list, and the view models
/// for one room's (or one of its subagents') read-only timeline. `null` on
/// [ChatScreen] (previews, tests) offers no rooms control.
class ConversationRoomsHost(
    val rooms: kotlinx.coroutines.flow.StateFlow<List<ConversationRoom>>,
    /// A room's timeline VM and its own subagent strip.
    val room: (roomID: String) -> Pair<ChatViewModel, SubChatStripViewModel>,
    /// A subagent opened from a room's timeline: its timeline VM and the
    /// room's strip (its siblings).
    val subagent: (childID: String, roomID: String) -> Pair<ChatViewModel, SubChatStripViewModel>,
)

/// The rooms sheet's own stack: which page shows, and what Back does.
/// Pure so the rules are plain tests. Port of the navigation half of
/// matron-apple's `ConversationRoomsSheet`.
data class RoomsSheetPath(
    /// The room the sheet opened straight on, or `null` when it opened on the
    /// list. Fixed when the sheet appears: a room arriving or leaving while
    /// it is up must not swap the page under the reader.
    val soleRoomID: String?,
    val pages: List<Page> = emptyList(),
) {
    data class Page(val id: String, val isRoom: Boolean, val roomID: String) {
        companion object {
            fun room(id: String) = Page(id, isRoom = true, roomID = id)
        }
    }

    /// The page on top: the last pushed one, else the sole room, else `null`
    /// for the list.
    val top: Page? get() = pages.lastOrNull() ?: soleRoomID?.let(Page::room)

    /// Back from [top] goes somewhere inside the sheet (otherwise Back
    /// closes it).
    val canGoBack: Boolean get() = pages.isNotEmpty()

    fun openRoom(id: String) = copy(pages = pages + Page.room(id))

    /// A subtask card or the switcher opening [childID] from [top]. From a
    /// room the subagent goes on top of it, so Back returns to the room;
    /// from a subagent it replaces that sibling, so switching between
    /// siblings does not grow the stack.
    fun openSubagent(childID: String): RoomsSheetPath {
        val current = top ?: return this
        val page = Page(childID, isRoom = false, roomID = current.roomID)
        if (current.id == childID) return this
        return if (current.isRoom) {
            copy(pages = pages + page)
        } else {
            copy(pages = pages.dropLast(1) + page)
        }
    }

    fun back() = copy(pages = pages.dropLast(1))

    companion object {
        /// The room the sheet opens straight on: the only one there is.
        fun soleRoomID(rooms: List<ConversationRoom>): String? = rooms.singleOrNull()?.id
    }
}

/// The agent-chat rooms a conversation is in, as a bottom sheet over its
/// chat. One room opens straight on its timeline; several open on a list,
/// each opening its timeline inside the sheet. The timeline is read-only;
/// "Open as chat" leaves the sheet for the room's own chat, which has a
/// composer. Port of matron-apple's `ConversationRoomsSheet`.
@Composable
fun ConversationRoomsSheet(
    rooms: List<ConversationRoom>,
    host: ConversationRoomsHost,
    onDone: () -> Unit,
    /// Closes the sheet, then opens the room (or a spawned session tapped
    /// in a room's timeline) as a chat on the stack under it.
    onOpenConversation: (String) -> Unit,
) {
    val initialSole = remember { RoomsSheetPath.soleRoomID(rooms) }
    var path by remember { mutableStateOf(RoomsSheetPath(soleRoomID = initialSole)) }
    BackHandler(enabled = path.canGoBack) { path = path.back() }
    val top = path.top

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).testTag("rooms.sheet")) {
        if (top == null) {
            SheetHeader(
                title = ConversationRoomsFormat.LIST_TITLE,
                onBack = null,
                onOpenAsChat = null,
                onDone = onDone,
            )
            HorizontalDivider()
            // The space left under the header, so the list scrolls inside it.
            ConversationRoomsList(
                rooms = rooms,
                onOpen = { path = path.openRoom(it) },
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            key(top.id) {
                if (top.isRoom) {
                    val (chatVM, stripVM) = remember(top.id) { host.room(top.id) }
                    SheetHeader(
                        title = ConversationRoomsFormat.title(top.id, rooms, stored = null),
                        onBack = if (path.canGoBack) ({ path = path.back() }) else null,
                        onOpenAsChat = { onOpenConversation(top.id) },
                        onDone = onDone,
                    )
                    HorizontalDivider()
                    RoomTimelinePage(
                        chatVM = chatVM,
                        stripVM = stripVM,
                        onOpenChild = { path = path.openSubagent(it) },
                        onOpenConversation = onOpenConversation,
                    )
                } else {
                    val (chatVM, stripVM) = remember(top.id) { host.subagent(top.id, top.roomID) }
                    SubChatView(
                        chatVM = chatVM,
                        stripVM = stripVM,
                        childID = top.id,
                        fallbackTitle = "Subagent",
                        onBack = { path = path.back() },
                        onSwitchTo = { path = path.openSubagent(it) },
                        onOpenConversation = onOpenConversation,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String,
    onBack: (() -> Unit)?,
    onOpenAsChat: (() -> Unit)?,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            TextButton(onClick = onDone) { Text("Done") }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (onOpenAsChat != null) {
            TextButton(onClick = onOpenAsChat, modifier = Modifier.testTag("rooms.openAsChat")) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("Open as chat", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/// One room's read-only timeline: the shared [TimelineList] with no
/// composer. Reading it marks the room read — a room is a top-level
/// conversation with an unread count of its own.
@Composable
private fun RoomTimelinePage(
    chatVM: ChatViewModel,
    stripVM: SubChatStripViewModel,
    onOpenChild: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    var previewModel by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(chatVM, stripVM) {
        val chatGeneration = chatVM.observationGeneration + 1
        stripVM.start()
        val stripGeneration = stripVM.observationGeneration
        onDispose {
            chatVM.stop(chatGeneration)
            chatVM.resetHistoryWindow(chatGeneration)
            stripVM.stop(stripGeneration)
        }
    }
    LaunchedEffect(chatVM) {
        chatVM.start()
        chatVM.paginateBackward()
        chatVM.markAsRead()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        MatronTimelineBackground()
        TimelineList(
            chatVM = chatVM,
            stripVM = stripVM,
            activityLabel = null,
            onOpenChild = onOpenChild,
            onOpenConversation = onOpenConversation,
            onPreviewImage = { previewModel = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
    previewModel?.let { tapped ->
        AttachmentFullscreenViewer(
            model = (tapped as? TappedImage)?.model ?: tapped,
            name = (tapped as? TappedImage)?.name,
            onDismiss = { previewModel = null },
        )
    }
}
