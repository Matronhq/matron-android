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

    @Test
    fun startAndStop_targetTheServiceComponent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowApp = shadowOf(context as android.app.Application)

        VoiceRecordingService.start(context)
        val started: Intent? = shadowApp.nextStartedService
        assertEquals(VoiceRecordingService::class.java.name, started?.component?.className)

        VoiceRecordingService.stop(context)
        val stopped: Intent? = shadowApp.nextStoppedService
        assertEquals(VoiceRecordingService::class.java.name, stopped?.component?.className)
    }
}
