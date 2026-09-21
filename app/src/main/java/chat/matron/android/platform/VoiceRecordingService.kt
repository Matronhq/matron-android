package chat.matron.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import chat.matron.android.MainActivity

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
        // Stop is delivered as a start command, not `stopService()`: commands
        // run in order, so a stop that lands right after the start (a
        // too-short tap, an immediate cancel) still lets the start's
        // `startForeground` satisfy the `startForegroundService` contract
        // first. `stopService()` racing that contract killed the process with
        // ForegroundServiceDidNotStartInTimeException.
        //
        // Guarded by this command's startId: a newer start (cancel, then
        // record again straight away) may already be registered when the stop
        // is processed, and an unguarded stopSelf() would bring the service —
        // and the newer recording's microphone session — down with it.
        if (intent?.action == ACTION_STOP) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
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
        const val ACTION_STOP = "chat.matron.android.action.STOP_VOICE_RECORDING"

        /// Claims the microphone foreground session. Call while the app is in
        /// the foreground, once capture has actually begun.
        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoiceRecordingService::class.java))
        }

        /// Releases the session; the notification goes with it. Queued behind
        /// any pending start (see [onStartCommand]). A background app with no
        /// running service may not queue commands at all — then there is
        /// nothing foregrounded to race, and a plain `stopService` suffices.
        fun stop(context: Context) {
            val stop = Intent(context, VoiceRecordingService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(stop) }.onFailure { context.stopService(stop) }
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

        /// The intent behind a tap on the notification: the same MAIN/LAUNCHER
        /// intent the home screen icon sends, so the existing task is brought
        /// forward. A bare `Intent(context, MainActivity)` would stack a second
        /// (standard-launch-mode) MainActivity with a fresh composition — and a
        /// fresh recorder — over the one holding the note.
        internal fun openAppIntent(context: Context): Intent {
            val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
            return launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        internal fun buildNotification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                openAppIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(chat.matron.android.R.drawable.ic_launcher_monochrome)
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
