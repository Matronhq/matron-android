package chat.matron.android.viewmodels

import chat.matron.android.models.MatronDebug
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// An interruption as the recorder sees it: a phone call, the assistant, or
/// another app taking the microphone. [Began] pauses capture; [Ended] says
/// whether the system wants us back on the mic ([Ended.shouldResume]).
/// Platform-neutral so the resume logic is testable without an
/// `AudioManager` (port of apple #180's `AudioInterruption`). Production maps
/// audio-focus changes onto it (`AudioFocusInterruptions`): a transient loss
/// is `Began`, regaining focus is `Ended(shouldResume = true)`, a permanent
/// loss is `Began` followed by `Ended(shouldResume = false)`.
sealed interface AudioInterruption {
    data object Began : AudioInterruption
    data class Ended(val shouldResume: Boolean) : AudioInterruption
}

/// Records a short voice note to a temporary AAC `.m4a` file for sending as an
/// `audio/*` attachment. Ported from matron-apple's `VoiceRecorder`.
///
/// The recorder is reached through the [AudioRecording] seam and the permission
/// prompt through an injectable suspend function so the state machine
/// (idle → recording ⇄ paused → finished / cancel, no double-start) is
/// unit-testable without touching the microphone or the permission dialog.
/// Production wires [MediaRecorderAudioRecording] and an Android permission
/// launcher in the UI stage.
///
/// Deviation from the Swift original: there is no Android analogue of iOS'
/// `AVAudioSession` activate/deactivate dance, so that step is dropped —
/// `android.media.MediaRecorder` owns its own capture lifecycle. Its
/// background-mode equivalent is the [holdRecordingSession] seam, which the UI
/// stage wires to a microphone foreground service. And where iOS pauses the
/// `AVAudioRecorder` itself on an interruption, Android tells us about lost
/// audio focus but leaves the `MediaRecorder` running, so [handleInterruption]
/// pauses it explicitly.
class VoiceRecorder(
    private val requestPermission: suspend () -> Boolean,
    private val makeRecorder: (File) -> AudioRecording,
    private val tempDirectory: File,
    /// `true` while capture is live, `false` once it ends — keeps the screen
    /// from auto-locking mid-recording (locking suspends the app and kills the
    /// capture). Injectable so tests can observe the claim/release pair; the
    /// UI stage wires the window's KEEP_SCREEN_ON flag (port of apple #159).
    private val setKeepScreenAwake: (Boolean) -> Unit = {},
    /// `true` once capture is live, `false` when it ends (stop or cancel) —
    /// the Android analogue of apple #180's `audio` background mode. The UI
    /// stage wires a `microphone` foreground service so switching apps
    /// mid-note no longer kills the capture; held only for the span of a
    /// recording so the app suspends as before the rest of the time. Called
    /// after `record()` succeeds, i.e. while the app is in the foreground —
    /// Android 14+ refuses to start a microphone foreground service from
    /// the background.
    private val holdRecordingSession: (Boolean) -> Unit = {},
    /// Subscribes a handler to interruption events for the life of one
    /// recording and returns the unsubscribe. Injectable so tests can deliver
    /// events; the real source maps audio-focus changes.
    private val observeInterruptions: ((AudioInterruption) -> Unit) -> (() -> Unit) = { {} },
    /// Describes the input route/device state for the diagnostics line
    /// (port of apple #181). The real one reads `AudioManager`; tests and the
    /// default leave it empty.
    private val describeInputRoute: () -> String = { "" },
    private val now: () -> Instant = Instant::now,
) {
    sealed interface State {
        data object Idle : State

        /// A recording in progress; [start] is the instant recording began,
        /// which the composer UI ticks against to show elapsed time.
        /// [isPaused] is true while an interruption holds the capture — the
        /// composer shows "Paused" rather than "Recording" then, so the UI
        /// never claims to capture what it does not.
        data class Recording(val start: Instant, val isPaused: Boolean = false) : State
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

    private var stopObservingInterruptions: (() -> Unit)? = null

    /// True between an interruption's `Began` and its `Ended` — the only
    /// window in which an `Ended` may resume capture. An `Ended` with no
    /// `Began` is a stale event, not a reason to poke the recorder.
    private var isInterrupted = false

    /// Interruption bookkeeping for the reported duration: capture time, not
    /// wall time — a call in the middle of a note is silence the recorder
    /// never captured. `pausedSince` is set when the recorder actually pauses
    /// and cleared only by a successful resume; an unresumed pause runs until
    /// [stop].
    private var pausedSince: Instant? = null
    private var pausedTotal: java.time.Duration = java.time.Duration.ZERO

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
            // Everything acquired below is released again, in reverse, if a
            // later step throws: a live MediaRecorder with the state back at
            // Idle, or a held screen/mic-session claim with no recording to
            // release it, would outlive the failure.
            var liveRecorder: AudioRecording? = null
            var screenClaimed = false
            var sessionClaimed = false
            var unsubscribe: (() -> Unit)? = null
            try {
                val newRecorder = makeRecorder(file)
                if (!newRecorder.record()) throw RecorderError.RecordFailed
                liveRecorder = newRecorder
                recorder = newRecorder
                fileURL = file
                val started = now()
                startedAt = started
                _state.value = State.Recording(started)
                screenClaimed = true
                setKeepScreenAwake(true)
                sessionClaimed = true
                holdRecordingSession(true)
                isInterrupted = false
                pausedSince = null
                pausedTotal = java.time.Duration.ZERO
                unsubscribe = observeInterruptions { event -> handleInterruption(event) }
                stopObservingInterruptions = unsubscribe
                log("start: ${routeDescription()}")
            } catch (error: Throwable) {
                runCatching { unsubscribe?.invoke() }
                stopObservingInterruptions = null
                // A claim whose call threw may or may not have taken effect;
                // releasing it is harmless either way, leaving it held is not.
                if (sessionClaimed) runCatching { holdRecordingSession(false) }
                if (screenClaimed) runCatching { setKeepScreenAwake(false) }
                runCatching { liveRecorder?.stop() }
                recorder = null
                fileURL = null
                startedAt = null
                isInterrupted = false
                pausedSince = null
                pausedTotal = java.time.Duration.ZERO
                _state.value = State.Idle
                // A failed recorder construction or start must not leave an orphan
                // temp file behind.
                file.delete()
                log("start failed: $error")
                // The composer handles RecorderError; anything else (a refused
                // foreground-service start, say) is the same outcome to the
                // user — no recording — and must not escape as a crash.
                throw error as? RecorderError ?: RecorderError.RecordFailed
            }
        } finally {
            isStarting = false
        }
    }

    /// Stops recording and hands back the finished file plus its captured
    /// duration (wall time minus any interruption pauses). Returns `null` (a
    /// no-op) when not currently recording, or when the recorder rejects the
    /// stop (too-short a tap yields no captured data — [AudioRecording.stop]
    /// returns `false` — in which case the unusable temp file is deleted
    /// rather than handed on to be sent). A recording left paused by an
    /// interruption still delivers what was captured up to the pause.
    fun stop(): VoiceNote? {
        if (_state.value !is State.Recording) return null
        val activeRecorder = recorder ?: return null
        val file = fileURL ?: return null
        val started = startedAt ?: return null
        val peak = activeRecorder.peakAmplitude()
        val succeeded = activeRecorder.stop()
        val stoppedAt = now()
        val stillPaused = pausedSince?.let { java.time.Duration.between(it, stoppedAt) } ?: java.time.Duration.ZERO
        val paused = pausedTotal.plus(stillPaused)
        val duration = java.time.Duration.between(started, stoppedAt).minus(paused).toKotlinDuration()
        val bytes = if (file.exists()) file.length() else -1L
        log(
            "stop: ok=$succeeded duration=${duration.inWholeMilliseconds}ms paused=${paused.toMillis()}ms " +
                "bytes=$bytes peak=$peak ${routeDescription()}",
        )
        recorder = null
        fileURL = null
        startedAt = null
        _state.value = State.Finished
        setKeepScreenAwake(false)
        holdRecordingSession(false)
        unsubscribeInterruptions()
        if (!succeeded) {
            file.delete()
            return null
        }
        return VoiceNote(file, duration)
    }

    /// Aborts recording, discards the temp file, and returns to Idle. Also
    /// invalidates any start() suspended at its permission prompt.
    fun cancel() {
        cancelGeneration += 1
        val wasRecording = recorder != null
        recorder?.stop()
        fileURL?.delete()
        recorder = null
        fileURL = null
        startedAt = null
        _state.value = State.Idle
        setKeepScreenAwake(false)
        holdRecordingSession(false)
        unsubscribeInterruptions()
        if (wasRecording) log("cancel")
    }

    /// One diagnostics line with the peak input level since the last sample
    /// (port of apple #181's gain breadcrumbs — Android exposes no input gain,
    /// but `MediaRecorder.getMaxAmplitude()` says whether the mic heard
    /// anything at all). The composer calls this every few seconds while a
    /// recording is live; a no-op when idle or paused.
    fun sampleLevel() {
        val current = _state.value as? State.Recording ?: return
        if (current.isPaused) return
        val activeRecorder = recorder ?: return
        log("level: peak=${activeRecorder.peakAmplitude()}")
    }

    /// Android leaves the `MediaRecorder` running on lost audio focus, so
    /// `Began` pauses it here; on `Ended` with the resume hint, `resume()`
    /// picks capture up in the same file. Without the hint the system doesn't
    /// want us back on the mic (another app took it): the recorder stays
    /// paused, and a later [stop] still delivers what was captured. A failed
    /// resume is left alone for the same reason.
    private fun handleInterruption(event: AudioInterruption) {
        val current = _state.value
        log("interruption: $event state=$current wasInterrupted=$isInterrupted")
        if (current !is State.Recording) return
        val activeRecorder = recorder ?: return
        when (event) {
            AudioInterruption.Began -> {
                isInterrupted = true
                if (pausedSince != null) return
                log("interruption: peak before pause=${activeRecorder.peakAmplitude()}")
                if (!activeRecorder.pause()) {
                    log("interruption: pause failed, capture continues")
                    return
                }
                pausedSince = now()
                _state.value = current.copy(isPaused = true)
            }
            is AudioInterruption.Ended -> {
                if (!isInterrupted) return
                isInterrupted = false
                if (!event.shouldResume) return
                val since = pausedSince ?: return
                // A failed resume leaves the recorder paused: the pause keeps
                // running until stop(), so it stays open here too.
                if (!activeRecorder.resume()) {
                    log("resume failed, recorder stays paused")
                    return
                }
                pausedTotal = pausedTotal.plus(java.time.Duration.between(since, now()))
                pausedSince = null
                _state.value = current.copy(isPaused = false)
                log("resume: ${routeDescription()}")
            }
        }
    }

    private fun unsubscribeInterruptions() {
        stopObservingInterruptions?.invoke()
        stopObservingInterruptions = null
        isInterrupted = false
        pausedSince = null
        pausedTotal = java.time.Duration.ZERO
    }

    /// The diagnostics seam must never be the reason a recording fails.
    private fun routeDescription(): String = runCatching { describeInputRoute() }.getOrElse { "route=error:$it" }

    /// Breadcrumbs for the "note came back silent" class of report (apple
    /// #181: three notes in a row carried no voice at all while the mic still
    /// heard taps on the phone body — the input route at the time was the
    /// unanswerable question). Un-gated: a recording is a rare enough event,
    /// and these are the lines a field incident is diagnosed from.
    private fun log(message: String) = MatronDebug.breadcrumb("$LOG_PREFIX $message")

    companion object {
        const val LOG_PREFIX = "voice-recorder"
    }
}

/// The slice of `android.media.MediaRecorder` [VoiceRecorder] drives. Abstracted
/// so the state machine can be tested against a fake without capturing audio.
interface AudioRecording {
    /// Begins capture. Returns `false` if the recorder refused to start.
    fun record(): Boolean

    /// Suspends capture without closing the file; [resume] continues into the
    /// same file. Returns `false` if the recorder refused (not recording).
    fun pause(): Boolean

    /// Continues a paused capture. Returns `false` if the recorder refused
    /// (not paused, or the microphone is gone).
    fun resume(): Boolean

    /// Stops capture and releases resources. Returns `false` if the recorder
    /// rejected the stop (e.g. `MediaRecorder.stop()` throws when a recording
    /// captured no data — too-short a tap), meaning the output file is not a
    /// valid recording and must not be sent.
    fun stop(): Boolean

    /// Peak input sample since the last call (0..32767), or -1 when unknown.
    /// Diagnostics only.
    fun peakAmplitude(): Int
}
