package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// Thin labelled progress strip for attachment uploads, rendered by the
/// composer above the input while a send's upload is in flight. Exists because
/// a slow uplink turns a multi-MB screenshot into many seconds of dead air — a
/// determinate bar plus a percentage is the difference between "working" and
/// "frozen". Takes plain values (label + fraction) so this surface stays free
/// of any view-model dependency. Ported from matron-apple's
/// `UploadProgressBar`.
@Composable
fun UploadProgressBar(label: String, fraction: Double, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0.0, 1.0)
    val percent = (clamped * 100).toInt()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $percent percent uploaded"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            progress = { clamped.toFloat() },
            modifier = Modifier.weight(1f),
        )
        // Two texts, not one: the filename may truncate (pasted photos get
        // UUID temp names wider than the screen), the percent never does.
        // The label is middle-truncated so the extension survives, and gets
        // at most half the row so the bar keeps its share; an end ellipsis
        // backs that up on very narrow widths (port of apple #156).
        Text(
            uploadLabelDisplay(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 8.dp),
        )
        Text(
            "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/// Maximum characters of the upload label before it is middle-truncated.
internal const val UPLOAD_LABEL_MAX_CHARS = 28

/// Middle-truncates [label] to [maxChars] with a single ellipsis, keeping the
/// head and the tail (so a filename's extension stays readable). Compose's
/// `TextOverflow` has no middle mode at our BOM, so this is done in the
/// string; pure and top-level so it's unit-testable. Deviation from Apple's
/// `.truncationMode(.middle)`, which is width-based.
internal fun uploadLabelDisplay(label: String, maxChars: Int = UPLOAD_LABEL_MAX_CHARS): String {
    if (label.length <= maxChars) return label
    val keep = maxChars - 1
    val tail = keep / 2
    val head = keep - tail
    return label.take(head) + "\u2026" + label.takeLast(tail)
}
