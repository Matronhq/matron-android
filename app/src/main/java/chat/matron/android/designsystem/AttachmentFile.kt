package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/// Design-system primitive for a non-image file attachment in the chat
/// timeline. Renders a generic doc icon, the [filename], and (optionally) the
/// formatted [sizeBytes]. Tapping the chip invokes [onTap] (e.g. to
/// share/export). An optional [caption] renders underneath, outside the chip —
/// it's the message, not a handle for opening the file.
///
/// While [isLoading] the icon becomes a spinner and the subtitle reads
/// "Downloading…" — a large attachment takes double-digit seconds to pull
/// through the journal server, and a tap with no visible reaction reads as a
/// dead tap (port of apple #138). The icon slot keeps its 32dp frame so the
/// chip doesn't reflow when the state flips.
///
/// [isExpired] (port of apple #139): the blob was reaped server-side (journal
/// media reaper) — permanently gone. The chip dims, the subtitle reads
/// "Expired", and there is no tap affordance — a silent no-op tap is the exact
/// bug this chip family exists to avoid.
@Composable
fun AttachmentFile(
    filename: String,
    sizeBytes: Long?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    isLoading: Boolean = false,
    isExpired: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MatronThemeColors.current.codeBg)
                .then(if (onTap != null && !isExpired) Modifier.clickable { onTap() } else Modifier)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (isExpired) {
                    // Clock-over-doc is Apple's `doc.badge.clock`; Material has
                    // no composite, so the Schedule glyph follows ToolCallCard's
                    // "Output expired" precedent.
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                } else if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isExpired) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = attachmentFileSubtitle(
                    isExpired = isExpired,
                    isLoading = isLoading,
                    sizeBytes = sizeBytes,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (caption != null && caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/// The chip's subtitle line: "Expired" for a reaped blob (permanent — wins over
/// everything), "Downloading…" while the blob fetch is in flight, else the
/// formatted size (or nothing when the size is unknown). Pure so the state
/// precedence is unit-testable — the Apple PRs pin the same states with
/// snapshot tests (`AttachmentFileSnapshotTests.test_downloading`/
/// `test_expired`, apple #138/#139), which this project's conventions replace
/// with pure-function tests.
internal fun attachmentFileSubtitle(
    isLoading: Boolean,
    sizeBytes: Long?,
    isExpired: Boolean = false,
): String? = when {
    isExpired -> "Expired"
    isLoading -> "Downloading…"
    sizeBytes != null -> formatFileSize(sizeBytes)
    else -> null
}

/// Decimal (1000-based) size string — "12 bytes", "3.4 kB", "1.2 MB" — matching
/// the tray's `StagedAttachment.formattedSize` so a staged file and a sent one
/// read the same.
private fun formatFileSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes bytes"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1000.0
    var unit = 0
    while (value >= 1000.0 && unit < units.lastIndex) {
        value /= 1000.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}
