package chat.matron.android.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerItem

/// A local "create" outbox row not yet confirmed by the server — see
/// `ItemsPanelViewModel.PendingItem`, which this mirrors; hosts build one per.
data class PendingItemRowModel(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val isFailed: Boolean,
    val error: String?,
)

/// Everything the list renders, supplied by the host from
/// `ItemsPanelViewModel` (the Swift `ItemsListView.Model`).
data class ItemsListModel(
    val needsYou: List<TrackerItem>,
    val tasks: List<TrackerItem>,
    val decisions: List<TrackerItem>,
    val done: List<TrackerItem>,
    /// Origin conversation labels for the All scope, keyed by convo id.
    val originTitles: Map<String, String>,
    val isSupported: Boolean,
    val isRefreshing: Boolean,
    val pending: List<PendingItemRowModel> = emptyList(),
) {
    val isEmpty: Boolean
        get() = needsYou.isEmpty() && tasks.isEmpty() && decisions.isEmpty() && done.isEmpty() && pending.isEmpty()
}

/// Which of the three bodies the list shows. Pure so the precedence
/// (unsupported wins over empty) is unit-tested.
enum class ItemsListState { UNSUPPORTED, EMPTY, POPULATED }

fun itemsListState(model: ItemsListModel): ItemsListState = when {
    !model.isSupported -> ItemsListState.UNSUPPORTED
    model.isEmpty -> ItemsListState.EMPTY
    else -> ItemsListState.POPULATED
}

/// The caption under a row in the All scope: the origin conversation's
/// label, or a neutral fallback for an unknown/untitled conversation.
fun itemOriginCaption(item: TrackerItem, scope: ItemsScope, originTitles: Map<String, String>): String? =
    if (scope is ItemsScope.All) originTitles[item.originConvoID] ?: "Another chat" else null

/// A reorder target the Tasks row's long-press menu offers.
data class TaskMoveOption(val label: String, val toIndex: Int)

/// The moves available for the task at [index] of [count]: none for a
/// single task, no "up"/"top" for the first, no "down"/"bottom" for the
/// last. Android has no drag handle here yet (the Apple iOS drawer's
/// `.onMove` was itself deferred to a device pass), so the same
/// `onMove(id, toIndex)` contract is reached through this menu.
fun taskMoveOptions(index: Int, count: Int): List<TaskMoveOption> {
    if (count <= 1 || index !in 0 until count) return emptyList()
    return buildList {
        if (index > 0) add(TaskMoveOption("Move to top", 0))
        if (index > 0) add(TaskMoveOption("Move up", index - 1))
        if (index < count - 1) add(TaskMoveOption("Move down", index + 1))
        if (index < count - 1) add(TaskMoveOption("Move to bottom", count - 1))
    }
}

/// The items panel body: scope control (when there is a home chat), the
/// four sections (plus Pending above them), and the unsupported / empty
/// states. Ported from matron-apple's `ItemsListView`; the iOS inset-grouped
/// `List` becomes a `LazyColumn` of section headers and rows.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsListView(
    model: ItemsListModel,
    scope: ItemsScope,
    /// The home conversation, or `null` for an app-wide instance — with no
    /// home chat the scope picker is hidden.
    convoID: String?,
    onScopeChange: (ItemsScope) -> Unit,
    onSelect: (TrackerItem) -> Unit,
    onMove: (String, Int) -> Unit,
    onCreate: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: (TrackerItem) -> Any? = { null },
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (convoID != null) {
                val isAll = scope is ItemsScope.All
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = !isAll,
                        onClick = { onScopeChange(ItemsScope.Convo(convoID)) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("This chat") }
                    SegmentedButton(
                        selected = isAll,
                        onClick = { onScopeChange(ItemsScope.All) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("All") }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (model.isRefreshing) {
                CircularProgressIndicator(
                    Modifier.size(18.dp).semantics { contentDescription = "Refreshing" },
                    strokeWidth = 2.dp,
                )
            }
            // An old journal can't accept the create.
            IconButton(onClick = onCreate, enabled = model.isSupported) {
                Icon(Icons.Filled.Add, contentDescription = "New item")
            }
        }
        when (itemsListState(model)) {
            ItemsListState.UNSUPPORTED -> ContentUnavailable(
                icon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Tracker not available",
                description = "Update the journal server to use items.",
            )
            ItemsListState.EMPTY -> ContentUnavailable(
                icon = { Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Nothing tracked yet",
                description = "Questions, tasks and decisions the agent files appear here.",
            )
            ItemsListState.POPULATED -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (model.pending.isNotEmpty()) {
                    sectionHeader("Pending")
                    items(model.pending, key = { "pending-${it.id}" }) { row ->
                        RowCard { PendingItemRow(row.kind, row.title, row.isFailed, Modifier.padding(12.dp)) }
                    }
                }
                section("Needs you", model.needsYou, scope, model.originTitles, movable = false, onSelect, onMove, onOpenConversation, thumbnail)
                section("Tasks", model.tasks, scope, model.originTitles, movable = true, onSelect, onMove, onOpenConversation, thumbnail)
                section("Decisions", model.decisions, scope, model.originTitles, movable = false, onSelect, onMove, onOpenConversation, thumbnail)
                section("Done", model.done, scope, model.originTitles, movable = false, onSelect, onMove, onOpenConversation, thumbnail)
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.section(
    title: String,
    list: List<TrackerItem>,
    scope: ItemsScope,
    originTitles: Map<String, String>,
    movable: Boolean,
    onSelect: (TrackerItem) -> Unit,
    onMove: (String, Int) -> Unit,
    onOpenConversation: (String) -> Unit,
    thumbnail: (TrackerItem) -> Any?,
) {
    if (list.isEmpty()) return
    sectionHeader(title)
    items(list.size, key = { "$title-${list[it].id}" }) { index ->
        val item = list[index]
        var menuOpen by remember { mutableStateOf(false) }
        val moves = if (movable) taskMoveOptions(index, list.size) else emptyList()
        val isAll = scope is ItemsScope.All
        RowCard {
            Box {
                ItemRow(
                    item,
                    origin = itemOriginCaption(item, scope, originTitles),
                    thumbnail = thumbnail(item),
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onSelect(item) },
                            onLongClick = if (moves.isNotEmpty() || isAll) ({ menuOpen = true }) else null,
                        )
                        .padding(12.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    moves.forEach { move ->
                        DropdownMenuItem(text = { Text(move.label) }, onClick = { menuOpen = false; onMove(item.id, move.toIndex) })
                    }
                    if (isAll) {
                        if (moves.isNotEmpty()) HorizontalDivider()
                        DropdownMenuItem(text = { Text("Open conversation") }, onClick = { menuOpen = false; onOpenConversation(item.originConvoID) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MatronThemeColors.current.bubbleBot,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun ContentUnavailable(icon: @Composable () -> Unit, title: String, description: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(0.dp))
        }
    }
}
