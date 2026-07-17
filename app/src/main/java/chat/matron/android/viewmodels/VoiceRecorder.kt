package chat.matron.android.viewmodels

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Records a short voice note to a temporary AAC `.m4a` file for sending as an
/// `audio/*` attachment. Ported from matron-apple's `VoiceRecorder`.
///
/// The recorder is reached through the [AudioRecording] seam and the permission
/// prompt through an injectable suspend function so the state machine
/// (idle → recording → finished / cancel, no double-start) is unit-testable
/// without touching the microphone or the permission dialog. Production wires
/// [MediaRecorderAudioRecording] and an Android permission launcher in the UI
/// stage.
///
/// Deviation from the Swift original: there is no Android analogue of iOS'
/// `AVAudioSession` activate/deactivate dance, so that step is dropped —
/// `android.media.MediaRecorder` owns its own capture lifecycle.
class VoiceRecorder(
    private val requestPermission: suspend () -> Boolean,
    private val makeRecorder: (File) -> AudioRecording,
    private val tempDirectory: File,
) {
    sealed interface State {
        data object Idle : State

        /// Actively capturing; [start] is the instant recording began, which the
        /// composer UI ticks against to show elapsed time.
        data class Recording(val start: Instant) : State
        data object Finished : State
    }

    sealed class RecorderError : Exception() {
        data object PermissionDenied : RecorderError()
        data object AlreadyRecording : RecorderError()
        data object RecordFailed : RecorderError()
    }

    /// A finished recording handed back by [stop].
    data class VoiceNote(val file: File, val duration: Duration)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recorder: AudioRecording? = null
    private var fileURL: File? = null
    private var startedAt: Instant? = null

    /// True from [start]'s entry until it settles — rejects a second call racing
    /// the permission `await` (state is still Idle in that gap, so the state
    /// check alone can't).
    private var isStarting = false

    /// Bumped by [cancel]. [start] snapshots it before the permission `await` and
    /// aborts quietly if it moved — a cancel that lands while the permission
    /// dialog is up must win over the in-flight start, or capture would begin
    /// with no recording UI. Everything after the single `await` is synchronous,
    /// so one check suffices.
    private var cancelGeneration = 0

    /// Requests microphone permission (once), then starts recording to a fresh
    /// temp `.m4a`. Throws [RecorderError.AlreadyRecording] if a recording is in
    /// progress, [RecorderError.PermissionDenied] if the user declines,
    /// [RecorderError.RecordFailed] if the recorder won't start.
    suspend fun start() {
        // Reject only an in-flight recording or start; a fresh start from Idle or
        // a prior Finished (a second voice note) is allowed.
        if (_state.value is State.Recording) throw RecorderError.AlreadyRecording
        if (isStarting) throw RecorderError.AlreadyRecording
        isStarting = true
        try {
            val generation = cancelGeneration
            if (!requestPermission()) throw RecorderError.PermissionDenied
            // A cancel() landed while the permission prompt was up — abandon the
            // start before any recorder work. Quiet no-op: the user asked for
            // silence, not an error.
            if (generation != cancelGeneration) return
            val file = File(tempDirectory, "voice-note-${UUID.randomUUID()}.m4a")
            try {
                val newRecorder = makeRecorder(file)
                if (!newRecorder.record()) throw RecorderError.RecordFailed
                recorder = newRecorder
                fileURL = file
                val started = Instant.now()
                startedAt = started
                _state.value = State.Recording(started)
            } catch (error: Throwable) {
                // A failed recorder construction or start must not leave an orphan
                // temp file behind.
                file.delete()
                throw error
            }
        } finally {
            isStarting = false
        }
    }

    /// Stops recording and hands back the finished file plus its elapsed
    /// duration. Returns `null` (a no-op) when not currently recording.
    fun stop(): VoiceNote? {
        if (_state.value !is State.Recording) return null
        val activeRecorder = recorder ?: return null
        val file = fileURL ?: return null
        val started = startedAt ?: return null
        activeRecorder.stop()
        val duration = java.time.Duration.between(started, Instant.now()).toKotlinDuration()
        recorder = null
        fileURL = null
        startedAt = null
        _state.value = State.Finished
        return VoiceNote(file, duration)
    }

    /// Aborts recording, discards the temp file, and returns to Idle. Also
    /// invalidates any start() suspended at its permission prompt.
    fun cancel() {
        cancelGeneration += 1
        recorder?.stop()
        fileURL?.delete()
        recorder = null
        fileURL = null
        startedAt = null
        _state.value = State.Idle
    }
}

/// The slice of `android.media.MediaRecorder` [VoiceRecorder] drives. Abstracted
/// so the state machine can be tested against a fake without capturing audio.
interface AudioRecording {
    /// Begins capture. Returns `false` if the recorder refused to start.
    fun record(): Boolean

    /// Stops capture and releases resources.
    fun stop()
}
