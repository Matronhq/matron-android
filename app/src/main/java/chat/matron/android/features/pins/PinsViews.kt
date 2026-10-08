package chat.matron.android.features.pins

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.ChatSummary
import chat.matron.android.designsystem.NeedsYouBadge
import chat.matron.android.designsystem.UnreadBadge
import chat.matron.android.features.chatlist.ChatRow
import chat.matron.android.features.coordinator.coordinatorChoices
import chat.matron.android.journal.ConvoPin
import chat.matron.android.journal.Pins
import chat.matron.android.journal.PinsSyncing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/// The pin actions every surface offers (the Pinned section, a row's
/// long-press menu, the chat header menu, Settings → Pinned chats). The
/// shell's [PinsController] implements it; the screens stay dumb.
interface PinActions {
    /// "Pin…" / "Edit pin…": the label + emoji dialog.
    fun edit(convoID: String)
    /// "Move pin…": the conversation chooser.
    fun move(convoID: String)
    fun unpin(convoID: String)
    fun shift(convoID: String, direction: Pins.Direction)
    fun acceptSuccessor(pin: ConvoPin)
    fun dismissSuccessor(pin: ConvoPin)
    /// Settings' "Add a pinned chat…": the chooser, then the dialog.
    fun add()
}

/// One entry of the Pinned section: the pin and, when the list holds it,
/// its conversation. No [summary] (the journal flagged the row missing,
/// or this device's list lacks it) draws the greyed entry that offers
/// only Move pin… and Unpin.
data class PinnedEntry(val pin: ConvoPin, val summary: ChatSummary?) {
    val isMissing: Boolean get() = summary == null
}

/// The Pinned section in the journal's position order. Ported from
/// matron-web's `pinSection` (less its archive and search filters, which
/// this list has no counterpart of).
fun pinnedEntries(pins: List<ConvoPin>, summaries: List<ChatSummary>): List<PinnedEntry> {
    val byID = summaries.associateBy { it.id }
    return pins.map { pin -> PinnedEntry(pin, if (pin.missing) null else byID[pin.convoID]) }
}

/// The ids the ordinary list below the Pinned section leaves out: a
/// pinned conversation is drawn once, as its pin.
fun pinnedIDs(pins: List<ConvoPin>?): Set<String> = pins.orEmpty().mapTo(mutableSetOf()) { it.convoID }

/// What the chat header's "⋮" menu offers for this conversation, or null
/// when this journal has no pins.
data class ChatPinMenu(
    val isPinned: Boolean,
    val onPin: () -> Unit,
    val onMove: () -> Unit,
    val onUnpin: () -> Unit,
)

fun chatPinMenu(pins: List<ConvoPin>?, convoID: String, actions: PinActions): ChatPinMenu? {
    if (pins == null) return null
    return ChatPinMenu(
        isPinned = pins.any { it.convoID == convoID },
        onPin = { actions.edit(convoID) },
        onMove = { actions.move(convoID) },
        onUnpin = { actions.unpin(convoID) },
    )
}

/// The shell's one pin coordinator per session: which sheet is open, and
/// the writes behind every [PinActions] call. A write that fails outside a
/// sheet lands in [error] ("Couldn't update pins") for the host's alert;
/// the sheets show their own failure inline.
class PinsController(
    val sync: PinsSyncing,
    private val scope: CoroutineScope,
) : PinActions {
    var editTarget by mutableStateOf<String?>(null)
        private set
    var moveTarget by mutableStateOf<String?>(null)
        private set
    var addOpen by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    override fun edit(convoID: String) {
        editTarget = convoID
    }

    override fun move(convoID: String) {
        moveTarget = convoID
    }

    override fun add() {
        addOpen = true
        // The hello carries no limit: read it now so the screen can say so.
        scope.launch { sync.refresh() }
    }

    override fun unpin(convoID: String) = report { sync.remove(convoID) }

    override fun shift(convoID: String, direction: Pins.Direction) = report { sync.shift(convoID, direction) }

    override fun acceptSuccessor(pin: ConvoPin) = report { sync.acceptSuccessor(pin) }

    override fun dismissSuccessor(pin: ConvoPin) = report { sync.dismissSuccessor(pin) }

    fun closeEdit() {
        editTarget = null
    }

    fun closeMove() {
        moveTarget = null
    }

    /// The add chooser's pick: the label dialog for that conversation.
    fun pickToAdd(convoID: String) {
        addOpen = false
        editTarget = convoID
    }

    fun closeAdd() {
        addOpen = false
    }

    private fun report(write: suspend () -> String?) {
        scope.launch { write()?.let { error = it } }
    }
}

/// The sheets and the alert behind [PinsController], hosted once by the
/// signed-in shell over whatever screen is showing.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinDialogsHost(controller: PinsController, chats: List<ChatSummary>, isLoading: Boolean) {
    val pins by controller.sync.pins.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    controller.editTarget?.let { convoID ->
        val existing = pins?.firstOrNull { it.convoID == convoID }
        val summary = chats.firstOrNull { it.id == convoID }
        PinEditDialog(
            key = convoID,
            isNew = existing == null,
            conversationTitle = summary?.title,
            initialLabel = existing?.label ?: Pins.labelFromTitle(summary?.title ?: ""),
            initialEmoji = existing?.emoji ?: "",
            onSave = { label, emoji -> controller.sync.save(convoID, label, emoji) },
            onDismiss = controller::closeEdit,
        )
    }
    controller.moveTarget?.let { convoID ->
        val pin = pins?.firstOrNull { it.convoID == convoID }
        val pinned = pinnedIDs(pins)
        var moveError by remember(convoID) { mutableStateOf<String?>(null) }
        var moving by remember(convoID) { mutableStateOf(false) }
        ModalBottomSheet(
            onDismissRequest = controller::closeMove,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PinChooserSheet(
                title = "Move pin",
                subtitle = pin?.let { "Point “${it.label}” at another conversation." },
                chats = chats.filter { it.id !in pinned },
                isLoading = isLoading,
                error = moveError,
                enabled = !moving,
                onPick = { target ->
                    moving = true
                    moveError = null
                    scope.launch {
                        val failure = controller.sync.move(convoID, target)
                        moving = false
                        // Only this pin's sheet: another may have opened meanwhile.
                        if (failure != null) moveError = failure
                        else if (controller.moveTarget == convoID) controller.closeMove()
                    }
                },
                onCancel = controller::closeMove,
            )
        }
    }
    if (controller.addOpen) {
        val pinned = pinnedIDs(pins)
        ModalBottomSheet(
            onDismissRequest = controller::closeAdd,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PinChooserSheet(
                title = "Pin a chat",
                subtitle = null,
                chats = chats.filter { it.id !in pinned },
                isLoading = isLoading,
                error = null,
                enabled = true,
                onPick = controller::pickToAdd,
                onCancel = controller::closeAdd,
            )
        }
    }
    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.error = null },
            title = { Text("Couldn't update pins") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { controller.error = null }) { Text("OK") } },
        )
    }
}

/// "Pin…" / "Edit pin": the label (prefilled from the title with the
/// bridge's marker and `[ab] ` short peeled off) and an optional emoji.
/// [onSave] resolves to the journal's refusal in words, shown inline (the
/// 409 pin limit lands here), or null to close. Ported from matron-web's
/// `PinEditSheet`.
@Composable
fun PinEditDialog(
    key: String,
    isNew: Boolean,
    conversationTitle: String?,
    initialLabel: String,
    initialEmoji: String,
    onSave: suspend (label: String, emoji: String) -> String?,
    onDismiss: () -> Unit,
) {
    var label by remember(key) { mutableStateOf(initialLabel) }
    var emoji by remember(key) { mutableStateOf(initialEmoji) }
    var saving by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trimmed = Pins.clampLabel(label)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Pin chat" else "Edit pin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                conversationTitle?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = label,
                    // One line, capped as the journal caps it.
                    onValueChange = { label = it.replace('\n', ' ').let(::capLabelInput) },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = Pins.clampEmoji(it) },
                    singleLine = true,
                    label = { Text("Emoji (optional)") },
                    placeholder = { Text("Shows the label's first letter when empty") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && trimmed.isNotEmpty(),
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val failure = onSave(trimmed, emoji.trim())
                        saving = false
                        if (failure == null) onDismiss() else error = failure
                    }
                },
            ) { Text(if (isNew) "Pin" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/// The label field's cap while typing: 24 code points, never splitting an
/// emoji (trailing whitespace is kept so a word can follow).
private fun capLabelInput(text: String): String {
    if (text.codePointCount(0, text.length) <= Pins.LABEL_MAX) return text
    return text.substring(0, text.offsetByCodePoints(0, Pins.LABEL_MAX))
}

/// The boxes a chooser candidate is on, as its chat-list row names them:
/// every box of a multi-box room, else the conversation's own box. Empty
/// when the row carries no box (single-box journals never name one).
internal fun chooserBoxes(summary: ChatSummary): List<String> =
    summary.roomBoxNames.ifEmpty { listOfNotNull(summary.boxName) }

/// The pin chooser's box filter options after "All": every named box among
/// [chats], once each, sorted by name. Empty when the candidates span
/// fewer than two boxes — there is nothing to narrow, so no filter shows.
fun pinChooserBoxOptions(chats: List<ChatSummary>): List<String> {
    val boxes = chats.flatMapTo(mutableSetOf(), ::chooserBoxes)
    if (boxes.size < 2) return emptyList()
    return boxes.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })
}

/// The box the chooser narrows to: [selected] while it is still one of
/// [options], else null ("All") — a box whose conversations all left the
/// candidates drops the filter rather than emptying the list.
fun activePinChooserBox(selected: String?, options: List<String>): String? =
    selected?.takeIf { it in options }

/// What tapping [box]'s chip selects: the box, or "All" (null) when it was
/// already selected.
fun toggledPinChooserBox(selected: String?, box: String): String? = if (selected == box) null else box

/// The chooser's rows: the title search (`coordinatorChoices`) AND, when
/// [box] is set, only conversations on that box.
fun pinChooserChoices(chats: List<ChatSummary>, query: String, box: String?): List<ChatSummary> {
    val matches = coordinatorChoices(chats, query)
    if (box == null) return matches
    return matches.filter { box in chooserBoxes(it) }
}

/// A conversation chooser for a pin — "Move pin" and Settings' "Pin a
/// chat": the chat-list rows under a search box, like the Coordinator
/// chooser (`CoordinatorChooserSheet`) without its "New chat" row, plus a
/// box filter row when the candidates span two or more boxes.
@Composable
fun PinChooserSheet(
    title: String,
    subtitle: String?,
    chats: List<ChatSummary>,
    isLoading: Boolean,
    error: String?,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedBox by remember { mutableStateOf<String?>(null) }
    val boxOptions = remember(chats) { pinChooserBoxOptions(chats) }
    val box = activePinChooserBox(selectedBox, boxOptions)
    // A box that left the candidates is forgotten, not parked: it must not
    // snap back on if one of its conversations returns.
    LaunchedEffect(box) { if (box == null) selectedBox = null }
    val choices = remember(chats, query, box) { pinChooserChoices(chats, query, box) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        subtitle?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search conversations") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (boxOptions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = box == null, onClick = { selectedBox = null }, label = { Text("All") })
                boxOptions.forEach { option ->
                    FilterChip(
                        selected = box == option,
                        onClick = { selectedBox = toggledPinChooserBox(box, option) },
                        label = { Text(option) },
                    )
                }
            }
        }
        error?.let {
            Text(
                it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        when {
            isLoading && chats.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            choices.isEmpty() -> Text(
                if (chats.isEmpty()) "No conversations to pin." else "No conversations match.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().height(420.dp).alpha(if (enabled) 1f else 0.5f)) {
                items(choices, key = { it.id }) { summary ->
                    ChatRow(summary = summary, onOpen = { if (enabled) onPick(summary.id) }, onMute = null, onLeave = null)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/// The pin's emoji, or its label's first letter, in a round tile.
@Composable
fun PinGlyph(pin: ConvoPin, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(Pins.glyph(pin), style = MaterialTheme.typography.titleMedium)
    }
}

/// One row of the Pinned section: glyph, label, the conversation's title
/// as the secondary line, then the needs-you and unread badges. Long-press
/// opens the pin's menu (Edit pin…, Move up/down, Move pin…, Unpin). A
/// missing pin greys out and offers only Move pin… and Unpin, inline.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedChatRow(
    entry: PinnedEntry,
    index: Int,
    count: Int,
    actions: PinActions,
    onOpen: () -> Unit,
) {
    val pin = entry.pin
    val summary = entry.summary
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { if (summary != null) onOpen() else menuOpen = true },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val greyed = if (summary == null) Modifier.alpha(0.5f) else Modifier
            PinGlyph(pin, modifier = greyed)
            Column(modifier = Modifier.weight(1f).then(greyed)) {
                Text(pin.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary?.title ?: "Conversation unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (summary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    NeedsYouBadge(count = summary.needsUserCount)
                    UnreadBadge(count = summary.unreadCount)
                }
            } else {
                TextButton(onClick = { actions.move(pin.convoID) }) { Text("Move pin…") }
                TextButton(onClick = { actions.unpin(pin.convoID) }) { Text("Unpin") }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            PinMenuItems(pin.convoID, missing = summary == null, index = index, count = count, actions = actions) { menuOpen = false }
        }
    }
}

/// A pin's menu: a missing pin offers only Move pin… and Unpin (spec).
@Composable
fun PinMenuItems(convoID: String, missing: Boolean, index: Int, count: Int, actions: PinActions, close: () -> Unit) {
    if (!missing) {
        DropdownMenuItem(text = { Text("Edit pin…") }, onClick = { close(); actions.edit(convoID) })
        if (index > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { close(); actions.shift(convoID, Pins.Direction.UP) })
        if (index < count - 1) DropdownMenuItem(text = { Text("Move down") }, onClick = { close(); actions.shift(convoID, Pins.Direction.DOWN) })
    }
    DropdownMenuItem(text = { Text("Move pin…") }, onClick = { close(); actions.move(convoID) })
    DropdownMenuItem(text = { Text("Unpin") }, onClick = { close(); actions.unpin(convoID) })
}

/// "New session on <box> — move pin here?" under its pin: one tap moves
/// the pin (keeping its name, emoji and position), or dismisses the offer.
@Composable
fun PinSuccessorRow(pin: ConvoPin, boxNames: Map<Long, String>, actions: PinActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 64.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Pins.successorHintText(pin, boxNames),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { actions.acceptSuccessor(pin) }) { Text("Move here") }
        TextButton(onClick = { actions.dismissSuccessor(pin) }) { Text("Dismiss") }
    }
}
