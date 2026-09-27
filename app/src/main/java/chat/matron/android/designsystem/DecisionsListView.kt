package chat.matron.android.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.models.TrackerItem

/// One Decisions row: the item plus its origin conversation's label (`null`
/// when unknown or untitled — the row falls back to "Another chat").
data class DecisionsRow(val item: TrackerItem, val originTitle: String?) {
    val id: String get() = item.id
}

/// Everything the Decisions list renders, mapped by the host from
/// `ItemsPanelViewModel.awaitingYou` (the Swift `DecisionsListView.Model`).
data class DecisionsListModel(
    val rows: List<DecisionsRow>,
    /// `false` shows the unsupported-journal message; `null` (not yet
    /// known) and `true` both show the list.
    val isSupported: Boolean?,
    val isRefreshing: Boolean,
)

/// Which body the list shows. Pure so the precedence (unsupported wins over
/// empty; an unknown support state shows the list) is unit-tested.
enum class DecisionsListState { UNSUPPORTED, EMPTY, POPULATED }

fun decisionsListState(model: DecisionsListModel): DecisionsListState = when {
    model.isSupported == false -> DecisionsListState.UNSUPPORTED
    model.rows.isEmpty() -> DecisionsListState.EMPTY
    else -> DecisionsListState.POPULATED
}

/// The origin caption a Decisions row always shows — the same fallback the
/// tracker's All scope uses (`itemOriginCaption`).
fun decisionsRowOrigin(row: DecisionsRow): String = row.originTitle ?: "Another chat"

/// Every open item awaiting the user, across every conversation, newest
/// first, with its origin conversation (app shell, spec §2). A pure leaf
/// view: the host maps the view model into [DecisionsListModel]. Rows reuse
/// [ItemRow] with the origin subtitle always on — the same rendering
/// [ItemsListView] uses in its All scope. Pull to refresh in every state
/// (Bugbot, apple #191): a stale cache or a journal that gains tracker
/// support later would otherwise have no way to re-fetch. Ported from
/// matron-apple's `DecisionsListView`.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DecisionsListView(
    model: DecisionsListModel,
    onSelect: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = model.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        when (decisionsListState(model)) {
            DecisionsListState.UNSUPPORTED -> Placeholder(
                icon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Tracker not available",
                description = "Update the journal server to use items.",
            )
            DecisionsListState.EMPTY -> Placeholder(
                icon = { Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Nothing needs you",
                description = "Questions and decisions waiting on you, from every conversation, appear here.",
            )
            DecisionsListState.POPULATED -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(model.rows, key = { it.id }) { row ->
                    var menuOpen by remember { mutableStateOf(false) }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MatronThemeColors.current.bubbleBot,
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box {
                            ItemRow(
                                row.item,
                                origin = decisionsRowOrigin(row),
                                modifier = Modifier
                                    .combinedClickable(onClick = { onSelect(row.item.id) }, onLongClick = { menuOpen = true })
                                    .padding(12.dp),
                            )
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Open conversation") },
                                    onClick = { menuOpen = false; onOpenConversation(row.item.originConvoID) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/// The empty / unsupported states, in a scrollable column so the
/// pull-to-refresh container above still answers a pull.
@Composable
private fun Placeholder(icon: @Composable () -> Unit, title: String, description: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
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
