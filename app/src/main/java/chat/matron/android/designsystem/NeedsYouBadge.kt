package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
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
import androidx.compose.ui.unit.dp

/// The text a needs-you badge shows: capped at `99+` like [UnreadBadge].
fun needsYouBadgeText(count: Int): String = if (count > 99) "99+" else "$count"

/// The badge's accessibility label.
fun needsYouBadgeLabel(count: Int): String = if (count == 1) "1 item needs you" else "$count items need you"

/// "The agent is waiting on you" count — sibling of [UnreadBadge] with the
/// same font/padding/minWidth metrics so the two can sit together without a
/// height change, only an orange tint (and the leading glyph) distinguishing
/// it. Renders nothing for `count <= 0`. Ported from matron-apple's
/// `NeedsYouBadge`.
@Composable
fun NeedsYouBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Row(
        modifier = modifier
            .semantics { contentDescription = needsYouBadgeLabel(count) }
            .widthIn(min = 18.dp)
            .clip(CircleShape)
            .background(MatronOrange)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Help, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            needsYouBadgeText(count),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
