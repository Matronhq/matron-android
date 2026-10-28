package chat.matron.android.designsystem

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlin.math.abs

/// Fullscreen image viewer presented from a chat attachment tap or the media
/// grid, hosted in a full-bleed [Dialog]. Steps through [gallery] with a
/// horizontal swipe at fit scale (swipe-down still dismisses; pinch/pan is
/// unchanged once zoomed); a "3 of 12" counter shows when there's more than
/// one image; ends are hard stops; zoom resets per image; neighbours
/// prefetch; expired/missing images show an in-place placeholder so the
/// counter matches the grid. Pinch-to-zoom (clamped 1×–4×) via a
/// transformable; a top-trailing close button gives a no-gesture path out
/// for TalkBack users. Port of matron-apple's viewer changes in #175.
/// A Download button beside it saves the shown image's original bytes
/// through the system save picker ([ImageDownload]), as the web viewer's
/// header Download does; it is hidden while there is nothing to save.
@Composable
fun AttachmentFullscreenViewer(gallery: ImageGallery, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var index by remember(gallery) { mutableIntStateOf(gallery.startIndex) }
        // Resolved bytes per entry index; neighbours are prefetched and
        // anything outside the retain radius is dropped on a step.
        val resolved = remember(gallery) {
            mutableStateOf<Map<Int, Any?>>(mapOf(gallery.startIndex to gallery.initial))
        }
        var scale by remember(index) { mutableFloatStateOf(1f) }
        var offsetY by remember(index) { mutableFloatStateOf(0f) }
        // A pinch's two-finger centroid also reaches the drag detector. Two
        // guards keep it from paging: the classifier reads the LIVE scale at
        // drag end (a zoomed image never pages), and any pinch during the
        // touch marks it so a drifting pinch that lands back near 1× is
        // still not a swipe. The mark is reset on the FIRST finger down, not
        // at drag start — drag start fires only after touch slop, by which
        // time a pinch may already have run and been consumed (Bugbot, #58).
        var pinchedThisGesture by remember(index) { mutableStateOf(false) }
        val transformState = rememberTransformableState { zoomChange, _, _ ->
            if (zoomChange != 1f) pinchedThisGesture = true
            scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        val entries = gallery.entries
        val count = entries.size

        LaunchedEffect(gallery, index) {
            val keep = ImageGalleryNavigation.retainedIndices(index, count, RETAIN_RADIUS)
            resolved.value = resolved.value.filterKeys { it in keep }
            val wanted = listOf(index) + ImageGalleryNavigation.preloadIndices(index, count)
            for (i in wanted) {
                if (resolved.value.containsKey(i)) continue
                val entry = entries[i]
                val url = entry.url
                val bytes = if (entry.expired || url == null) null else gallery.load(url)
                resolved.value = resolved.value + (i to bytes)
            }
        }

        val entry = entries[index]
        val model = resolved.value[index]
        // No key yet means the fetch hasn't answered; a stored null is a
        // fetch that failed (or an expired entry) and is final (Bugbot).
        val stillLoading = !resolved.value.containsKey(index)

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        // The bytes waiting for the picker to name a destination, and
        // whether a save is between the tap and its result.
        var pendingSave by remember { mutableStateOf<ByteArray?>(null) }
        var saving by remember { mutableStateOf(false) }
        val saveLauncher = rememberLauncherForActivityResult(ImageDownload.SaveContract) { uri ->
            val bytes = pendingSave
            pendingSave = null
            if (uri == null || bytes == null) { saving = false; return@rememberLauncherForActivityResult }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
                }
                saving = false
                Toast.makeText(context, if (ok) "Image saved" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
            }
        }
        fun download() {
            if (saving) return
            saving = true
            val shown = model
            val name = entry.name
            scope.launch {
                val bytes = ImageDownload.originalBytes(shown, context, gallery.load)
                if (bytes == null) {
                    saving = false
                    Toast.makeText(context, "Couldn't download the image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                pendingSave = bytes
                saveLauncher.launch(ImageDownload.request(name, bytes))
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val gestures = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = offsetY
                }
                .pointerInput(gallery, index) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pinchedThisGesture = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // A second finger at any point in the touch is a
                            // pinch, whether or not the zoom callback ran.
                            if (event.changes.count { it.pressed } > 1) pinchedThisGesture = true
                        } while (event.changes.any { it.pressed })
                    }
                }
                .transformable(transformState)
                .pointerInput(gallery, index) {
                    var dx = 0f
                    var dy = 0f
                    detectDragGestures(
                        onDragStart = { dx = 0f; dy = 0f },
                        onDragEnd = {
                            val zoomed = scale > MIN_SCALE + 0.01f
                            if (zoomed || pinchedThisGesture) {
                                offsetY = 0f
                                return@detectDragGestures
                            }
                            when (ImageGalleryNavigation.swipeIntent(dx, dy, SWIPE_THRESHOLD_PX)) {
                                ImageGalleryNavigation.SwipeIntent.NEXT ->
                                    ImageGalleryNavigation.step(index, 1, count)?.let { index = it }
                                ImageGalleryNavigation.SwipeIntent.PREVIOUS ->
                                    ImageGalleryNavigation.step(index, -1, count)?.let { index = it }
                                ImageGalleryNavigation.SwipeIntent.DISMISS -> onDismiss()
                                ImageGalleryNavigation.SwipeIntent.NONE -> {}
                            }
                            offsetY = 0f
                        },
                        onDragCancel = { offsetY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dx += dragAmount.x
                            dy += dragAmount.y
                            // Only a downward drag on an un-zoomed image shifts
                            // it — a swipe down dismisses.
                            val zoomed = scale > MIN_SCALE + 0.01f
                            if (!zoomed && !pinchedThisGesture && abs(dy) > abs(dx)) offsetY = dy.coerceAtLeast(0f)
                        },
                    )
                }
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = "Image preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(gestures),
                )
            } else {
                // A reaped or unresolved image keeps its place so the counter
                // matches the grid the user came from; one still on its way
                // shows a spinner rather than a verdict.
                Box(Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.Center) {
                    if (stillLoading) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    } else {
                        Text(
                            if (entry.expired) "Image expired" else "Image unavailable",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            ImageGalleryNavigation.counterLabel(index, count)?.let { label ->
                // Same 48dp row as the controls on the right, so the counter
                // lines up with Download and Close.
                Box(
                    Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp).height(48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Nothing to save for an expired, failed or still-loading image.
                if (model != null && !entry.expired) {
                    OutlinedButton(
                        onClick = ::download,
                        enabled = !saving,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.4f),
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.5f),
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            if (saving) "Saving…" else "Download",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Close image preview", tint = Color.White)
                }
            }
        }
    }
}

/// The one-image viewer, for call sites with nothing to step through.
/// [model] is any Coil-loadable handle (URL string, `File`, `Uri`, bytes).
@Composable
/// [name] is the suggested file name for Download; [load] fetches the
/// original when [model] is a URL string.
fun AttachmentFullscreenViewer(
    model: Any?,
    onDismiss: () -> Unit,
    name: String? = null,
    load: suspend (String) -> Any? = { null },
) {
    AttachmentFullscreenViewer(gallery = remember(model, name) { ImageGallery.single(model, name, load) }, onDismiss = onDismiss)
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
/// Same "intentional swipe" feel as an interactive sheet dismiss.
private const val SWIPE_THRESHOLD_PX = 300f
/// Resolved neighbours kept in memory either side of the shown image.
private const val RETAIN_RADIUS = 1
