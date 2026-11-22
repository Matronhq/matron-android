package chat.matron.android.viewmodels

import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/// Production [AudioRecording] backed by `android.media.MediaRecorder`, writing
/// AAC audio into an MPEG-4 (`.m4a`) container — the Android analogue of the
/// Swift original's `AVAudioRecorder` settings (mono, 44.1 kHz, 64 kbps AAC).
///
/// Not unit-tested (it captures real audio); [VoiceRecorder]'s state machine is
/// exercised against a fake through the [AudioRecording] seam. Instantiated by
/// the composition root / UI stage as the `makeRecorder` factory.
class MediaRecorderAudioRecording(private val file: File) : AudioRecording {
    @Suppress("DEPRECATION")
    private val recorder = MediaRecorder()

    override fun record(): Boolean = runCatching {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(44_100)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setOutputFile(file.absolutePath)
        recorder.prepare()
        recorder.start()
        true
    }.getOrDefault(false)

    // pause()/resume() exist since API 24 (minSdk is 26) and continue into the
    // same output file, which is what an interruption needs (apple #180).
    // Both throw IllegalStateException when called out of order — reported as
    // `false` so the state machine leaves the recorder as it found it.
    override fun pause(): Boolean = runCatching { recorder.pause() }.isSuccess

    override fun resume(): Boolean = runCatching { recorder.resume() }.isSuccess

    @Volatile
    private var timedOut = false
    override val finishTimedOut: Boolean get() = timedOut

    override fun stop(): Boolean {
        // MediaRecorder.stop() throws RuntimeException when the session
        // captured no valid data (e.g. stopped a moment after start()) —
        // that must not silently succeed, or a corrupt/empty m4a gets
        // uploaded. release() runs regardless so the recorder is never
        // leaked either way.
        //
        // stop() is where the MPEG-4 writer finishes the file (it writes the
        // index the file can't be played without), and it blocks until then.
        // It runs on a thread of its own so a writer that never finishes
        // can't hang the caller: after FINISH_TIMEOUT the note counts as
        // failed — the file may be incomplete — and release() still runs
        // once stop() returns.
        val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "voice-note-finish") }
        val finish = worker.submit<Boolean> {
            val stopped = runCatching { recorder.stop() }.isSuccess
            runCatching { recorder.release() }
            stopped
        }
        worker.shutdown()
        return try {
            finish.get(FINISH_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            timedOut = true
            false
        } catch (_: Exception) {
            false
        }
    }

    // Throws when the recorder isn't running; -1 keeps the diagnostics line
    // honest rather than reporting a silent 0.
    override fun peakAmplitude(): Int = runCatching { recorder.maxAmplitude }.getOrDefault(-1)

    companion object {
        /// How long [stop] waits for the recorder to finish the file.
        val FINISH_TIMEOUT: Duration = 3_000.milliseconds

        /// The audio [file] holds, read from its container; `null` when it
        /// can't be read. `VoiceRecorder` refuses a note under a second.
        fun measuredDuration(file: File): Duration? = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.milliseconds
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }
}
