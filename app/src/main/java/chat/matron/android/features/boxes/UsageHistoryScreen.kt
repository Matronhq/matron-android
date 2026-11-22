package chat.matron.android.features.boxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.UsageHistoryBars
import chat.matron.android.designsystem.UsageHistoryStyle
import chat.matron.android.models.UsageHistoryTable
import chat.matron.android.models.UsageMeterChoice
import chat.matron.android.viewmodels.UsageHistoryViewModel
import kotlinx.coroutines.launch

/// Usage history, pushed from Boxes: a card per account with the last twelve
/// weeks as bars and what was left unused last week. Port of matron-apple's
/// `UsageHistoryScreen`.
@Composable
fun UsageHistoryScreen(viewModel: UsageHistoryViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }
    UsageHistoryContent(
        table = state.table(),
        choices = state.choices,
        choiceID = state.choice.id,
        hasLoaded = state.hasLoaded,
        hasHistory = state.history != null,
        errorMessage = state.errorMessage,
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    viewModel.load()
                } finally {
                    refreshing = false
                }
            }
        },
        onChoose = viewModel::choose,
        onBack = onBack,
    )
}

/// The screen's body from plain values, so it draws from fixtures.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageHistoryContent(
    table: UsageHistoryTable,
    choices: List<UsageMeterChoice>,
    choiceID: String,
    hasLoaded: Boolean,
    hasHistory: Boolean,
    errorMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onChoose: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage history") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (choices.size > 1) {
                    item(key = "meter") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            choices.forEachIndexed { index, choice ->
                                SegmentedButton(
                                    selected = choice.id == choiceID,
                                    onClick = { onChoose(choice.id) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                                ) { Text(choice.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                if (errorMessage != null) {
                    item(key = "error") {
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            // With nothing to show it is the page's message,
                            // not an alarm over a list that is still there.
                            color = if (hasHistory) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                items(table.rows, key = { it.id }) { row -> AccountCard(row, table.columns) }
                if (table.rows.isEmpty() && errorMessage == null) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                            if (hasLoaded) {
                                Text(
                                    "No usage history yet. A week shows here once it has reset.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                } else if (table.rows.isNotEmpty()) {
                    item(key = "legend") {
                        Text(
                            "Blue is the share of each week's allowance left unused when it reset. " +
                                "The dashed bar is the week in progress.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(row: UsageHistoryTable.Row, columns: List<Long>) {
    val firstColumnMs = columns.firstOrNull()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val latest = row.latest
    // "last week" only when the newest reading is last week's; otherwise the
    // week it is from.
    val latestWeek = when {
        latest == null -> null
        row.latestIsLastWeek -> "last week"
        else -> row.latestColumn?.let(columns::getOrNull)?.let { "week of ${UsageHistoryStyle.columnText(it)}" } ?: "last week"
    }
    // Only weeks with a reading are in the average.
    val weeks = row.cells.count { it != null }
    val spoken = buildString {
        append("${row.title}, ${row.subtitle}. ")
        append(latest?.let { "${it.unused} percent unused $latestWeek" } ?: "No finished week")
        row.averageUnused?.let { append(", $weeks-week average $it percent unused") }
        row.current?.let { append(", ${it.unused} percent left so far this week") }
    }
    BoxesCard(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(row.subtitle, style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        latest?.let { "${it.unused}%" } ?: "—",
                        style = MaterialTheme.typography.titleLarge.merge(TextStyle(fontFeatureSettings = "tnum")),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (latest == null) "no finished week" else "unused $latestWeek",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                    )
                }
            }
            UsageHistoryBars(row)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val caption = MaterialTheme.typography.labelSmall
                Text(firstColumnMs?.let(UsageHistoryStyle::columnText).orEmpty(), style = caption, color = secondary)
                row.averageUnused?.let {
                    Text("$weeks-week average $it% unused", style = caption, color = secondary)
                }
                Text("now", style = caption, color = secondary)
            }
        }
    }
}
