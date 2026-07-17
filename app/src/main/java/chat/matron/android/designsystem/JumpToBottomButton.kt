package chat.matron.android.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

/// Floating "jump to latest message" affordance for the chat timeline. Shown
/// as an overlay in the bottom-trailing corner whenever the user has scrolled
/// away from the live tail; tapping invokes [onClick], which the host binds to
/// a scroll-to-bottom helper.
@Composable
fun JumpToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 16.dp, bottom = 8.dp)
            .size(44.dp)
            .shadow(4.dp, CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            Icons.Filled.ArrowDownward,
            contentDescription = "Jump to latest",
            modifier = Modifier.size(24.dp),
        )
    }
}
