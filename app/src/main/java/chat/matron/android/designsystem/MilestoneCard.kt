package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.MilestoneMarkerEvent
import chat.matron.android.events.MissionMarkerEvent
import chat.matron.android.models.MilestoneKind

// Inline timeline rendering of the two mission marker events (spec
// 2026-09-10, apple #209): a `milestone` renders as a compact card — the
// card IS the jump target's own row, so it needs no navigation of its own
// beyond opening the mission page — and a `mission` marker as a one-line
// notice. The mission is named through `missionLabel`, which falls back to
// `#N` when the journal sieved the title away at write time. Never render
// `missionTitle` directly. The pure helpers below are what the tests pin.

/// "Your input · Missions & milestones" / "Progress · #61".
fun milestoneCardSubtitle(marker: MilestoneMarkerEvent): String =
    "${MissionGlyph.label(marker.kind)} · ${marker.missionLabel}"

/// The card's TalkBack announcement.
fun milestoneCardAccessibilityLabel(marker: MilestoneMarkerEvent): String =
    "Milestone ${marker.num}, ${marker.title}. ${milestoneCardSubtitle(marker)}. Opens the mission"

/// The one-line notice for a `mission` marker. Same title-fallback rule as
/// the card: the name rides along only when the marker carried one.
fun missionNoticeText(marker: MissionMarkerEvent): String {
    val named = marker.title?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
    return when (marker.action) {
        MissionMarkerEvent.Action.CREATED -> "🏁 Mission #${marker.num} started$named"
        MissionMarkerEvent.Action.JOINED -> "🏁 Joined mission #${marker.num}$named"
        MissionMarkerEvent.Action.UPDATED -> "🏁 Mission #${marker.num} renamed$named"
        MissionMarkerEvent.Action.CLOSED ->
            if (marker.openItemNums.isEmpty()) "🏁 Mission #${marker.num} closed$named"
            else "🏁 Mission #${marker.num}$named closed over " + marker.openItemNums.joinToString(", ") { "#$it" }
    }
}

/// A `milestone` marker as a compact card: kind glyph, `#num`, title, body
/// preview and the "kind · mission" subtitle; a `user_input` milestone gets
/// the orange border the needs-you card uses. [onOpen] `null` (previews,
/// the sub-chat pane) leaves the card drawn but tap-inert.
@Composable
fun MilestoneCard(marker: MilestoneMarkerEvent, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val border = if (marker.kind == MilestoneKind.USER_INPUT) MatronOrange.copy(alpha = 0.5f) else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .border(1.dp, border, shape)
            .let { if (onOpen != null) it.clickable(onClickLabel = "Open the mission") { onOpen() } else it }
            .semantics(mergeDescendants = true) { contentDescription = milestoneCardAccessibilityLabel(marker) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            MissionGlyph.icon(marker.kind), contentDescription = null,
            tint = MissionGlyph.tint(marker.kind), modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Text("#${marker.num}", style = MaterialTheme.typography.labelSmall, color = secondary, modifier = Modifier.padding(top = 2.dp))
                Text(marker.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (marker.body.isNotEmpty()) {
                Text(marker.body.replace("\n", " "), style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(milestoneCardSubtitle(marker), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = secondary)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp),
        )
    }
}

/// A `mission` marker as a one-line muted notice, tappable to the mission.
@Composable
fun MissionNotice(marker: MissionMarkerEvent, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Text(
        missionNoticeText(marker),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .let { if (onOpen != null) it.clickable(onClickLabel = "Open the mission") { onOpen() } else it }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
