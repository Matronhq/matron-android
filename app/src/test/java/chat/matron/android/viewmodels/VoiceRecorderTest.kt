package chat.matron.android.viewmodels

import chat.matron.android.models.MatronDebug
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        var pauseReturn: Boolean = true,
        var resumeReturn: Boolean = true,
        var amplitude: Int = 1234,
    ) : AudioRecording {
        var recordCalls = 0
            private set
        var stopCalls = 0
            private set
        var pauseCalls = 0
            private set
        var resumeCalls = 0
            private set

        override fun record(): Boolean {
            recordCalls++
            return recordReturn
        }

        override fun pause(): Boolean {
            pauseCalls++
            return pauseReturn
        }

        override fun resume(): Boolean {
            resumeCalls++
            return resumeReturn
        }

        override fun stop(): Boolean {
            stopCalls++
            return stopReturn
        }

        override fun peakAmplitude(): Int = amplitude
    }

    /// Fake interruption source (apple #180's `FakeInterruptions`): captures
    /// the recorder's handler so a test can deliver began/ended events, and
    /// counts how often the subscription was torn down.
    private class FakeInterruptions {
        var handler: ((AudioInterruption) -> Unit)? = null
        var cancelCalls = 0
            private set

        fun source(handler: (AudioInterruption) -> Unit): () -> Unit {
            this.handler = handler
            return { cancelCalls++ }
        }

        fun deliver(event: AudioInterruption) = handler?.invoke(event)
    }

    private fun tempDir(): File = Files.createTempDirectory("voice").toFile()

    private fun makeRecorder(
        permission: Boolean = true,
        fake: FakeAudioRecorder = FakeAudioRecorder(),
        keepAwakeCalls: MutableList<Boolean> = mutableListOf(),
        sessionCalls: MutableList<Boolean> = mutableListOf(),
        interruptions: FakeInterruptions = FakeInterruptions(),
        now: () -> Instant = Instant::now,
        holdRecordingSession: (Boolean) -> Unit = { sessionCalls.add(it) },
        observeInterruptions: ((AudioInterruption) -> Unit) -> (() -> Unit) = interruptions::source,
    ) = VoiceRecorder(
        requestPermission = { permission },
        makeRecorder = { fake },
        tempDirectory = tempDir(),
        setKeepScreenAwake = { keepAwakeCalls.add(it) },
        holdRecordingSession = holdRecordingSession,
        observeInterruptions = observeInterruptions,
        describeInputRoute = { "inputs=[fake-mic]" },
        now = now,
    )

    private val defaultSink = MatronDebug.sink

    @After
    fun restoreSink() {
        MatronDebug.sink = defaultSink
    }

    /// Captures `MatronDebug` breadcrumbs from the recorder for the
    /// diagnostics assertions (apple #181).
    private fun captureLog(): List<String> {
        val lines = mutableListOf<String>()
        MatronDebug.sink = { _, message -> lines.add(message) }
        return lines
    }

    private fun recordingState(rec: VoiceRecorder): VoiceRecorder.State.Recording {
        val state = rec.state.value
        assertTrue("expected Recording, was $state", state is VoiceRecorder.State.Recording)
        return state as VoiceRecorder.State.Recording
    }

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

    // MARK: Microphone foreground session (apple #180's `audio` background mode)

    @Test
    fun start_holdsTheRecordingSessionOnceCaptureIsLive() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(sessionCalls = calls)
        rec.start()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun stop_releasesTheRecordingSession() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(sessionCalls = calls)
        rec.start()
        rec.stop()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun cancel_releasesTheRecordingSession() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(sessionCalls = calls)
        rec.start()
        rec.cancel()
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun start_recordFailure_neverHoldsTheRecordingSession() = runBlocking {
        val calls = mutableListOf<Boolean>()
        val rec = makeRecorder(fake = FakeAudioRecorder(recordReturn = false), sessionCalls = calls)
        runCatching { rec.start() }
        assertTrue(calls.isEmpty())
    }

    // MARK: Rollback when a later start step fails (CodeRabbit, android #70)

    /// A refused foreground-service start must not leave a live recorder,
    /// a Recording state, or a held screen claim behind — and must surface
    /// as the RecorderError the composer handles, not a crash.
    @Test
    fun start_whenHoldRecordingSessionThrows_rollsEverythingBackToIdle() = runBlocking {
        val fake = FakeAudioRecorder()
        val keepAwake = mutableListOf<Boolean>()
        val sessions = mutableListOf<Boolean>()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(
            fake = fake,
            keepAwakeCalls = keepAwake,
            interruptions = interruptions,
            holdRecordingSession = { held -> sessions.add(held); if (held) throw IllegalStateException("not allowed") },
        )
        val error = runCatching { rec.start() }.exceptionOrNull()
        assertEquals(VoiceRecorder.RecorderError.RecordFailed, error)
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
        assertEquals("the started recorder is stopped again", 1, fake.stopCalls)
        assertEquals(listOf(true, false), keepAwake)
        assertEquals("the attempted claim is released either way", listOf(true, false), sessions)
        assertNull("never subscribed", interruptions.handler)
    }

    @Test
    fun start_whenObserveInterruptionsThrows_releasesTheClaimsAndReturnsToIdle() = runBlocking {
        val fake = FakeAudioRecorder()
        val keepAwake = mutableListOf<Boolean>()
        val sessions = mutableListOf<Boolean>()
        val rec = makeRecorder(
            fake = fake,
            keepAwakeCalls = keepAwake,
            sessionCalls = sessions,
            observeInterruptions = { throw IllegalStateException("no audio manager") },
        )
        assertEquals(VoiceRecorder.RecorderError.RecordFailed, runCatching { rec.start() }.exceptionOrNull())
        assertEquals(VoiceRecorder.State.Idle, rec.state.value)
        assertEquals(1, fake.stopCalls)
        assertEquals(listOf(true, false), keepAwake)
        assertEquals(listOf(true, false), sessions)
    }

    @Test
    fun start_afterARolledBackStart_recordsAgainCleanly() = runBlocking {
        val dir = tempDir()
        val fake = FakeAudioRecorder()
        var refuse = true
        val rec = VoiceRecorder(
            requestPermission = { true },
            makeRecorder = { fake },
            tempDirectory = dir,
            holdRecordingSession = { held -> if (held && refuse) throw IllegalStateException("not allowed") },
        )
        runCatching { rec.start() }
        assertTrue("no orphan temp file", dir.listFiles()?.isEmpty() ?: true)

        refuse = false
        rec.start()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
        assertEquals(2, fake.recordCalls)
    }

    /// The diagnostics seam is breadcrumbs, never a reason to fail a note.
    @Test
    fun start_whenRouteDiagnosticsThrow_stillRecords() = runBlocking {
        val log = captureLog()
        val rec = VoiceRecorder(
            requestPermission = { true },
            makeRecorder = { FakeAudioRecorder() },
            tempDirectory = tempDir(),
            describeInputRoute = { throw IllegalStateException("boom") },
        )
        rec.start()
        assertTrue(rec.state.value is VoiceRecorder.State.Recording)
        assertTrue(log.toString(), log.any { it.startsWith("${VoiceRecorder.LOG_PREFIX} start:") && it.contains("route=error") })
    }

    // MARK: Interruptions (calls, the assistant, another app taking the mic)

    /// Android leaves the MediaRecorder running when focus is lost, so the
    /// recorder must pause it itself — and the state must say so, or the UI
    /// would claim to capture a call it is not hearing.
    @Test
    fun interruptionBegan_pausesTheRecorderAndReportsPaused() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        assertNotNull("subscribed for the life of the recording", interruptions.handler)
        assertFalse(recordingState(rec).isPaused)

        interruptions.deliver(AudioInterruption.Began)
        assertEquals(1, fake.pauseCalls)
        assertTrue(recordingState(rec).isPaused)
    }

    /// When the interruption ends with the resume hint, capture must pick up
    /// again in the same file — otherwise the note would silently stop at the
    /// call while the UI still says "recording".
    @Test
    fun interruptionEnded_withResumeHint_resumesTheRecorder() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        val start = recordingState(rec).start

        interruptions.deliver(AudioInterruption.Began)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertEquals(1, fake.resumeCalls)
        assertEquals(1, fake.recordCalls)
        val state = recordingState(rec)
        assertFalse(state.isPaused)
        assertEquals("same recording, same start", start, state.start)
    }

    /// Without the hint the system does not want us back on the mic (another
    /// app took it). The recorder stays paused; Stop still delivers what was
    /// captured up to the interruption.
    @Test
    fun interruptionEnded_withoutResumeHint_leavesRecorderPaused() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        interruptions.deliver(AudioInterruption.Began)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = false))
        assertEquals(0, fake.resumeCalls)
        assertTrue(recordingState(rec).isPaused)
        assertNotNull("the captured part is still delivered", rec.stop())
        assertEquals(1, fake.stopCalls)
        assertEquals(VoiceRecorder.State.Finished, rec.state.value)
    }

    /// An `Ended` with no matching `Began` is noise (a stale event from a
    /// previous focus request), not a reason to poke the recorder.
    @Test
    fun interruptionEnded_withoutBegan_isIgnored() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertEquals(0, fake.resumeCalls)
        assertFalse(recordingState(rec).isPaused)
    }

    /// A second `Began` while already paused (a call arriving during a
    /// transient loss) must not pause twice or restart the paused clock.
    @Test
    fun interruptionBegan_whileAlreadyPaused_isIdempotent() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        interruptions.deliver(AudioInterruption.Began)
        interruptions.deliver(AudioInterruption.Began)
        assertEquals(1, fake.pauseCalls)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertEquals(1, fake.resumeCalls)
        assertFalse(recordingState(rec).isPaused)
    }

    /// If the recorder refuses to pause, capture is still running: the state
    /// must not claim otherwise, and the later `Ended` must not try to resume
    /// a recorder that never paused.
    @Test
    fun interruptionBegan_whenPauseFails_staysRecording() = runBlocking {
        val fake = FakeAudioRecorder(pauseReturn = false)
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        interruptions.deliver(AudioInterruption.Began)
        assertFalse(recordingState(rec).isPaused)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertEquals(0, fake.resumeCalls)
    }

    /// The duration handed to the send path is capture time, not wall time: a
    /// two-minute call in the middle of a note is silence the recorder never
    /// captured.
    @Test
    fun duration_excludesInterruptedTime_whenResumed() = runBlocking {
        val t0 = Instant.ofEpochSecond(1_000)
        var now = t0
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(interruptions = interruptions, now = { now })
        rec.start()
        now = t0.plusSeconds(10)
        interruptions.deliver(AudioInterruption.Began)
        now = t0.plusSeconds(15)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        now = t0.plusSeconds(20)
        assertEquals(15.seconds, rec.stop()?.duration)
    }

    /// Not resumed: paused from the interruption until Stop, so nothing after
    /// `Began` counts.
    @Test
    fun duration_excludesInterruptedTime_whenNotResumed() = runBlocking {
        val t0 = Instant.ofEpochSecond(1_000)
        var now = t0
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(interruptions = interruptions, now = { now })
        rec.start()
        now = t0.plusSeconds(10)
        interruptions.deliver(AudioInterruption.Began)
        now = t0.plusSeconds(15)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = false))
        now = t0.plusSeconds(20)
        assertEquals(10.seconds, rec.stop()?.duration)
    }

    /// A resume that fails leaves the recorder paused, so the pause keeps
    /// running until Stop — and the state keeps saying paused.
    @Test
    fun duration_excludesTimeAfterAFailedResume() = runBlocking {
        val t0 = Instant.ofEpochSecond(1_000)
        var now = t0
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions, now = { now })
        rec.start()
        now = t0.plusSeconds(10)
        interruptions.deliver(AudioInterruption.Began)
        fake.resumeReturn = false
        now = t0.plusSeconds(15)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertTrue(recordingState(rec).isPaused)
        now = t0.plusSeconds(20)
        assertEquals(10.seconds, rec.stop()?.duration)
    }

    @Test
    fun stopAndCancel_unsubscribeFromInterruptions() = runBlocking {
        val fake = FakeAudioRecorder()
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.start()
        rec.stop()
        assertEquals(1, interruptions.cancelCalls)
        interruptions.deliver(AudioInterruption.Began)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        assertEquals("a finished recording never pauses or resumes", 0, fake.pauseCalls)
        assertEquals(0, fake.resumeCalls)

        rec.start()
        rec.cancel()
        assertEquals(2, interruptions.cancelCalls)
    }

    /// Interruption bookkeeping is per recording: a pause left open by the
    /// previous note must not shorten the next one.
    @Test
    fun interruptionState_resetsBetweenRecordings() = runBlocking {
        val t0 = Instant.ofEpochSecond(1_000)
        var now = t0
        val interruptions = FakeInterruptions()
        val rec = makeRecorder(interruptions = interruptions, now = { now })
        rec.start()
        interruptions.deliver(AudioInterruption.Began)
        rec.cancel()

        now = t0.plusSeconds(100)
        rec.start()
        assertFalse(recordingState(rec).isPaused)
        now = t0.plusSeconds(110)
        assertEquals(10.seconds, rec.stop()?.duration)
    }

    // MARK: Diagnostics (apple #181)

    @Test
    fun start_logsTheInputRoute() = runBlocking {
        val log = captureLog()
        val rec = makeRecorder()
        rec.start()
        val line = log.single { it.startsWith("${VoiceRecorder.LOG_PREFIX} start:") }
        assertTrue(line, line.contains("inputs=[fake-mic]"))
    }

    @Test
    fun stop_logsDurationPausedSpanBytesAndPeak() = runBlocking {
        val t0 = Instant.ofEpochSecond(1_000)
        var now = t0
        val fake = FakeAudioRecorder(amplitude = 4321)
        val interruptions = FakeInterruptions()
        val log = captureLog()
        val rec = makeRecorder(fake = fake, interruptions = interruptions, now = { now })
        rec.start()
        now = t0.plusSeconds(10)
        interruptions.deliver(AudioInterruption.Began)
        now = t0.plusSeconds(15)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        now = t0.plusSeconds(20)
        rec.stop()
        val line = log.single { it.startsWith("${VoiceRecorder.LOG_PREFIX} stop:") }
        assertTrue(line, line.contains("duration=15000ms"))
        assertTrue(line, line.contains("paused=5000ms"))
        assertTrue(line, line.contains("bytes="))
        assertTrue(line, line.contains("peak=4321"))
        assertTrue(line, line.contains("inputs=[fake-mic]"))
    }

    @Test
    fun interruptions_areLoggedWithTheRecorderState() = runBlocking {
        val interruptions = FakeInterruptions()
        val log = captureLog()
        val rec = makeRecorder(interruptions = interruptions)
        rec.start()
        interruptions.deliver(AudioInterruption.Began)
        interruptions.deliver(AudioInterruption.Ended(shouldResume = true))
        val lines = log.filter { it.startsWith("${VoiceRecorder.LOG_PREFIX} interruption:") }
        assertTrue(lines.toString(), lines.any { it.contains("Began") && it.contains("wasInterrupted=false") })
        assertTrue(lines.toString(), lines.any { it.contains("Ended(shouldResume=true)") && it.contains("wasInterrupted=true") })
        assertTrue(log.toString(), log.any { it.startsWith("${VoiceRecorder.LOG_PREFIX} resume:") })
    }

    @Test
    fun sampleLevel_logsThePeakOnlyWhileCapturing() = runBlocking {
        val fake = FakeAudioRecorder(amplitude = 777)
        val interruptions = FakeInterruptions()
        val log = captureLog()
        val rec = makeRecorder(fake = fake, interruptions = interruptions)
        rec.sampleLevel()
        assertTrue("idle: nothing to sample", log.none { it.contains("level:") })

        rec.start()
        rec.sampleLevel()
        assertEquals(1, log.count { it == "${VoiceRecorder.LOG_PREFIX} level: peak=777" })

        interruptions.deliver(AudioInterruption.Began)
        rec.sampleLevel()
        assertEquals("paused: no sample", 1, log.count { it.contains("level:") })
    }
}
