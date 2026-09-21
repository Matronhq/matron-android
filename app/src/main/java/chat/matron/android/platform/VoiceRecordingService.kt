package chat.matron.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import chat.matron.android.MainActivity
import chat.matron.android.R

/// Keeps a voice-note capture alive while the app is in the background — the
/// Android analogue of apple #180's `audio` `UIBackgroundModes` entry. Without
/// it, switching to another app mid-note has Android 11+ cut the microphone
/// (and eventually freeze the process), so the note silently stopped at the
/// switch while the UI still said "recording".
///
/// A `microphone` foreground service is what grants continued mic access away
/// from the screen. It is started when `record()` succeeds (the app is in the
/// foreground then — Android 14+ refuses to start a microphone service from the
/// background) and stopped on stop/cancel, so the app suspends as before the
/// rest of the time. The mandatory notification lives in a low-importance
/// channel: no sound, no heads-up, just the status-bar entry the platform
/// requires while a service holds the mic.
///
/// The service owns no recorder: `VoiceRecorder` does the capture; this is
/// purely the process-lifetime and mic-access claim.
class VoiceRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // Not sticky: if the system kills the process the capture is gone with
        // it, and a restarted service would have nothing to keep alive.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The system drops the notification with the service anyway; being
        // explicit means the claim's release is observable and never lingers.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "voice-recording"
        const val NOTIFICATION_ID = 0x7601

        /// Claims the microphone foreground session. Call while the app is in
        /// the foreground, once capture has actually begun.
        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceRecordingService::class.java))
        }

        /// Releases the session; the notification goes with it.
        fun stop(context: Context) {
            context.stopService(Intent(context, VoiceRecordingService::class.java))
        }

        internal fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a voice note is being recorded."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        internal fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Recording voice note")
                .setContentText("Return to Matron to send or cancel.")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
