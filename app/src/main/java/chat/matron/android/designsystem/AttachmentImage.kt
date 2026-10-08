package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/// Design-system primitive for an image attachment in the chat timeline.
/// Loads [model] (a URL string, `File`, `Uri`, …) via Coil, showing a
/// placeholder box while it resolves or if it fails, caps to a 280×280 box
/// with rounded corners, and surfaces an optional [caption] underneath.
/// The caption renders as normal message body text — it IS the message the
/// sender typed alongside the image, not metadata about it (same styling as
/// [AttachmentFile]'s caption).
///
/// [onTap] (when wired) fires on the whole image box — including the
/// placeholder state, so the user can open the (eventually-resolved) image
/// before the bytes have rendered.
@Composable
fun AttachmentImage(
    model: Any?,
    modifier: Modifier = Modifier,
    placeholder: String = "Image",
    caption: String? = null,
    onTap: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val boxModifier = Modifier
            .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)

        SubcomposeAsyncImage(
            model = model,
            contentDescription = caption ?: placeholder,
            modifier = boxModifier,
            contentScale = ContentScale.Fit,
            loading = { ImagePlaceholder(placeholder) },
            error = { ImagePlaceholder(placeholder) },
        )

        if (caption != null && caption.isNotEmpty()) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ImagePlaceholder(label: String) {
    Box(
        Modifier
            .size(160.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
