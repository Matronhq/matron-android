package chat.matron.android.designsystem

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Memory
import chat.matron.android.models.memoryAuthorLabel
import chat.matron.android.models.memoryTypeLabel
import java.time.Duration
import java.time.Instant

/// Everything the Memories list renders, mapped by the host from
/// `MemoriesListViewModel`.
data class MemoriesListModel(
    val memories: List<Memory>,
    /// `false` shows the unsupported message; `null` and `true` show the list.
    val isSupported: Boolean?,
    val isRefreshing: Boolean,
)

/// Which body the list shows. Pure so the precedence is unit-tested.
enum class MemoriesListState { UNSUPPORTED, EMPTY, POPULATED }

fun memoriesListState(model: MemoriesListModel): MemoriesListState = when {
    model.isSupported == false -> MemoriesListState.UNSUPPORTED
    model.memories.isEmpty() -> MemoriesListState.EMPTY
    else -> MemoriesListState.POPULATED
}

/// "Updated 5 min ago by you" — the row's meta line. Coarse on purpose: a
/// memory is edited rarely and the list is not a feed.
fun memoryRowMetaText(memory: Memory, now: Instant = Instant.now()): String {
    val age = Duration.between(memory.updatedAt, now).coerceAtLeast(Duration.ZERO)
    val ago = when {
        age.toMinutes() < 1 -> "just now"
        age.toHours() < 1 -> "${age.toMinutes()} min ago"
        age.toDays() < 1 -> "${age.toHours()} h ago"
        age.toDays() < 30 -> "${age.toDays()} d ago"
        else -> "${age.toDays() / 30} mo ago"
    }
    return "Updated $ago by ${memoryAuthorLabel(memory.updatedBy)}"
}

/// The row's TalkBack announcement.
fun memoryRowAccessibilityLabel(memory: Memory): String =
    "Memory ${memory.name}, ${memoryTypeLabel(memory.type)}: ${memory.description}"

/// One row: the name (monospace, with the type pill beside it), the
/// one-line description, then the meta line. Mirrors matron-web's
/// `MemoryRow`.
@Composable
fun MemoryRowView(memory: Memory, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = memoryRowAccessibilityLabel(memory) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                memory.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                memoryTypeLabel(memory.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(memory.description, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(memoryRowMetaText(memory), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/// The user's memories, sorted by name by the sync. Pull to refresh in
/// every state, like `MissionsListView`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesListView(
    model: MemoriesListModel,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = model.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        when (memoriesListState(model)) {
            MemoriesListState.UNSUPPORTED -> MemoriesPlaceholder(
                icon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "Memories not available",
                description = "Update the journal server to use memories.",
            )
            MemoriesListState.EMPTY -> MemoriesPlaceholder(
                icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = "No memories yet",
                description = "Tell an agent a rule about how you want work done and it saves one here, or add one yourself.",
            )
            MemoriesListState.POPULATED -> LazyColumn(Modifier.fillMaxSize()) {
                items(model.memories, key = { it.id }) { memory ->
                    Column(Modifier.fillMaxWidth()) {
                        MemoryRowView(
                            memory,
                            modifier = Modifier
                                .clickable { onSelect(memory.name) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoriesPlaceholder(icon: @Composable () -> Unit, title: String, description: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.width(0.dp))
        }
    }
}
