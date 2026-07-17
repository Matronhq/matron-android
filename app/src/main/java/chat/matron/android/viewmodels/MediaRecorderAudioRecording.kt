package chat.matron.android.viewmodels

import android.media.MediaRecorder
import java.io.File

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

    override fun stop(): Boolean {
        // MediaRecorder.stop() throws RuntimeException when the session
        // captured no valid data (e.g. stopped a moment after start()) —
        // that must not silently succeed, or a corrupt/empty m4a gets
        // uploaded. release() runs regardless so the recorder is never
        // leaked either way.
        val stopped = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        return stopped
    }
}
