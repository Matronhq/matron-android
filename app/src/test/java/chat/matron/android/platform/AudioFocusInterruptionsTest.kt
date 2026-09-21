package chat.matron.android.platform

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.viewmodels.AudioInterruption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/// The audio-focus → `AudioInterruption` mapping behind the recorder's
/// production interruption seam (apple #180): a transient loss pauses, a gain
/// resumes, a permanent loss ends without the resume hint, and ducking is not
/// an interruption at all.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AudioFocusInterruptionsTest {
    @Test
    fun transientLoss_isBegan() {
        assertEquals(
            listOf(AudioInterruption.Began),
            AudioFocusInterruptions.interruptionsForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT),
        )
    }

    @Test
    fun gain_isEndedWithResumeHint() {
        assertEquals(
            listOf(AudioInterruption.Ended(shouldResume = true)),
            AudioFocusInterruptions.interruptionsForFocusChange(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun permanentLoss_isBeganThenEndedWithoutResumeHint() {
        assertEquals(
            listOf(AudioInterruption.Began, AudioInterruption.Ended(shouldResume = false)),
            AudioFocusInterruptions.interruptionsForFocusChange(AudioManager.AUDIOFOCUS_LOSS),
        )
    }

    @Test
    fun ducking_isNotAnInterruption() {
        assertTrue(
            AudioFocusInterruptions.interruptionsForFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK).isEmpty(),
        )
    }

    /// End to end against the shadow AudioManager: subscribing requests focus
    /// and forwards the listener's changes; unsubscribing abandons the request.
    @Test
    fun observe_requestsFocusForwardsChangesAndAbandonsOnUnsubscribe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(AudioManager::class.java)
        val shadow = shadowOf(manager)
        val events = mutableListOf<AudioInterruption>()

        val unsubscribe = AudioFocusInterruptions(context).observe { events.add(it) }
        val request = shadow.lastAudioFocusRequest
        assertNotNull("focus requested for the span of the recording", request)
        assertEquals("a short recording claims transient focus", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint)
        val listener = request.listener
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(AudioInterruption.Began, AudioInterruption.Ended(shouldResume = true)), events)

        assertNull(shadow.lastAbandonedAudioFocusRequest)
        unsubscribe()
        assertEquals("focus abandoned on unsubscribe", request.audioFocusRequest, shadow.lastAbandonedAudioFocusRequest)
    }
}
