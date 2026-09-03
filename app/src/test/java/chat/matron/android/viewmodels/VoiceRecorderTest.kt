package chat.matron.android.viewmodels

import java.io.File
import java.nio.file.Files
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `VoiceRecorderTests`: the idle → recording →
/// finished / cancel state machine driven against a fake recorder and a
/// stubbable permission suspend fn (no microphone, no permission dialog).
class VoiceRecorderTest {

    private class FakeAudioRecorder(
        var recordReturn: Boolean = true,
        var stopReturn: Boolean = true,
    ) : AudioRecording {
        var recordCalls = 0
            private set
        var stopCalls = 0
            private set

        override fun record(): Boolean {
            recordCalls++
            return recordReturn
        }

        override fun stop(): Boolean {
            stopCalls++
            return stopReturn
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("voice").toFile()

    private fun makeRecorder(
        permission: Boolean = true,
        fake: FakeAudioRecorder = FakeAudioRecorder(),
        keepAwakeCalls: MutableList<Boolean> = mutableListOf(),
    ) = VoiceRecorder(
        requestPermission = { permission },
        makeRecorder = { fake },
        tempDirectory = tempDir(),
        setKeepScreenAwake = { keepAwakeCalls.add(it) },
    )

    @Test
    fun start_transitionsIdleToRecording() = runBlocking {
        val rec = makeRecorder()
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
        rec.start()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
    }

    @Test
    fun stop_returnsM4AFileAndDurationThenFinishes() = runBlocking {
        val rec = makeRecorder()
        rec.start()
        val result = rec.stop()
        assertEquals(VoiceRecorder.State.Finished, rec.state.value)
        assertEquals("m4a", result?.file?.extension)
        assertTrue((result?.duration ?: Duration.INFINITE) >= Duration.ZERO)
    }

    @Test
    fun cancel_returnsToIdleAndDiscardsRecording() = runBlocking {
        val fake = FakeAudioRecorder()
        val rec = makeRecorder(fake = fake)
        rec.start()
        rec.cancel()
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
        assertEquals(1, fake.stopCalls)
    }

    @Test
    fun start_whileRecording_throwsAlreadyRecording() = runBlocking {
        val rec = makeRecorder()
        rec.start()
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.AlreadyRecording, error)
    }

    @Test
    fun start_permissionDenied_throwsAndStaysIdle() = runBlocking {
        val rec = makeRecorder(permission = false)
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.PermissionDenied, error)
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
    }

    @Test
    fun start_recordFailure_throwsRecordFailed() = runBlocking {
        val fake = FakeAudioRecorder(recordReturn = false)
        val rec = makeRecorder(fake = fake)
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.RecordFailed, error)
    }

    @Test
    fun stop_whenIdle_returnsNull() {
        val rec = makeRecorder()
        assertNull(rec.stop())
    }

    @Test
    fun stop_whenRecorderStopFails_returnsNullAndDeletesTheFile() = runBlocking {
        val dir = tempDir()
        val fake = FakeAudioRecorder(stopReturn = false)
        val rec = VoiceRecorder(
            requestPermission = { true },
            makeRecorder = { fake },
            tempDirectory = dir,
        )
        rec.start()
        val result = rec.stop()
        assertNull(result)
        assertEquals(VoiceRecorder.State.Finished, rec.state.value)
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun start_afterFinish_beginsAnotherRecording() = runBlocking {
        val rec = makeRecorder()
        rec.start()
        rec.stop()
        assertEquals(VoiceRecorder.State.Finished, rec.state.value)
        rec.start()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
    }

    @Test
    fun cancel_duringPermissionAwait_abortsTheStart() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val fake = FakeAudioRecorder()
        val rec = VoiceRecorder(
            requestPermission = { reached.complete(Unit); gate.await() },
            makeRecorder = { fake },
            tempDirectory = tempDir(),
        )

        val job = launch { rec.start() }
        reached.await()
        rec.cancel()
        gate.complete(true)
        job.join()

        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
        assertEquals(0, fake.recordCalls)
    }

    @Test
    fun secondStart_duringPermissionAwait_throwsAlreadyRecording() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val rec = VoiceRecorder(
            requestPermission = { reached.complete(Unit); gate.await() },
            makeRecorder = { FakeAudioRecorder() },
            tempDirectory = tempDir(),
        )

        val job = launch { rec.start() }
        reached.await()
        // State is still Idle here — the isStarting flag must reject the
        // overlapping second call anyway.
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.AlreadyRecording, error)
        gate.complete(true)
        job.join()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
    }

    @Test
    fun start_recordFailure_staysIdleAndRecoverable() = runBlocking {
        val fake = FakeAudioRecorder(recordReturn = false)
        val rec = makeRecorder(fake = fake)
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.RecordFailed, error)
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)

        fake.recordReturn = true
        rec.start()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
    }

    // MARK: keep-screen-awake claim/release (apple #159)

    @Test
    fun start_claimsKeepScreenAwakeWhileRecording() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(keepAwakeCalls = calls)
        rec.start()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun stop_releasesKeepScreenAwake() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(keepAwakeCalls = calls)
        rec.start()
        rec.stop()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun cancel_releasesKeepScreenAwake() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(keepAwakeCalls = calls)
        rec.start()
        rec.cancel()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun start_recordFailure_neverClaimsKeepScreenAwake() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(fake = FakeAudioRecorder(recordReturn = false), keepAwakeCalls = calls)
        runCatching { rec.start() }
        assertTrue(calls.isEmpty())
    }
}
