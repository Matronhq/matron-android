package chat.matron.android.designsystem

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import chat.matron.android.models.Mission

/// Everything the Missions list renders, mapped by the host from
/// `MissionsListViewModel` (the Swift `MissionsListView.Model`).
data class MissionsListModel(
    val open: List<Mission>,
    val closed: List<Mission>,
    /// `false` shows the unsupported message (and the shell hides the tab);
    /// `null` (not yet known) and `true` both show the list.
    val isSupported: Boolean?,
    val isRefreshing: Boolean,
) {
    val isEmpty: Boolean get() = open.isEmpty() && closed.isEmpty()
}

/// Which body the list shows. Pure so the precedence (unsupported wins over
/// empty; an unknown support state shows the list) is unit-tested.
enum class MissionsListState { UNSUPPORTED, EMPTY, POPULATED }

fun missionsListState(model: MissionsListModel): MissionsListState = when {
    model.isSupported == false -> MissionsListState.UNSUPPORTED
    model.isEmpty -> MissionsListState.EMPTY
    else -> MissionsListState.POPULATED
}

/// The collapsible Closed section's header copy and TalkBack label.
fun missionsClosedHeaderText(count: Int): String = "Closed ($count)"
fun missionsClosedToggleLabel(expanded: Boolean): String = if (expanded) "Hide closed missions" else "Show closed missions"

/// Every mission, open first, closed in a collapsed section (spec: Apps →
/// Missions tab → List). A pure leaf view: the host maps
/// `MissionsListViewModel` into [MissionsListModel]. Rows read like an
/// inbox — one hairline separator per row, the full width of the list
/// (apple #213). Pull to refresh in every state, as `DecisionsListView`.
/// Ported from matron-apple's `MissionsListView`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsListView(
    model: MissionsListModel,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClosed by remember { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = model.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        when (missionsListState(model)) {
            MissionsListState.UNSUPPORTED -> MissionsPlaceholder(
                icon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Missions not available",
                description = "Update the journal server to use missions.",
            )
            // The copy must not imply the app can start one: only an agent
            // can, through `mission_start` (spec #74).
            MissionsListState.EMPTY -> MissionsPlaceholder(
                icon = { Icon(Icons.Outlined.SportsScore, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "No missions yet",
                description = "An agent starts one with mission_start, then posts milestones as the work goes.",
            )
            MissionsListState.POPULATED -> LazyColumn(Modifier.fillMaxSize().animateContentSize()) {
                if (model.open.isNotEmpty()) {
                    item(key = "header:open") { SectionHeader("Open") }
                    inboxRows(model.open, onSelect)
                }
                if (model.closed.isNotEmpty()) {
                    item(key = "header:closed") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = missionsClosedToggleLabel(showClosed)) { showClosed = !showClosed }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                missionsClosedHeaderText(model.closed.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (showClosed) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                    if (showClosed) inboxRows(model.closed, onSelect)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
    HorizontalDivider()
}

/// Inbox-style rows: the horizontal breathing room lives on the row content
/// and the hairline runs edge to edge beneath it (apple #213's `MacInboxRow`).
private fun LazyListScope.inboxRows(missions: List<Mission>, onSelect: (String) -> Unit) {
    items(missions, key = { it.id }) { mission ->
        Column(Modifier.fillMaxWidth()) {
            MissionRowView(
                mission,
                modifier = Modifier
                    .clickable { onSelect(mission.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            HorizontalDivider()
        }
    }
}

/// The empty / unsupported states, in a scrollable column so the
/// pull-to-refresh container above still answers a pull.
@Composable
private fun MissionsPlaceholder(icon: @Composable () -> Unit, title: String, description: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.width(0.dp))
        }
    }
}
