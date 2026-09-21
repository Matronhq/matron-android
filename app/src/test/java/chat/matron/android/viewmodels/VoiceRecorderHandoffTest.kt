package chat.matron.android.viewmodels

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/// A live recording must survive the app-lock shield tearing the composer
/// down (Bugbot, android #70), but every other teardown still cancels it.
class VoiceRecorderHandoffTest {
    private class FakeAudioRecorder : AudioRecording {
        var stopCalls = 0
        override fun record() = true
        override fun pause() = true
        override fun resume() = true
        override fun stop(): Boolean { stopCalls++; return true }
        override fun peakAmplitude() = 0
    }

    private fun host(fake: FakeAudioRecorder = FakeAudioRecorder()): VoiceRecorderHost {
        val bindings = VoiceRecorderBindings()
        val recorder = VoiceRecorder(
            requestPermission = { bindings.requestPermission() },
            makeRecorder = { fake },
            tempDirectory = Files.createTempDirectory("voice").toFile(),
            setKeepScreenAwake = { bindings.setKeepScreenAwake(it) },
        )
        return VoiceRecorderHost(bindings, recorder)
    }

    private fun recordingHost(fake: FakeAudioRecorder = FakeAudioRecorder()): VoiceRecorderHost = runBlocking {
        host(fake).also { it.bindings.requestPermission = { true }; it.recorder.start() }
    }

    @After
    fun reset() = VoiceRecorderHandoff.resetForTesting()

    @Test
    fun lockedWhileRecording_parksAndTheNextComposerReclaimsItStillRecording() {
        val fake = FakeAudioRecorder()
        val host = recordingHost(fake)
        assertTrue(VoiceRecorderHandoff.onComposerDisposed("room", host, appLocked = true))
        assertEquals(0, fake.stopCalls)
        assertEquals("room", VoiceRecorderHandoff.parkedRoomID())

        val reclaimed = VoiceRecorderHandoff.parkedFor("room")
        assertSame(host, reclaimed)
        assertTrue(reclaimed!!.recorder.state.value is VoiceRecorder.State.Recording)
        VoiceRecorderHandoff.confirmReclaim("room", reclaimed)
        assertNull("handed over once", VoiceRecorderHandoff.parkedFor("room"))
        assertNull(VoiceRecorderHandoff.parkedRoomID())
    }

    /// A `remember` calculation may run in a composition Compose discards and
    /// retries (Bugbot, android #70): reading the parked note must not take
    /// it, or the retry finds nothing and the live capture is orphaned with
    /// its microphone session. Only the committed composer's confirmation
    /// releases the entry.
    @Test
    fun readingTheParkedNoteTwice_returnsTheSameLiveHostUntilConfirmed() {
        val fake = FakeAudioRecorder()
        val host = recordingHost(fake)
        VoiceRecorderHandoff.onComposerDisposed("room", host, appLocked = true)

        assertSame("abandoned composition", host, VoiceRecorderHandoff.parkedFor("room"))
        assertSame("retried composition", host, VoiceRecorderHandoff.parkedFor("room"))
        assertTrue(host.recorder.state.value is VoiceRecorder.State.Recording)
        assertEquals(0, fake.stopCalls)

        VoiceRecorderHandoff.confirmReclaim("room", host)
        assertNull(VoiceRecorderHandoff.parkedFor("room"))
    }

    /// A composer that ended up with a fresh recorder (the read happened
    /// before the park) must not evict the note parked for its room.
    @Test
    fun confirmingWithADifferentHost_leavesTheParkedNoteAlone() {
        val parkedHost = recordingHost()
        VoiceRecorderHandoff.onComposerDisposed("room", parkedHost, appLocked = true)
        VoiceRecorderHandoff.confirmReclaim("room", host())
        assertSame(parkedHost, VoiceRecorderHandoff.parkedFor("room"))
    }

    @Test
    fun navigatingAwayWhileRecording_cancelsAsBefore() {
        val fake = FakeAudioRecorder()
        val host = recordingHost(fake)
        assertFalse(VoiceRecorderHandoff.onComposerDisposed("room", host, appLocked = false))
        assertEquals(1, fake.stopCalls)
        assertEquals(VoiceRecorder.State.Idle, host.recorder.state.value)
        assertNull(VoiceRecorderHandoff.parkedFor("room"))
    }

    @Test
    fun lockedWhileIdle_parksNothing() {
        val host = host()
        assertFalse(VoiceRecorderHandoff.onComposerDisposed("room", host, appLocked = true))
        assertNull(VoiceRecorderHandoff.parkedFor("room"))
    }

    @Test
    fun reclaimForAnotherRoom_leavesTheParkedNoteAlone() {
        val host = recordingHost()
        VoiceRecorderHandoff.onComposerDisposed("a", host, appLocked = true)
        assertNull(VoiceRecorderHandoff.parkedFor("b"))
        assertSame(host, VoiceRecorderHandoff.parkedFor("a"))
    }

    @Test
    fun parkingASecondNoteForTheSameRoom_cancelsTheStaleOne() {
        val staleFake = FakeAudioRecorder()
        val stale = recordingHost(staleFake)
        VoiceRecorderHandoff.onComposerDisposed("room", stale, appLocked = true)
        val fresh = recordingHost()
        VoiceRecorderHandoff.onComposerDisposed("room", fresh, appLocked = true)
        assertEquals("the stale mic claim is released", 1, staleFake.stopCalls)
        assertSame(fresh, VoiceRecorderHandoff.parkedFor("room"))
    }

    /// The reclaimed recorder must run the NEXT composer's launcher, not the
    /// disposed one's: bindings are rebound, not baked in.
    @Test
    fun reboundPermissionCloseIsWhatTheRecorderCalls() = runBlocking {
        val host = host()
        host.bindings.requestPermission = { false }
        assertEquals(VoiceRecorder.RecorderError.PermissionDenied, runCatching { host.recorder.start() }.exceptionOrNull())
        host.bindings.requestPermission = { true }
        host.recorder.start()
        assertTrue(host.recorder.state.value is VoiceRecorder.State.Recording)
    }
}
