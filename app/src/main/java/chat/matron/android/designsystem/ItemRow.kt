package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerItem
import coil.compose.AsyncImage

/// `#61` when the item belongs to a mission, else `null`. Deliberately bare:
/// `#61` may name an item, a mission or a milestone, and an item can carry a
/// `missionNum` for a mission this device cannot read, so the chip never
/// tries to resolve a title.
fun itemMissionChipText(item: TrackerItem): String? = item.missionNum?.let { "#$it" }

/// The row's one-line state caption, or `null` for a plain open item.
fun itemRowStatusCaption(item: TrackerItem): String? = when {
    item.needsUser -> "Needs you"
    item.state == ItemState.CLOSED && item.resolution != null -> ItemGlyph.label(item.resolution)
    item.awaiting == ItemAwaiting.AGENT -> "With the agent"
    else -> null
}

/// The row's full TalkBack announcement. Pure so the rule is pinned without
/// rendering: `semantics(mergeDescendants)` with an explicit
/// `contentDescription` REPLACES the merged children, so every fact the
/// reader should hear — the mission chip included — is folded in here.
fun itemRowAccessibilityLabel(item: TrackerItem): String =
    "${ItemGlyph.label(item.kind)} ${item.num}, ${item.title}" +
        (if (item.needsUser) ", needs you" else "") +
        (itemMissionChipText(item)?.let { ", mission $it" } ?: "")

/// One tracker item in the list: kind glyph, `#num`, title, a one-line body
/// preview, origin (All scope), state caption, comment count, and a thumbnail
/// of the first image attachment. Ported from matron-apple's `ItemRow`.
/// [thumbnail] is a Coil model (bytes, URL, file) or `null`.
@Composable
fun ItemRow(
    item: TrackerItem,
    modifier: Modifier = Modifier,
    origin: String? = null,
    thumbnail: Any? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = itemRowAccessibilityLabel(item) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            ItemGlyph.icon(item.kind),
            contentDescription = null,
            tint = ItemGlyph.tint(item.kind),
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Text(
                    "#${item.num}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.body.isNotEmpty()) {
                Text(
                    item.body.replace("\n", " "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val tertiary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                if (origin != null) {
                    Text(
                        origin, style = MaterialTheme.typography.labelSmall, color = tertiary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
                itemMissionChipText(item)?.let { chip ->
                    Text(chip, style = MaterialTheme.typography.labelSmall, color = tertiary)
                }
                itemRowStatusCaption(item)?.let { caption ->
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (item.needsUser) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item.needsUser) MatronOrange else tertiary,
                    )
                }
                if (item.commentCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = tertiary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${item.commentCount}", style = MaterialTheme.typography.labelSmall, color = tertiary)
                    }
                }
            }
        }
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else if (item.hasImage) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/// A single "Pending" row: muted kind glyph + title + a "Sending…" /
/// "Failed — will retry" caption. Deliberately not [ItemRow]-based — the row
/// has no `#num` (the server hasn't minted one yet) and needs a send-state
/// caption [ItemRow] has no slot for.
@Composable
fun PendingItemRow(kind: ItemKind, title: String, isFailed: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = pendingItemRowLabel(title, isFailed) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            ItemGlyph.icon(kind), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                pendingItemRowCaption(isFailed),
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailed) MatronRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun pendingItemRowCaption(isFailed: Boolean): String = if (isFailed) "Failed — will retry" else "Sending…"

fun pendingItemRowLabel(title: String, isFailed: Boolean): String =
    "$title, ${if (isFailed) "failed, will retry" else "sending"}"
