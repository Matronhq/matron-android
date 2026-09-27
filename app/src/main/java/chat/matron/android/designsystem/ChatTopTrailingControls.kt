package chat.matron.android.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/// Pure visibility rule for the "jump to my last message" pill: it shows once
/// the user has scrolled away from the live tail — the same signal as the
/// bottom "jump to latest" pill — and goes away at the tail. (The Apple rule
/// also hides it on the iOS Tasks page; the Android chat has no tasks page,
/// so that input does not exist here.) Port of apple #211's
/// `ChatTopTrailingControls.showsJump`.
fun chatTopTrailingShowsJump(isFollowingTail: Boolean): Boolean = !isFollowingTail

/// Floating top-trailing overlay stack for the chat timeline: the Stop pill
/// (when a turn is running) above the "jump to my last message" pill (when
/// the user has scrolled away from the tail) — or the jump pill alone, in
/// Stop's slot, when no turn is running. This is "the one thing scrolling
/// can't find": jumping back to your own last message stays reachable without
/// living permanently in the header toolbar (Dan, tracker #270).
///
/// Owns the 16dp trailing / 8dp top padding for the whole stack so the two
/// pills line up on one shared trailing edge; [StopTurnButton] no longer
/// carries its own padding since this is its only host. Ported from
/// matron-apple's `ChatTopTrailingControls` (apple #211).
@Composable
fun ChatTopTrailingControls(
    showsStop: Boolean,
    showsJump: Boolean,
    onStop: () -> Unit,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = 16.dp, top = 8.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = showsStop,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            StopTurnButton(onClick = onStop)
        }
        AnimatedVisibility(
            visible = showsJump,
            enter = scaleIn() + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            JumpToLastOwnMessageButton(onClick = onJump)
        }
    }
}
