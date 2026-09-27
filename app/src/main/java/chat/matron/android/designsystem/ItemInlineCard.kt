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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.TrackerAttachment

// Inline timeline rendering of a tracker marker event (spec 2026-09-08,
// apple #186 / #210). `created`/`closed` render as a compact card;
// `commented`/`reopened` render as a one-line muted note. Both share this
// composable so the timeline row can hand the marker over without branching
// of its own. `reordered`/`updated` never reach here — `JournalTimelineMapper`
// hides them before a `TimelineItem` is even built.
//
// The pure helpers below are what the tests pin (this repo's replacement for
// apple's `ItemInlineCardSnapshotTests` / `ItemInlineCardTests`).

/// Status pill under the card title: `(text, urgent)`. Closed markers show
/// "Done" (plus the resolution label when the payload carries one); open
/// markers show whose court the ball is in — "Needs you" is deliberately the
/// only urgent-coloured state, matching `TrackerItem.needsUser`'s definition
/// of the badge the rest of the tracker UI uses. `null` for an open marker
/// awaiting nobody.
fun itemInlinePill(marker: ItemMarkerEvent): Pair<String, Boolean>? = when {
    marker.action == ItemMarkerEvent.Action.CLOSED ->
        ("Done" + (marker.resolution?.let { " · ${ItemGlyph.label(it)}" } ?: "")) to false
    marker.awaiting == ItemAwaiting.USER -> "Needs you" to true
    marker.awaiting == ItemAwaiting.AGENT -> "With the agent" to false
    else -> null
}

/// The one-line note for `commented`/`reopened` — "You" for the local user,
/// "Agent" for the bot, matching how the rest of the tracker UI refers to the
/// two `ItemAuthor` cases.
fun itemInlineNoteText(marker: ItemMarkerEvent): String {
    val who = if (marker.by == ItemAuthor.USER) "You" else "Agent"
    return when (marker.action) {
        ItemMarkerEvent.Action.COMMENTED -> "$who replied on #${marker.num} · ${marker.title}"
        ItemMarkerEvent.Action.REOPENED -> "$who reopened #${marker.num} · ${marker.title}"
        else -> "#${marker.num} · ${marker.title}"
    }
}

/// The card's TalkBack announcement: kind, number, title and pill.
fun itemInlineCardAccessibilityLabel(marker: ItemMarkerEvent): String =
    "${ItemGlyph.label(marker.kind)} ${marker.num}, ${marker.title}. ${itemInlinePill(marker)?.first ?: ""}"

/// Whether the marker's comment has anything worth rendering as a block — an
/// empty comment (a bare status transition) still parses with a `Comment`
/// payload but has nothing to show beyond the note/card (apple #210).
fun itemInlineHasVisibleComment(marker: ItemMarkerEvent): Boolean {
    val comment = marker.comment ?: return false
    return comment.body.isNotEmpty() || comment.attachments.isNotEmpty()
}

/// One caption line per attachment under a reply. Deliberately no
/// "Transcribing…" here: this card renders the marker's frozen snapshot, which
/// never learns the job finished. The live state is in the item thread
/// (`ItemDetailView`).
fun itemInlineAttachmentLine(attachment: TrackerAttachment): String {
    val name = attachment.name.ifEmpty { "attachment" }
    if (attachment.isAudio) {
        var line = "Voice note: $name"
        val transcript = attachment.transcript
        if (!transcript.isNullOrEmpty()) line += " — $transcript"
        return line
    }
    return "Attachment: $name"
}

/// Which shape [ItemInlineCard] draws for a marker.
enum class ItemInlineShape { CARD, NOTE }

fun itemInlineShape(marker: ItemMarkerEvent): ItemInlineShape = when (marker.action) {
    ItemMarkerEvent.Action.CREATED, ItemMarkerEvent.Action.CLOSED -> ItemInlineShape.CARD
    else -> ItemInlineShape.NOTE
}

/// Leading indent for the comment block under the note, lining its text up
/// under the note's own text rather than its leading glyph (14dp icon + 6dp
/// spacing).
private val NoteTextIndent = 20.dp

/// [onOpen] `null` renders the same chrome but tap-inert — the sub-chat pane
/// has nowhere to navigate (same scope decision as apple's `SubChatView`).
@Composable
fun ItemInlineCard(
    marker: ItemMarkerEvent,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onOpenLink: ((String) -> Unit)? = null,
) {
    when (itemInlineShape(marker)) {
        ItemInlineShape.CARD ->
            // The card itself is unchanged by a closing comment: the comment
            // (body and/or attachments) renders as its own block underneath,
            // not inside the card's tap target, so links in it stay tappable.
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Card(marker, onOpen)
                if (itemInlineHasVisibleComment(marker)) {
                    CommentBlock(marker, onOpenLink, Modifier.padding(start = 10.dp))
                }
            }
        ItemInlineShape.NOTE ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Note(marker, onOpen)
                if (itemInlineHasVisibleComment(marker)) {
                    CommentBlock(marker, onOpenLink, Modifier.padding(start = NoteTextIndent))
                }
            }
    }
}

/// The reply body (in full — no line limit) and one caption line per
/// attachment. Deliberately NOT inside the open-item tap target: it sits below
/// the note/card, so `[#65](matron://item/65)`-style links inside the body
/// stay tappable instead of being swallowed by a block-wide open gesture
/// (apple #210).
@Composable
private fun CommentBlock(marker: ItemMarkerEvent, onOpenLink: ((String) -> Unit)?, modifier: Modifier) {
    val comment = marker.comment ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (comment.body.isNotEmpty()) MarkdownText(comment.body, onLinkClick = onOpenLink)
        comment.attachments.forEach { attachment ->
            Text(
                itemInlineAttachmentLine(attachment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Card(marker: ItemMarkerEvent, onOpen: (() -> Unit)?) {
    val pill = itemInlinePill(marker)
    val urgent = marker.awaiting == ItemAwaiting.USER && marker.action != ItemMarkerEvent.Action.CLOSED
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .border(1.dp, if (urgent) MatronOrange.copy(alpha = 0.5f) else Color.Transparent, shape)
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = itemInlineCardAccessibilityLabel(marker) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            ItemGlyph.icon(marker.kind),
            contentDescription = null,
            tint = ItemGlyph.tint(marker.kind),
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${marker.num}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    marker.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pill != null) {
                Text(
                    pill.first,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pill.second) MatronOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun Note(marker: ItemMarkerEvent, onOpen: (() -> Unit)?) {
    val text = itemInlineNoteText(marker)
    Row(
        modifier = Modifier
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            ItemGlyph.icon(marker.kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
