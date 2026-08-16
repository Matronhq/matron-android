package chat.matron.android.features.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.ConversationSummaryEntry
import chat.matron.android.viewmodels.ChatViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TOC of the conversation: one row per bridge summary pass, newest first.
 * Rows expand to the fuller rolling summary; tapping a row calls [onSelect]
 * with the entry's seq so the caller can jump the transcript there (and
 * dismiss the sheet). Ports Features/Chat/SummariesSheet.swift (apple #124)
 * with the #126 full-contrast expanded detail and the #128 real chevron tap
 * target baked in — the chevron is an [IconButton], whose 48dp minimum touch
 * target is the Compose-native equivalent of the padding-inflated tap shape.
 *
 * Presented from [ChatScreen] inside a ModalBottomSheet, following the
 * [SessionStatusSheet] precedent (no navigation chrome; the sheet's own
 * drag-handle/scrim replaces iOS's "Done" button).
 */
@Composable
fun SummariesSheet(viewModel: ChatViewModel, onSelect: (Long) -> Unit) {
    val entries by viewModel.summaryEntries.collectAsStateWithLifecycle()
    var expandedSeq by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Summaries",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (entries.isEmpty()) {
            SummariesEmptyState()
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(entries, key = { it.seq }) { entry ->
                    SummaryRow(
                        entry = entry,
                        expanded = expandedSeq == entry.seq,
                        onToggleExpand = { expandedSeq = toggleExpandedSeq(expandedSeq, entry.seq) },
                        onSelect = { onSelect(entry.seq) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    entry: ConversationSummaryEntry,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClickLabel = "Jump to this point") { onSelect() }
                .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(entry.toc, style = MaterialTheme.typography.bodyMedium)
            if (expanded && entry.detail.isNotEmpty()) {
                // Full contrast on purpose (apple #126): the expanded detail
                // is the content the user asked for, not a secondary caption.
                Text(entry.detail, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                summaryTimestampLabel(entry.date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.detail.isNotEmpty()) {
            // IconButton's 48dp minimum touch target is the point (apple
            // #128): a bare 16dp icon was nearly untappable.
            IconButton(onClick = onToggleExpand, modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide detail" else "Show detail",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummariesEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("No summaries yet", style = MaterialTheme.typography.titleSmall)
        Text(
            "They appear as the conversation grows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/// One expanded row at a time; tapping the expanded row's chevron collapses it.
/// Mirrors the iOS sheet's `toggle(_ seq:)`.
internal fun toggleExpandedSeq(current: Long?, tapped: Long): Long? =
    if (current == tapped) null else tapped

/// Row timestamp caption, mirroring the iOS
/// `.dateTime.month().day().hour().minute()` format ("Aug 16, 2:30 PM").
/// [zone]/[locale] injectable for tests.
internal fun summaryTimestampLabel(
    date: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern("MMM d, h:mm a", locale).format(date.atZone(zone))
