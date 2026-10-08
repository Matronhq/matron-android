package chat.matron.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/// Wraps a transient [active] flag and produces a derived flag that stays
/// `true` for at least [minDurationMillis] once shown. Used to keep brief
/// loading indicators (e.g. [PaginatingHeader] while `isPaginatingBackward`)
/// visible long enough to be perceptible — a 50ms paginate that completes from
/// local cache otherwise barely registers behind the fade-in.
///
/// Behaviour:
///   - [active] flips `true`  → derived flag flips `true` immediately
///   - [active] flips `false` → derived flag holds `true` for
///     [minDurationMillis], then flips `false` (unless [active] flipped back
///     to `true` in the meantime).
///
/// The hide is driven by a cancellable effect so a flurry of rapid toggles
/// collapses into "true for at least minDurationMillis after the last truth",
/// rather than a stuttering chain of show/hide animations.
@Composable
fun MinDisplayDuration(
    active: Boolean,
    minDurationMillis: Long = 500,
    content: @Composable (Boolean) -> Unit,
) {
    var derived by remember { mutableStateOf(active) }

    LaunchedEffect(active) {
        if (active) {
            derived = true
        } else if (derived) {
            delay(minDurationMillis)
            derived = false
        }
    }

    content(derived)
}
