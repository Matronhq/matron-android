package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/// What the voice-note bar shows. Values only: the composers map
/// `VoiceRecorder.State` onto it.
sealed interface VoiceNoteBarMode {
    /// The microphone is still coming up: nothing is captured yet, so there
    /// is Cancel but no Send.
    data object Starting : VoiceNoteBarMode

    /// Capturing since [start]; [isPaused] while an interruption holds it.
    /// [pausedTotal] is time earlier interruptions held it, which the clock
    /// leaves out so it shows what was captured.
    data class Recording(
        val start: Instant,
        val isPaused: Boolean = false,
        val pausedTotal: Duration = Duration.ZERO,
    ) : VoiceNoteBarMode
}

/// The chat composer's voice-note strip. A grey dot and "Starting
/// microphone…" until the microphone delivers sound, then a red dot and the
/// running time: the bar only says "Recording" once audio is reaching the
/// file. While an interruption holds the capture it says so: "Recording…"
/// would claim to be capturing a call the recorder is not hearing. Send
/// still delivers what was captured up to the pause.
@Composable
fun VoiceNoteRecordingBar(
    mode: VoiceNoteBarMode,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Instant = Instant::now,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (mode) {
            VoiceNoteBarMode.Starting -> {
                VoiceNoteDot(live = false)
                Text(
                    voiceNoteBarLabel(mode),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is VoiceNoteBarMode.Recording -> if (mode.isPaused) {
                Icon(Icons.Default.Pause, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    voiceNoteBarLabel(mode),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                VoiceNoteDot(live = true)
                val elapsed = rememberElapsed(mode.start.plus(mode.pausedTotal), now)
                Text(
                    voiceNoteBarLabel(mode, elapsed),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
        val canSend = mode is VoiceNoteBarMode.Recording
        IconButton(onClick = onSend, enabled = canSend) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send voice note",
                tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/// Grey while the microphone starts, red once it is recording.
@Composable
fun VoiceNoteDot(live: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            .semantics { contentDescription = if (live) "Recording" else "Starting microphone" },
    )
}

/// Seconds since [start], ticking once a second.
@Composable
private fun rememberElapsed(start: Instant, now: () -> Instant): Duration {
    var elapsed by remember(start) { mutableStateOf(Duration.between(start, now())) }
    LaunchedEffect(start) {
        while (true) {
            elapsed = Duration.between(start, now())
            delay(1_000L - elapsed.toMillis().mod(1_000L))
        }
    }
    return elapsed
}

/// Copy for the voice-note strip: truthful about a microphone still coming
/// up, a capture an interruption has paused (a call, another app on the
/// mic), and one that is live.
fun voiceNoteBarLabel(mode: VoiceNoteBarMode, elapsed: Duration = Duration.ZERO): String = when (mode) {
    VoiceNoteBarMode.Starting -> "Starting microphone…"
    is VoiceNoteBarMode.Recording ->
        if (mode.isPaused) "Recording paused" else "Recording ${formatVoiceNoteElapsed(elapsed)}"
}

/// `m:ss`, never negative.
fun formatVoiceNoteElapsed(elapsed: Duration): String {
    val seconds = elapsed.seconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
