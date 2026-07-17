package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/// Fullscreen image viewer presented from a chat attachment tap, hosted in a
/// full-bleed [Dialog]. Pinch-to-zoom (clamped 1×–4×) via a transformable, and
/// a single-finger swipe-down past a threshold dismisses via [onDismiss]; a
/// top-trailing close button gives a no-gesture path out for TalkBack users.
///
/// [model] is any Coil-loadable handle (URL string, `File`, `Uri`).
@Composable
fun AttachmentFullscreenViewer(model: Any?, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoomChange, _, _ ->
            scale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = "Image preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationY = offsetY
                    }
                    .transformable(transformState)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                if (offsetY > DISMISS_THRESHOLD_PX) onDismiss() else offsetY = 0f
                            },
                            onVerticalDrag = { _, dragAmount ->
                                // Only a downward drag on an un-zoomed image
                                // shifts it — a swipe down dismisses.
                                if (scale <= MIN_SCALE + 0.01f) {
                                    offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                                }
                            },
                        )
                    },
            )

            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Filled.Cancel, contentDescription = "Close image preview", tint = Color.White)
            }
        }
    }
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 4f
/// Same "intentional swipe" feel as an interactive sheet dismiss.
private const val DISMISS_THRESHOLD_PX = 300f
