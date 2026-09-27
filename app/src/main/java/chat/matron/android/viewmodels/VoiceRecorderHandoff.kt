package chat.matron.android.viewmodels

/// The composition-bound halves of a `VoiceRecorder`'s wiring: the permission
/// launcher and the window flag belong to whichever `ComposerView` is currently
/// on screen, not to the recorder. They are rebound on every composition so a
/// recorder that outlived its first composer (see [VoiceRecorderHandoff]) keeps
/// working from the next one — a launcher from a disposed composition throws.
class VoiceRecorderBindings {
    var requestPermission: suspend () -> Boolean = { false }
    var setKeepScreenAwake: (Boolean) -> Unit = {}
}

/// A recorder together with its rebindable wiring — the unit that survives a
/// composer being torn down.
class VoiceRecorderHost(val bindings: VoiceRecorderBindings, val recorder: VoiceRecorder)

/// Carries an in-progress voice note across the app lock.
///
/// The lock shield REPLACES the app's composition rather than covering it, so
/// returning after the lock timeout disposes `ComposerView` — whose teardown
/// cancels the recorder. With the microphone foreground session keeping capture
/// alive in the background, that turned every app switch (with the Immediate
/// timeout) into a discarded note. A composer disposed while the app is locked
/// parks its live recording here instead; the composer that next opens the
/// same room reads it back ([parkedFor]) still recording, takes ownership once
/// its composition is committed ([confirmReclaim]), and `SignedInApp` steers
/// the post-unlock navigation into that room so that actually happens.
/// Any other teardown — navigating away, leaving the chat — still cancels, as
/// before: the note is bound to the room it was started in.
///
/// Process-wide, like `ComposerDraftMemory`: the per-room view-model cache
/// lives inside the composition the shield replaces, so nothing shorter-lived
/// would survive.
object VoiceRecorderHandoff {
    private val parked = LinkedHashMap<String, VoiceRecorderHost>()

    /// The composer for [roomID] is going away. Parks [host] when a recording
    /// is live and the app is locked (the shield is what took the composer
    /// down); otherwise cancels it. Returns true when parked.
    fun onComposerDisposed(roomID: String, host: VoiceRecorderHost, appLocked: Boolean): Boolean {
        val recording = host.recorder.state.value is VoiceRecorder.State.Recording
        if (!recording || !appLocked) {
            host.recorder.cancel()
            return false
        }
        // A stale entry for the same room (an earlier lock that was never
        // reclaimed) would otherwise hold the mic forever.
        parked.remove(roomID)?.takeIf { it !== host }?.recorder?.cancel()
        parked[roomID] = host
        return true
    }

    /// The recording parked for [roomID], if any — a read, not a takeover.
    /// Meant to be called from composition (inside `remember`), which Compose
    /// may run and then discard without retaining the result; removing the
    /// entry there would orphan a live capture and its microphone session.
    /// Calling this twice before [confirmReclaim] returns the same host.
    fun parkedFor(roomID: String): VoiceRecorderHost? = parked[roomID]

    /// The composer that read [host] via [parkedFor] is now committed (a
    /// `DisposableEffect` ran) and owns it: drop the parked entry. Identity-
    /// checked so a composer holding a fresh recorder never evicts a note
    /// parked for the same room by someone else.
    fun confirmReclaim(roomID: String, host: VoiceRecorderHost) {
        if (parked[roomID] === host) parked.remove(roomID)
    }

    /// The room a parked recording belongs to, if any — where the app should
    /// land after unlock.
    fun parkedRoomID(): String? = parked.keys.lastOrNull()

    fun resetForTesting() {
        parked.values.forEach { it.recorder.cancel() }
        parked.clear()
    }
}
