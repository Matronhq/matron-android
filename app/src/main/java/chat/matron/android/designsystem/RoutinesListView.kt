package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Routine
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/// Everything the Routines list renders, mapped by the host from
/// `RoutinesViewModel`.
data class RoutinesListModel(
    /// `null` until the first load lands.
    val routines: List<Routine>?,
    /// `false` once the journal has 404'd `GET /routines`.
    val isSupported: Boolean,
    val isLoading: Boolean,
    val loadError: String? = null,
    /// Routines with a pause or run request in flight.
    val busyIDs: Set<String> = emptySet(),
)

/// Which body the list shows. Pure so the precedence is unit-tested.
enum class RoutinesListState { UNSUPPORTED, LOADING, LOAD_FAILED, EMPTY, POPULATED }

fun routinesListState(model: RoutinesListModel): RoutinesListState = when {
    !model.isSupported -> RoutinesListState.UNSUPPORTED
    model.routines == null && model.loadError != null -> RoutinesListState.LOAD_FAILED
    model.routines == null -> RoutinesListState.LOADING
    model.routines.isEmpty() -> RoutinesListState.EMPTY
    else -> RoutinesListState.POPULATED
}

const val ROUTINES_CAPTION =
    "Prompts the journal sends your Coordinator on a schedule, or when something needs a look. Pause one to stop it without losing it."

/// "Next: today at 18:30" (scheduled, active) and "Last: 2 h ago ·
/// Delivered". A failed last outcome gets its own line instead (the row
/// shows it in the error colour).
fun routineStatusLines(
    routine: Routine,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<String> {
    val lines = mutableListOf<String>()
    val next = routine.nextAt
    if (next != null && routine.enabled && !routine.isTriggered) {
        lines += "Next: " + routineWhen(next, now, zone, locale)
    }
    val last = routine.lastFiredAt
    if (last != null) {
        var line = "Last: " + routineAgo(last, now)
        val outcome = routine.lastOutcomeWords
        if (!routine.lastFireFailed && outcome != null) line += " · $outcome"
        lines += line
    } else {
        lines += "Never fired"
    }
    return lines
}

/// "today at 18:30", "tomorrow at 07:05", "Mon 20 Oct at 08:00".
fun routineWhen(date: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val at = date.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    return when (at.toLocalDate()) {
        today -> "today at $time"
        today.plusDays(1) -> "tomorrow at $time"
        else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM", locale)) + " at $time"
    }
}

/// "just now", "5 min ago", "2 h ago", "3 d ago".
fun routineAgo(date: Instant, now: Instant): String {
    val age = Duration.between(date, now).coerceAtLeast(Duration.ZERO)
    return when {
        age.toMinutes() < 1 -> "just now"
        age.toHours() < 1 -> "${age.toMinutes()} min ago"
        age.toDays() < 1 -> "${age.toHours()} h ago"
        else -> "${age.toDays()} d ago"
    }
}

/// Settings → Coordinator → Routines: the user's Coordinator routines,
/// sorted by name, each with what sets it off, its next and last fire, a
/// pause switch and a menu with Run now. A pure leaf view; the host maps
/// the view model into [model] and handles the taps.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesListView(
    model: RoutinesListModel,
    onSelect: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRun: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant? = null,
) {
    PullToRefreshBox(isRefreshing = model.isLoading && model.routines != null, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        when (routinesListState(model)) {
            RoutinesListState.UNSUPPORTED -> RoutinesPlaceholder(
                title = "Routines not available",
                description = "This journal doesn't have routines yet. Update the journal server to use them.",
            )
            RoutinesListState.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            RoutinesListState.LOAD_FAILED -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(
                    "Couldn't load routines: ${model.loadError}",
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRefresh) { Text("Try again") }
            }
            RoutinesListState.EMPTY, RoutinesListState.POPULATED -> LazyColumn(Modifier.fillMaxSize()) {
                val routines = model.routines.orEmpty()
                if (routines.isEmpty()) {
                    item {
                        Text(
                            "No routines yet. Add one, or ask your Coordinator to.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(routines, key = { it.id }) { routine ->
                    Column(Modifier.fillMaxWidth()) {
                        RoutineRowView(
                            routine = routine,
                            isBusy = routine.id in model.busyIDs,
                            now = now ?: Instant.now(),
                            onSelect = { onSelect(routine.id) },
                            onSetEnabled = { onSetEnabled(routine.id, it) },
                            onRun = { onRun(routine.id) },
                        )
                        HorizontalDivider()
                    }
                }
                item {
                    Text(
                        ROUTINES_CAPTION,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                model.loadError?.let { error ->
                    item {
                        Text(
                            "Couldn't refresh: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/// One routine's row: title (with a Paused badge) and name, what sets it
/// off, next and last fire, the pause switch, and a menu with Run now,
/// Pause/Resume and Edit. Tapping the text opens the editor.
@Composable
fun RoutineRowView(
    routine: Routine,
    isBusy: Boolean,
    now: Instant,
    onSelect: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.weight(1f).clickable(onClick = onSelect).testTag("routine.row.${routine.name}")
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    routine.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (routine.enabled) MaterialTheme.colorScheme.onSurface else secondary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!routine.enabled) {
                    Text(
                        "Paused",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = secondary,
                        modifier = Modifier
                            .background(secondary.copy(alpha = 0.15f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(routine.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    if (routine.isTriggered) Icons.Outlined.Bolt else Icons.Outlined.Schedule,
                    contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 1.dp),
                )
                Text(routine.whenWords, style = MaterialTheme.typography.bodySmall)
            }
            routineStatusLines(routine, now).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            if (routine.lastFireFailed) {
                routine.lastOutcomeWords?.let { outcome ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Text(outcome, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Switch(
            checked = routine.enabled,
            onCheckedChange = onSetEnabled,
            enabled = !isBusy,
            modifier = Modifier
                .testTag("routine.enabled.${routine.name}")
                .semantics { contentDescription = if (routine.enabled) "Active" else "Paused" },
        )
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !isBusy) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More for ${routine.title}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Run now") }, onClick = { menuOpen = false; onRun() })
                DropdownMenuItem(
                    text = { Text(if (routine.enabled) "Pause" else "Resume") },
                    onClick = { menuOpen = false; onSetEnabled(!routine.enabled) },
                )
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onSelect() })
            }
        }
    }
}

@Composable
private fun RoutinesPlaceholder(title: String, description: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
