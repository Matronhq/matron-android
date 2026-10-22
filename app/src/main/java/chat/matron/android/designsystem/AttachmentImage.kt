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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipDefaults
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
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
///
/// With [filename] or [sizeBytes] (item threads, port of apple #101) the
/// image gets a `name · size` line right under it: see [AttachmentNameLine].
@Composable
fun AttachmentImage(
    model: Any?,
    modifier: Modifier = Modifier,
    placeholder: String = "Image",
    caption: String? = null,
    filename: String? = null,
    sizeBytes: Long? = null,
    onTap: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val boxModifier = Modifier
            .sizeIn(maxWidth = 280.dp, maxHeight = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)

        CappedToFirstWidth {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = caption ?: filename?.takeIf { it.isNotBlank() } ?: placeholder,
                modifier = boxModifier,
                contentScale = ContentScale.Fit,
                loading = { ImagePlaceholder(placeholder) },
                error = { ImagePlaceholder(placeholder) },
            )
            if (filename != null || sizeBytes != null) {
                AttachmentNameLine(filename.orEmpty(), sizeBytes)
            }
        }

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

/// The `name · size` line under an image in an item thread. The name is
/// truncated in the middle so the extension stays (Compose 1.7 has no
/// `TextOverflow.MiddleEllipsis`, so the head ellipsizes and the tail never
/// does), the size never truncates, and a long-press shows the full name.
/// Just the size when the attachment has no name.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentNameLine(filename: String, sizeBytes: Long?, modifier: Modifier = Modifier) {
    val name = filename.trim()
    val size = sizeBytes?.let(::formatFileSize)
    val full = listOfNotNull(name.ifEmpty { null }, size).joinToString(" · ")
    if (full.isEmpty()) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(full) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        val (head, tail) = splitFilenameForMiddleTruncation(name)
        NameLineLayout(Modifier.clearAndSetSemantics { contentDescription = full }) {
            Text(if (size != null && name.isNotEmpty()) " · $size" else size.orEmpty(), style = style, color = color, maxLines = 1, softWrap = false)
            Text(tail, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(head, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/// One row of size, tail and head, laid out head-tail-size but measured
/// size first, then tail, then head, each in what the earlier ones left: the
/// size always shows, the tail gives way only once the head is gone.
@Composable
private fun NameLineLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        var left = constraints.maxWidth
        val (size, tail, head) = measurables.map { m ->
            m.measure(constraints.copy(minWidth = 0, maxWidth = left.coerceAtLeast(0))).also { left -= it.width }
        }
        val width = head.width + tail.width + size.width
        layout(width, maxOf(head.height, tail.height, size.height)) {
            head.placeRelative(0, 0)
            tail.placeRelative(head.width, 0)
            size.placeRelative(head.width + tail.width, 0)
        }
    }
}

/// A filename split for middle truncation: `head` may be cut with an
/// ellipsis, `tail` (the last few characters of the stem and the extension)
/// always shows, so "divider_4867x3071.png" narrows to "divid…3071.png"
/// rather than losing its extension.
internal fun splitFilenameForMiddleTruncation(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= 10) name.length - dot else 0
    val cut = maxOf(0, name.length - extension - 4)
    return name.substring(0, cut) to name.substring(cut)
}

/// Lays its children out in a column, every child after the first measured no
/// wider than the first: the name line under an image never widens it.
@Composable
private fun CappedToFirstWidth(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val first = measurables.first().measure(loose)
        val gap = 4.dp.roundToPx()
        val rest = measurables.drop(1).map { it.measure(loose.copy(maxWidth = first.width)) }
        val height = first.height + rest.sumOf { gap + it.height }
        layout(first.width, height) {
            first.placeRelative(0, 0)
            var y = first.height
            rest.forEach { y += gap; it.placeRelative(0, y); y += it.height }
        }
    }
}
