package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// Numeric unread-count pill rendered on the trailing edge of a chat-list row.
/// Matches the standard iOS Messages / Mail visual: a small accent-tinted
/// capsule with white text. Values above [cap] render as `cap+` (e.g. `99+`)
/// so a runaway notification queue doesn't blow out the row's layout.
///
/// Renders nothing for `count <= 0` so callers don't need a surrounding `if` —
/// the unread row keeps the same layout shape as the no-unread row.
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, cap: Int = 99) {
    if (count <= 0) return

    val displayText = if (count > cap) "$cap+" else "$count"
    val label = if (count == 1) "1 unread message" else "$displayText unread messages"

    Text(
        text = displayText,
        modifier = modifier
            .semantics { contentDescription = label }
            // `minWidth: 18` matches Messages' shortest pill so single-digit
            // counts don't collapse to a near-circle.
            .widthIn(min = 18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
}
