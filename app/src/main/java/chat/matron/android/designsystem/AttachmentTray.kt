package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.StagedAttachment
import coil.compose.AsyncImage

private val CHIP_SIDE = 56.dp

/// The row of attachments waiting above the composer, shown between picking
/// something and sending it. Empty by design when there's nothing staged: the
/// caller can render it unconditionally and it takes no space. [onRemove] is
/// keyed by [StagedAttachment.id].
@Composable
fun AttachmentTray(
    attachments: List<StagedAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    Row(
        modifier
            .height(CHIP_SIDE + 16.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            StagedAttachmentChip(attachment) { onRemove(attachment.id) }
        }
    }
}

/// One staged attachment: an image preview, or a labelled chip for anything
/// else, with a remove button hanging over the top-trailing corner.
@Composable
private fun StagedAttachmentChip(attachment: StagedAttachment, onRemove: () -> Unit) {
    val label = "${if (attachment.isImage) "Image" else "File"} attachment, " +
        "${attachment.filename}, ${attachment.formattedSize}"

    Box(
        Modifier
            .padding(top = 6.dp, end = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Box(
            Modifier
                .height(CHIP_SIDE)
                .clip(RoundedCornerShape(8.dp))
                .background(MatronThemeColors.current.codeBg),
            contentAlignment = Alignment.Center,
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachment.file,
                    contentDescription = null,
                    modifier = Modifier.size(CHIP_SIDE),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Row(
                    Modifier.widthIn(max = 160.dp).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            attachment.filename,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            attachment.formattedSize,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
        ) {
            Icon(
                Icons.Filled.Cancel,
                contentDescription = "Remove ${attachment.filename}",
                // Two-tone so the glyph stays legible against a photo of any
                // colour: white glyph over a dark scrim.
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
            )
        }
    }
}
