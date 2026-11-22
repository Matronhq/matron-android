package chat.matron.android.viewmodels

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.matron.android.models.Microphone
import chat.matron.android.platform.MicrophoneCatalog
import java.io.File
import java.util.concurrent.Executors

/// Production [AudioRecording] backed by `android.media.MediaRecorder`, writing
/// AAC audio into an MPEG-4 (`.m4a`) container — the Android analogue of the
/// Swift original's `AVAudioRecorder` settings (mono, 44.1 kHz, 64 kbps AAC).
///
/// The input is chosen with `MediaRecorder.setPreferredDevice` (Android 9+),
/// which also moves a recording already under way: a microphone picked
/// mid-note takes over at once, in the same file. A Bluetooth headset's
/// microphone only reaches a recorder over a call-audio link, so picking one
/// switches that link on for the span of the recording ([audioManager]).
///
/// Not unit-tested (it captures real audio); [VoiceRecorder]'s state machine is
/// exercised against a fake through the [AudioRecording] seam. Instantiated by
/// the composition root / UI stage as the `makeRecorder` factory.
class MediaRecorderAudioRecording(
    private val file: File,
    private val catalog: MicrophoneCatalog? = null,
    private val audioManager: AudioManager? = null,
) : AudioRecording {
    @Suppress("DEPRECATION")
    private val recorder = MediaRecorder()

    /// The device the next [record] (or the running recording) is pointed at.
    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    /// True while this recorder holds the Bluetooth call-audio link.
    @Volatile
    private var holdsCommunicationLink = false

    private var routingListener: Any? = null

    /// True once [record] has started the capture: from then on a pick is a
    /// live reroute rather than a setting for the start.
    @Volatile
    private var started = false

    /// Mid-note reroutes run here, one at a time. Moving a running capture
    /// waits on the audio system, which can take seconds for an input that
    /// is slow to come up; the main thread must not wait with it.
    private val rerouter = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "voice-note-reroute") }

    override fun record(): Boolean = runCatching {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(44_100)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setOutputFile(file.absolutePath)
        applyPreferredDevice()
        recorder.prepare()
        recorder.start()
        started = true
        true
    }.getOrElse {
        releaseCommunicationLink()
        false
    }

    // pause()/resume() exist since API 24 (minSdk is 26) and continue into the
    // same output file, which is what an interruption needs (apple #180).
    // Both throw IllegalStateException when called out of order — reported as
    // `false` so the state machine leaves the recorder as it found it.
    override fun pause(): Boolean = runCatching { recorder.pause() }.isSuccess

    override fun resume(): Boolean = runCatching { recorder.resume() }.isSuccess

    override fun stop(): Boolean {
        // MediaRecorder.stop() throws RuntimeException when the session
        // captured no valid data (e.g. stopped a moment after start()) —
        // that must not silently succeed, or a corrupt/empty m4a gets
        // uploaded. release() runs regardless so the recorder is never
        // leaked either way.
        setOnMicrophoneChanged(null)
        // A reroute still queued must not touch the released recorder.
        started = false
        rerouter.shutdown()
        val stopped = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        releaseCommunicationLink()
        return stopped
    }

    // Throws when the recorder isn't running; -1 keeps the diagnostics line
    // honest rather than reporting a silent 0.
    override fun peakAmplitude(): Int = runCatching { recorder.maxAmplitude }.getOrDefault(-1)

    override val microphone: Microphone?
        get() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            val routed = runCatching { recorder.routedDevice }.getOrNull() ?: preferredDevice ?: return null
            return MicrophoneCatalog.microphone(routed)
        }

    override fun useMicrophone(id: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = catalog?.device(id)
        preferredDevice = device
        // Before record() this only notes the pick; on a running recorder it
        // reroutes the capture there and then, in the same file. The routing
        // listener reports when the new input has taken over.
        if (!started) return
        runCatching { rerouter.execute { if (started && preferredDevice === device) runCatching { applyPreferredDevice() } } }
    }

    override fun setOnMicrophoneChanged(listener: (() -> Unit)?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        (routingListener as? AudioRouting.OnRoutingChangedListener)?.let { recorder.removeOnRoutingChangedListener(it) }
        routingListener = null
        if (listener == null) return
        val routing = AudioRouting.OnRoutingChangedListener { listener() }
        recorder.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        routingListener = routing
    }

    private fun applyPreferredDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val device = preferredDevice
        if (device != null && MicrophoneCatalog.needsCommunicationLink(device)) {
            holdCommunicationLink(device)
        } else {
            releaseCommunicationLink()
        }
        recorder.setPreferredDevice(device)
    }

    /// Switches on the call-audio link a Bluetooth headset's microphone needs.
    private fun holdCommunicationLink(input: AudioDeviceInfo) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The link is chosen by its output side: the same headset.
            val output = manager.availableCommunicationDevices.firstOrNull {
                it.type == input.type && it.address == input.address
            } ?: manager.availableCommunicationDevices.firstOrNull { it.type == input.type }
            if (output != null && manager.setCommunicationDevice(output)) holdsCommunicationLink = true
        } else {
            @Suppress("DEPRECATION")
            manager.startBluetoothSco()
            @Suppress("DEPRECATION")
            manager.isBluetoothScoOn = true
            holdsCommunicationLink = true
        }
    }

    private fun releaseCommunicationLink() {
        if (!holdsCommunicationLink) return
        holdsCommunicationLink = false
        val manager = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            }
        }
    }
}
