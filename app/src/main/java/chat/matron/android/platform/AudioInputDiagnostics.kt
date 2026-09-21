package chat.matron.android.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/// Describes the microphone route for `VoiceRecorder`'s per-recording
/// breadcrumbs (port of apple #181's `logSessionState`): everything that
/// decides what the file will contain. The input Android actually chose (a
/// connected headset or car kit can override the built-in mic), whether the
/// platform is feeding the recorder silence (Android 10+ mutes a capture when
/// a privileged app takes the mic — exactly the "real file, no voice" report),
/// the available inputs, mic mute, audio mode, whether other audio is
/// playing, and the record permission. Android exposes no input gain; the
/// level is covered by the recorder's own peak-amplitude samples.
///
/// Every read is wrapped: this line must never be the reason a recording
/// fails.
class AudioInputDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    fun describe(): String {
        val manager = audioManager ?: return "audioManager=null"
        val active = runCatching {
            manager.activeRecordingConfigurations.joinToString(",") { config ->
                val device = config.audioDevice
                val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) config.isClientSilenced else null
                buildString {
                    append(describeDevice(device))
                    append(" src=").append(config.clientAudioSource)
                    append(" rate=").append(config.format.sampleRate)
                    append(" ch=").append(config.format.channelCount)
                    if (silenced != null) append(" silenced=").append(silenced)
                }
            }
        }.getOrElse { "error:$it" }
        val inputs = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString(",") { describeDevice(it) }
        }.getOrElse { "error:$it" }
        val communication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.communicationDevice?.let { describeDevice(it) } ?: "none" }.getOrElse { "error" }
        } else {
            "n/a"
        }
        val permission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return "active=[$active] inputs=[$inputs] commDevice=$communication " +
            "micMute=${runCatching { manager.isMicrophoneMute }.getOrNull()} " +
            "mode=${runCatching { manager.mode }.getOrNull()} " +
            "otherAudio=${runCatching { manager.isMusicActive }.getOrNull()} " +
            "permission=$permission"
    }

    private fun describeDevice(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        return "${device.type}:${device.productName}"
    }
}
