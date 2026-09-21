package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import chat.matron.android.models.ItemResolution

/// The menu entry for a resolution — what marking the item that way *does*,
/// not the resulting state.
fun resolveActionLabel(resolution: ItemResolution): String = when (resolution) {
    ItemResolution.DONE -> "Mark done"
    ItemResolution.ANSWERED -> "Mark answered"
    ItemResolution.DECIDED -> "Mark decided"
    ItemResolution.REVERSED -> "Reverse"
    ItemResolution.CANCELLED -> "Dismiss"
}

internal fun resolveActionIcon(resolution: ItemResolution): ImageVector = when (resolution) {
    ItemResolution.DONE, ItemResolution.ANSWERED, ItemResolution.DECIDED -> Icons.Outlined.CheckCircle
    ItemResolution.REVERSED -> Icons.Outlined.Undo
    ItemResolution.CANCELLED -> Icons.Outlined.Cancel
}

/// Whether the control renders at all: an open item with nothing honest to
/// offer (a question nobody has replied to has only "Dismiss", so that still
/// shows) hides it; a closed item always offers Reopen.
fun resolveControlVisible(isOpen: Boolean, resolutions: List<ItemResolution>): Boolean =
    if (isOpen) resolutions.isNotEmpty() else true

/// The item's resolve/reopen menu — an overflow button in the top bar, not a
/// button above the keyboard: a "Close" over the composer read as "close
/// this screen" and offered "answered" on a question nobody had replied to.
/// Closing is the exception, not the next step after typing, so the control
/// lives out of the composer's way and names each outcome as the act it
/// performs ("Mark done", "Dismiss"). The host decides which resolutions are
/// honest to offer (`ItemDetailViewModel.availableResolutions`). Ported from
/// matron-apple's `ItemResolveControl`.
@Composable
fun ItemResolveControl(
    isOpen: Boolean,
    resolutions: List<ItemResolution>,
    isBusy: Boolean,
    onClose: (ItemResolution) -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!resolveControlVisible(isOpen, resolutions)) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, enabled = !isBusy) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Item actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (isOpen) {
                resolutions.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                resolveActionLabel(r),
                                color = if (r == ItemResolution.CANCELLED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        leadingIcon = { Icon(resolveActionIcon(r), contentDescription = null) },
                        onClick = { open = false; onClose(r) },
                    )
                }
            } else {
                DropdownMenuItem(
                    text = { Text("Reopen") },
                    leadingIcon = { Icon(Icons.Outlined.Undo, contentDescription = null) },
                    onClick = { open = false; onReopen() },
                )
            }
        }
    }
}
