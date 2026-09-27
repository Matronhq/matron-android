package chat.matron.android.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

/// Floating "jump to my last message" affordance for the chat timeline.
/// Hosted inside [ChatTopTrailingControls], in the Stop pill's slot when no
/// turn is running, or beneath it when one is — never in the header toolbar
/// (apple #211). Same shape language and tint as [StopTurnButton] and
/// [JumpToBottomButton] so all three floating chat controls read as one
/// family. Ported from matron-apple's `JumpToLastOwnMessageButton`.
@Composable
fun JumpToLastOwnMessageButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .shadow(4.dp, CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            Icons.Filled.VerticalAlignTop,
            contentDescription = "Jump to my last message",
            modifier = Modifier.size(24.dp),
        )
    }
}
