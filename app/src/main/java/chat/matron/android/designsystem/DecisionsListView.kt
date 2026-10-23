package chat.matron.android.designsystem

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ForYouTab
import chat.matron.android.models.TrackerItem
import java.time.Instant

/// What the user reads on the For you tab (once "Decisions"): the tab label,
/// the screen title and the empty state, in one place so they cannot drift.
object ForYouCopy {
    const val TITLE = "For you"
    const val EMPTY_TITLE = "Nothing needs you"
    const val EMPTY_DESCRIPTION =
        "Questions, things to read and secret requests from every conversation appear here."
    /// The one-tap button on a notice row, and its swipe's announcement.
    const val SEEN_BUTTON = "Seen"
    /// The Done tab with nothing closed yet.
    const val CLOSED_EMPTY_TITLE = "Nothing done yet"
    const val CLOSED_EMPTY_DESCRIPTION =
        "Questions and decisions move here once they are closed, and notices once you have seen them. " +
            "Newest first by your last reply."
    /// The row under the Done tab's visible window.
    const val SHOW_MORE = "Show more"
    /// The search field above the Needs you | Done control.
    const val SEARCH_PLACEHOLDER = "Search For you"
    /// A search that leaves the tab's list empty.
    const val NO_MATCHES_TITLE = "No matches"
    const val NO_MATCHES_DESCRIPTION = "Nothing in this list matches your search."
}

/// Whether every word of [query] appears in the row's number, title, text
/// or origin conversation — the For you search field's filter, the same
/// rule the web inbox applies. A blank query lets every row through.
fun decisionsRowMatches(row: DecisionsRow, query: String): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val haystack = "#${row.item.num} ${row.item.title} ${row.item.body} ${decisionsRowOrigin(row)}".lowercase()
    return words.all { haystack.contains(it) }
}

/// One For you row: the item plus its origin conversation's label (`null`
/// when unknown or untitled — the row falls back to "Another chat").
data class DecisionsRow(val item: TrackerItem, val originTitle: String?) {
    val id: String get() = item.id
}

/// Everything the Decisions list renders, mapped by the host from
/// `ItemsPanelViewModel` (the Swift `DecisionsListView.Model`).
data class DecisionsListModel(
    /// The Needs you tab's rows, from `awaitingYou`.
    val rows: List<DecisionsRow>,
    /// `false` shows the unsupported-journal message; `null` (not yet
    /// known) and `true` both show the list.
    val isSupported: Boolean?,
    val isRefreshing: Boolean,
    val tab: ForYouTab = ForYouTab.OPEN,
    /// The Done tab's VISIBLE window, in `ItemsPanelViewModel.decided`
    /// order — already sliced by the host, and left empty while the Needs
    /// you tab shows so the open list never pays for building it.
    val closed: List<DecisionsRow> = emptyList(),
    /// Every locally known closed question, decision and notice, however
    /// many of them [closed] currently shows.
    val closedTotalCount: Int = 0,
    /// The search field's text. The host has already filtered [rows] and
    /// [closed] by it; a non-blank query only changes the empty state.
    val query: String = "",
) {
    /// Whether "Show more" renders under the visible window — purely local;
    /// there is no server page to reach for.
    val hasMoreClosed: Boolean get() = closed.size < closedTotalCount
}

/// Which body the list shows. Pure so the precedence (unsupported wins over
/// empty; an unknown support state shows the list; each tab has its own
/// empty state) is unit-tested.
enum class DecisionsListState { UNSUPPORTED, EMPTY, POPULATED, CLOSED_EMPTY, CLOSED_POPULATED, NO_MATCHES }

fun decisionsListState(model: DecisionsListModel): DecisionsListState = when {
    model.isSupported == false -> DecisionsListState.UNSUPPORTED
    model.query.isNotBlank() &&
        (if (model.tab == ForYouTab.CLOSED) model.closedTotalCount == 0 else model.rows.isEmpty()) ->
        DecisionsListState.NO_MATCHES
    model.tab == ForYouTab.CLOSED ->
        if (model.closedTotalCount == 0) DecisionsListState.CLOSED_EMPTY else DecisionsListState.CLOSED_POPULATED
    model.rows.isEmpty() -> DecisionsListState.EMPTY
    else -> DecisionsListState.POPULATED
}

/// Whether the Needs you | Done control shows: with every state but the
/// unsupported journal's, which has nothing on either tab.
fun decisionsShowsTabs(model: DecisionsListModel): Boolean = model.isSupported != false

/// The origin caption a Decisions row always shows — the same fallback the
/// tracker's All scope uses (`itemOriginCaption`).
fun decisionsRowOrigin(row: DecisionsRow): String = row.originTitle ?: "Another chat"

/// Whether a row offers the one-tap Seen button and the swipe that does the
/// same: open notices only — everything else in For you needs an answer.
fun decisionsRowOffersSeen(row: DecisionsRow): Boolean = row.item.canMarkSeen

/// The For you list, on two tabs. Needs you: every open item awaiting the
/// user, across every conversation, newest first, with its origin
/// conversation (app shell, spec §2). Done: every closed question, decision
/// and notice, newest first by the user's own last input. A pure leaf
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
    /// A notice's Seen — its row button or a swipe from the trailing edge.
    onMarkSeen: (TrackerItem) -> Unit = {},
    /// The Needs you | Done control.
    onSelectTab: (ForYouTab) -> Unit = {},
    /// "Show more" under the Done tab — a local window grow.
    onShowMoreClosed: () -> Unit = {},
    /// The clock the Done rows' "Answered · 2 hr ago" captions read. The
    /// host refreshes it on a timer; a parameter so the view stays pure.
    now: Instant = Instant.now(),
    /// The search field's edits; the host filters the rows.
    onQueryChange: (String) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        if (decisionsShowsTabs(model)) {
            ForYouSearchField(model.query, onQueryChange)
            ForYouTabPicker(model.tab, onSelectTab)
        }
        PullToRefreshBox(isRefreshing = model.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            when (decisionsListState(model)) {
                DecisionsListState.UNSUPPORTED -> Placeholder(
                    icon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Tracker not available",
                    description = "Update the journal server to use items.",
                )
                DecisionsListState.NO_MATCHES -> Placeholder(
                    icon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = ForYouCopy.NO_MATCHES_TITLE,
                    description = ForYouCopy.NO_MATCHES_DESCRIPTION,
                )
                DecisionsListState.EMPTY -> Placeholder(
                    icon = { Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = ForYouCopy.EMPTY_TITLE,
                    description = ForYouCopy.EMPTY_DESCRIPTION,
                )
                // Inbox-style rows (apple #213): one hairline separator per row,
                // the full width of the list, with the breathing room on the row
                // content itself — the same chrome the Missions list uses, so
                // the two tabs read alike. An open notice adds a trailing Seen
                // button and a swipe that does the same.
                DecisionsListState.POPULATED -> LazyColumn(Modifier.fillMaxSize()) {
                    items(model.rows, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth()) {
                            if (decisionsRowOffersSeen(row)) {
                                SeenSwipeRow(onSeen = { onMarkSeen(row.item) }) {
                                    DecisionsRowContent(row, onSelect, onOpenConversation, onMarkSeen)
                                }
                            } else {
                                DecisionsRowContent(row, onSelect, onOpenConversation, onMarkSeen)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                DecisionsListState.CLOSED_EMPTY -> Placeholder(
                    icon = { Icon(Icons.Outlined.Inbox, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = ForYouCopy.CLOSED_EMPTY_TITLE,
                    description = ForYouCopy.CLOSED_EMPTY_DESCRIPTION,
                )
                // The same row chrome as the open list. A closed row has no Seen
                // button and no swipe: there is nothing left to mark.
                DecisionsListState.CLOSED_POPULATED -> LazyColumn(Modifier.fillMaxSize()) {
                    items(model.closed, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth()) {
                            DecisionsRowContent(
                                row, onSelect, onOpenConversation, onMarkSeen,
                                closedCaption = decisionsClosedRowCaption(row, now),
                            )
                            HorizontalDivider()
                        }
                    }
                    if (model.hasMoreClosed) {
                        item(key = "show-more") {
                            Text(
                                ForYouCopy.SHOW_MORE,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onShowMoreClosed).padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/// A Done row's caption: [ItemGlyph.closedCaption] at the time the tab is
/// ordered by, so the list reads in order.
fun decisionsClosedRowCaption(row: DecisionsRow, now: Instant): String? =
    ItemGlyph.closedCaption(row.item, at = row.item.closedSortDate, now = now)

/// The search field above the Needs you | Done control: a rounded filled
/// field with a clear button, filtering whichever tab shows.
@Composable
private fun ForYouSearchField(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(ForYouCopy.SEARCH_PLACEHOLDER) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

/// The Needs you | Done control, above whichever tab's list shows — the
/// tracker's own segmented control ([ItemsListView]'s This chat | All). A
/// control rather than a section under the open list: with many open items
/// the closed ones were a long scroll away.
@Composable
private fun ForYouTabPicker(tab: ForYouTab, onSelectTab: (ForYouTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ForYouTab.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = tab == entry,
                onClick = { onSelectTab(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, ForYouTab.entries.size),
            ) { Text(entry.title) }
        }
    }
}

/// One row: the shared [ItemRow], the long-press "Open conversation" menu,
/// and — on an open notice — a trailing Seen button. [closedCaption] is a
/// Done row's "Answered · 2 hr ago".
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DecisionsRowContent(
    row: DecisionsRow,
    onSelect: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onMarkSeen: (TrackerItem) -> Unit,
    closedCaption: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onSelect(row.item.id) }, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemRow(row.item, origin = decisionsRowOrigin(row), modifier = Modifier.weight(1f), closedCaption = closedCaption)
            if (decisionsRowOffersSeen(row)) {
                OutlinedButton(
                    onClick = { onMarkSeen(row.item) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(ForYouCopy.SEEN_BUTTON, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Open conversation") },
                onClick = { menuOpen = false; onOpenConversation(row.item.originConvoID) },
            )
        }
    }
}

/// A notice row's swipe: dragged from the trailing edge past the threshold,
/// it marks the notice seen. Only end-to-start, so a drag the other way is
/// left alone. The view model drops the row from the list at once; should
/// it stay (the tap was refused), the row settles back.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeenSwipeRow(onSeen: () -> Unit, content: @Composable () -> Unit) {
    val latestOnSeen by rememberUpdatedState(onSeen)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) latestOnSeen()
            // Never stay dismissed: the row's fate is the list's, not the box's.
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Drawn only mid-swipe: the rows sit transparent over the
            // timeline background, so a resting row must not show it through.
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Row(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(6.dp))
                    Text(ForYouCopy.SEEN_BUTTON, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        // Opaque only mid-swipe too, so the sliding row covers the backdrop.
        val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
        Box(if (swiping) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier) { content() }
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
