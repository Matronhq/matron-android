package chat.matron.android.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import chat.matron.android.viewmodels.AudioInterruption

/// The production interruption source for `VoiceRecorder` (port of apple
/// #180's `AVAudioSession.interruptionNotification` observer). Android has no
/// recorder interruption notification; what it has is audio focus. Holding
/// focus for the span of a recording means the system tells us when a phone
/// call, the assistant, or another app takes over: the dialer requests
/// transient focus on an incoming or outgoing call, so telephony interruptions
/// arrive here too without a phone-state permission.
///
/// Focus changes map onto the platform-neutral seam via
/// [interruptionsForFocusChange], kept pure so the table is unit-tested:
/// - `AUDIOFOCUS_LOSS_TRANSIENT` → `Began` (the recorder pauses)
/// - `AUDIOFOCUS_GAIN` → `Ended(shouldResume = true)` (resume into the same file)
/// - `AUDIOFOCUS_LOSS` → `Began`, `Ended(shouldResume = false)`: the focus
///   request is over and Android will not hand focus back, so the recorder
///   stays paused and Stop delivers what was captured.
/// - `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK` is not an interruption: nothing
///   takes the mic for a notification chime, so capture continues.
class AudioFocusInterruptions(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /// Subscribes [handler] for one recording; the returned lambda abandons the
    /// focus request. Delivered on the main thread, like the recorder's other
    /// callers. A refused focus request (e.g. mid-call) simply yields no
    /// events: the capture proceeds and stops as it always did.
    fun observe(handler: (AudioInterruption) -> Unit): () -> Unit {
        val manager = audioManager ?: return {}
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            interruptionsForFocusChange(change).forEach(handler)
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .build()
        val granted = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return {}
        return { runCatching { manager.abandonAudioFocusRequest(request) } }
    }

    companion object {
        /// The focus-change → interruption table; see the class doc.
        fun interruptionsForFocusChange(focusChange: Int): List<AudioInterruption> = when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listOf(AudioInterruption.Began)
            AudioManager.AUDIOFOCUS_LOSS ->
                listOf(AudioInterruption.Began, AudioInterruption.Ended(shouldResume = false))
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            -> listOf(AudioInterruption.Ended(shouldResume = true))
            else -> emptyList()
        }
    }
}
