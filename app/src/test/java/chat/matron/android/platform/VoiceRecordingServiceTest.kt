package chat.matron.android.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/// The microphone foreground session behind a live voice note (apple #180's
/// `audio` background mode): a `microphone`-typed foreground service with a
/// quiet, low-importance notification, torn down with the service.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class VoiceRecordingServiceTest {
    @Test
    fun start_goesForegroundWithTheMicrophoneTypeAndAnOngoingNotification() {
        val controller = Robolectric.buildService(VoiceRecordingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        val shadow = shadowOf(service)

        assertEquals(VoiceRecordingService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        val notification = shadow.lastForegroundNotification
        assertNotNull(notification)
        assertEquals(VoiceRecordingService.CHANNEL_ID, notification.channelId)
        assertTrue("ongoing: the user cannot swipe away a live mic claim", notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        assertFalse(shadow.isForegroundStopped)

        controller.destroy()
        assertTrue(shadow.isForegroundStopped)
    }

    @Test
    fun channel_isLowImportanceSoTheClaimIsQuiet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        VoiceRecordingService.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(VoiceRecordingService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    /// A stop that lands before the start's `startForeground` ran must not
    /// tear the service down with the foreground contract unmet (Bugbot,
    /// android #70): stop is queued as a start command behind the start.
    @Test
    fun stop_isQueuedAsAStartCommandBehindTheStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(context as android.app.Application)

        VoiceRecordingService.start(context)
        val started: Intent? = shadowApp.nextStartedService
        assertEquals(VoiceRecordingService::class.java.name, started?.component?.className)
        assertNull(started?.action)

        VoiceRecordingService.stop(context)
        val stop: Intent? = shadowApp.nextStartedService
        assertEquals(VoiceRecordingService::class.java.name, stop?.component?.className)
        assertEquals(VoiceRecordingService.ACTION_STOP, stop?.action)
        assertNull("no stopService racing the pending start", shadowApp.nextStoppedService)
    }

    @Test
    fun stopCommand_afterStart_goesForegroundThenStopsItself() {
        val controller = Robolectric.buildService(VoiceRecordingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        val shadow = shadowOf(service)
        assertEquals(VoiceRecordingService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)

        controller.withIntent(Intent(service, VoiceRecordingService::class.java).setAction(VoiceRecordingService.ACTION_STOP))
            .startCommand(0, 2)
        assertTrue(shadow.isStoppedBySelf)
        controller.destroy()
        assertTrue(shadow.isForegroundStopped)
    }

    /// Cancel, then record again straight away: the queued stop must be
    /// guarded by its own startId (`stopSelfResult`) so a newer start the
    /// system has already registered keeps the service — and the second
    /// recording's mic session — alive (CodeRabbit, android #70).
    @Test
    fun stopCommand_isGuardedByItsStartId_soALaterStartSurvives() {
        val controller = Robolectric.buildService(VoiceRecordingService::class.java)
        val service = controller.create().startCommand(0, 1).get()
        val shadow = shadowOf(service)
        val stop = Intent(service, VoiceRecordingService::class.java).setAction(VoiceRecordingService.ACTION_STOP)

        controller.withIntent(stop).startCommand(0, 2)
        assertEquals("stop is scoped to its own start request", 2, shadow.stopSelfResultId)

        controller.withIntent(Intent(service, VoiceRecordingService::class.java)).startCommand(0, 3)
        assertEquals(VoiceRecordingService.NOTIFICATION_ID, shadow.lastForegroundNotificationId)
        assertFalse(shadow.isForegroundStopped)
    }

    /// Tapping the notification must bring the existing task forward, not
    /// stack a second MainActivity with a fresh recorder over the one holding
    /// the note (Bugbot, android #70): the launcher's own MAIN/LAUNCHER intent.
    @Test
    fun notificationTap_bringsTheExistingTaskForward() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = VoiceRecordingService.buildNotification(context)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(chat.matron.android.MainActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
    }
}
