package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.SessionStatus

/// Absolute context size (in tokens) past which the compact-context header
/// appears. Absolute, not a fraction of the model's window: the concern is
/// cost/latency/recall at large sizes, which a 1M-window model shares.
const val COMPACT_HEADER_TOKEN_THRESHOLD = 200_000

/// Whether the compact-context header should show for [context]. Null (no status
/// yet) and exactly-at-threshold do not show; strictly above does.
fun shouldShowCompactHeader(context: SessionStatus.Context?): Boolean =
    context != null && context.tokens > COMPACT_HEADER_TOKEN_THRESHOLD

/// Tappable strip pinned at the top of a large conversation nudging the user to
/// compact it. Always renders the coloured strip for [tokens]; the show/hide
/// decision belongs to the caller (see [shouldShowCompactHeader]). Structurally
/// mirrors [ConnectionStatusBanner]. Tapping calls [onCompact], which sends a
/// bare `/compact`.
@Composable
fun CompactContextBanner(
    tokens: Int,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = "Large conversation, ${UsageMetersFormat.spokenTokens(tokens)} tokens, tap to compact"
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onCompact)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // Merge the children so TalkBack announces the single spoken label
            // below, not the inner Text's abbreviated visible token string.
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Compress,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Large conversation (${UsageMetersFormat.compactTokens(tokens)} tokens) · Tap to compact",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
