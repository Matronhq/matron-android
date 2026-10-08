package chat.matron.android.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// How long the app may sit in the background before it locks again.
///
/// [seconds] doubles as the persisted raw value, so the stored form stays
/// readable and a future option can be added without renumbering.
enum class AppLockTimeout(val seconds: Long, val title: String, val shortTitle: String) {
    IMMEDIATELY(0, "Immediately", "Now"),
    ONE_MINUTE(60, "After 1 minute", "1 min"),
    FIVE_MINUTES(300, "After 5 minutes", "5 min"),
    FIFTEEN_MINUTES(900, "After 15 minutes", "15 min"),
    ONE_HOUR(3600, "After 1 hour", "1 hour");

    companion object {
        /// Decodes a persisted raw value, tolerating one this build no longer offers.
        fun fromSeconds(seconds: Int): AppLockTimeout? =
            entries.firstOrNull { it.seconds == seconds.toLong() }
    }
}

/// Seam over the platform biometric prompt so lock logic is testable without
/// real hardware. Ports the Swift `BiometricAuthenticating` protocol; the
/// production implementation is
/// [chat.matron.android.platform.AndroidBiometricAuthenticator].
interface BiometricAuthenticating {
    /// A user-facing name for the strongest available method, or `null` when
    /// the device can evaluate NOTHING — no enrolled biometric AND no device
    /// credential. `null` is what drives the stand-down rule below.
    ///
    /// Android has no public API naming the specific sensor (the iOS original
    /// could say "Face ID" / "Touch ID"), so the production implementation
    /// returns a class of method rather than a brand.
    fun availableMethodName(): String?

    /// Runs one authentication. Returns `false` when the attempt completed
    /// without succeeding; throws when the platform reported an error whose
    /// message is worth showing (lockout, cancellation, no hardware).
    suspend fun authenticate(reason: String): Boolean
}

/// App-wide biometric lock: opt-in, relocks after a configurable idle period,
/// and always locks on cold launch while enabled.
///
/// Ports `MatronShared/Sources/ViewModels/AppLockController.swift` (matron-apple
/// #83). The Swift original is a `@MainActor @Observable` class; this one is a
/// plain class exposing [StateFlow]s, matching how the rest of this layer was
/// ported. It carries the same main-thread confinement by convention — every
/// mutator is called from a Compose effect or an Android lifecycle callback,
/// both of which run on the main thread — so the generation counter and the
/// backgrounded timestamp need no synchronisation.
///
/// The invariants worth stating outright, because each one exists to stop a
/// specific way an app lock can go wrong:
///
/// 1. **Stand-down.** If nothing can be evaluated (biometrics unenrolled AND
///    screen lock removed since the user opted in), the lock does not engage
///    and an engaged lock releases. A lock nothing can open is a permanent
///    lockout, not security — and removing the screen lock already required
///    owning an unlocked device.
/// 2. **Enabling authenticates first.** The user proves the method works
///    before it is allowed to stand between them and their chats. Disabling
///    needs no auth: reaching the toggle means the app is already unlocked.
/// 3. **A later intent beats an in-flight prompt.** [setEnabled] carries a
///    generation counter, so an enable whose system prompt outlives the user's
///    change of mind cannot re-enable behind their back.
/// 4. **Sign-out is refused while locked** ([signOutIfUnlocked]) — signing out
///    clears the lock, so allowing it from the shield would hand an
///    unauthenticated holder an unlocked app.
class AppLockController(
    private val auth: BiometricAuthenticating,
    private val store: KeyValueStore,
    /// Injected clock in epoch milliseconds. Wall clock rather than
    /// `SystemClock.elapsedRealtime()`: one clock for prod and tests beats a
    /// split default, and moving the wall clock backwards to stretch the idle
    /// window requires an already-unlocked device — which defeats the lock more
    /// directly anyway.
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val _isEnabled = MutableStateFlow(store.getBoolean(ENABLED_KEY, false))

    /// Whether the user has opted in. Persisted.
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _timeout = MutableStateFlow(
        store.getIntOrNull(TIMEOUT_KEY)?.let { AppLockTimeout.fromSeconds(it) }
            ?: AppLockTimeout.FIVE_MINUTES
    )

    /// Idle grace period before a background trip locks the app. Persisted.
    val timeout: StateFlow<AppLockTimeout> = _timeout.asStateFlow()

    private val _isLocked = MutableStateFlow(
        // A cold launch has no trusted "last active" moment, so an enabled lock
        // always engages at startup — subject to the stand-down rule.
        _isEnabled.value && auth.availableMethodName() != null
    )

    /// The shield is up. The app root renders the lock screen INSTEAD of any
    /// content while this holds, so nothing can flash behind it.
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _isAuthenticating = MutableStateFlow(false)

    /// An evaluation is on screen. Drives the button's spinner, and suppresses
    /// the idle countdown (the system credential prompt is a separate activity,
    /// so it stops ours — that is not the user leaving the app).
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)

    /// Why the last attempt didn't succeed, or `null`.
    val authError: StateFlow<String?> = _authError.asStateFlow()

    /// When the most recent evaluation finished, success or not. Hosts use this
    /// to tell the auth prompt's own stop/start churn apart from a genuine app
    /// switch. Read from the main thread only.
    var lastAuthEndedAt: Long? = null
        private set

    /// `null` when the device can evaluate nothing — the settings section hides
    /// itself off this.
    val methodName: String?
        get() = auth.availableMethodName()

    /// Earliest moment of the current background stay, or `null` while
    /// foregrounded. Earliest, not latest: a stop/start blip on the way out
    /// (permission dialog, task switcher preview) must not restart the
    /// countdown from a later moment than the user's actual departure.
    private var backgroundedAt: Long? = null

    /// Monotonic intent counter — see invariant 3 in the class docs.
    private var enableGeneration = 0

    /// Turns the lock on or off. Enabling runs an evaluation first and aborts
    /// on anything but success, so a device whose sensor is broken can never
    /// lock the user out of their own account.
    suspend fun setEnabled(enabled: Boolean) {
        _authError.value = null
        // Bumped BEFORE the no-op guard: a disable that matches the current
        // state still expresses fresh intent, and must invalidate an enable
        // suspended in its prompt.
        enableGeneration += 1
        val generation = enableGeneration
        if (enabled == _isEnabled.value) return
        if (enabled) {
            if (!runAuth(CONFIRM_REASON)) return
            // The user may have reversed the toggle while the prompt was up.
            if (generation != enableGeneration) return
        }
        _isEnabled.value = enabled
        store.setBoolean(ENABLED_KEY, enabled)
        if (!enabled) _isLocked.value = false
    }

    /// Persists a new idle grace period. Takes effect on the next background trip.
    fun setTimeout(value: AppLockTimeout) {
        _timeout.value = value
        store.setInt(TIMEOUT_KEY, value.seconds.toInt())
    }

    /// Call when the app leaves the foreground (`ON_STOP`).
    fun noteBackgrounded() {
        if (!_isEnabled.value || _isLocked.value || _isAuthenticating.value) return
        if (backgroundedAt == null) backgroundedAt = now()
    }

    /// Call when the app returns to the foreground (`ON_START`). Locks when the
    /// background stay outran [timeout].
    fun noteForegrounded() {
        val since = backgroundedAt
        backgroundedAt = null
        if (!_isEnabled.value || _isLocked.value || since == null) return
        // Stand-down: don't engage a lock nothing can open.
        if (auth.availableMethodName() == null) return
        if (now() - since >= _timeout.value.seconds * 1_000) _isLocked.value = true
    }

    /// Prompts for authentication and, on success, lifts the shield.
    suspend fun unlock() {
        if (!_isLocked.value || _isAuthenticating.value) return
        // The auth method can vanish WHILE locked (screen lock removed with the
        // app backgrounded but still resident). Same reasoning as the cold-launch
        // guard: stand down rather than strand the user.
        if (auth.availableMethodName() == null) {
            _isLocked.value = false
            backgroundedAt = null
            _authError.value = null
            return
        }
        _authError.value = null
        if (runAuth(UNLOCK_REASON)) {
            _isLocked.value = false
            backgroundedAt = null
        }
    }

    /// A completed interactive sign-in counts as authentication: the user just
    /// proved account access, so the fresh session must not open behind a
    /// shield left over from the previous one.
    fun noteSignedIn() {
        _isLocked.value = false
        backgroundedAt = null
        _authError.value = null
    }

    /// Runs [signOut] only while unlocked; returns whether it ran. Invariant 4.
    ///
    /// Belt and braces given the shield replaces the content that hosts the
    /// sign-out menu, but the refusal belongs with the lock state rather than
    /// with whichever screen happens to own the button today.
    fun signOutIfUnlocked(signOut: () -> Unit): Boolean {
        if (_isLocked.value) return false
        signOut()
        return true
    }

    /// Clears lock state on sign-out, the way every other per-account
    /// preference is cleared: the next account starts opted out rather than
    /// inheriting a lock it never agreed to. Also bumps the generation so an
    /// enable prompt left on screen by the outgoing session can't land.
    fun resetForSignOut() {
        enableGeneration += 1
        _isEnabled.value = false
        store.setBoolean(ENABLED_KEY, false)
        _isLocked.value = false
        backgroundedAt = null
        _authError.value = null
    }

    private suspend fun runAuth(reason: String): Boolean {
        _isAuthenticating.value = true
        try {
            if (auth.authenticate(reason)) return true
            _authError.value = "Authentication didn't complete — try again."
        } catch (cancellation: CancellationException) {
            // Structured-concurrency cancellation is not an auth failure.
            throw cancellation
        } catch (error: Throwable) {
            _authError.value = error.message?.takeIf { it.isNotBlank() }
                ?: "Authentication failed — try again."
        } finally {
            _isAuthenticating.value = false
            lastAuthEndedAt = now()
        }
        return false
    }

    companion object {
        const val ENABLED_KEY = "AppLock.enabled"
        const val TIMEOUT_KEY = "AppLock.timeout"

        /// Shown as the prompt subtitle when lifting the shield.
        const val UNLOCK_REASON = "Unlock Matron"

        /// Shown as the prompt subtitle when opting in.
        const val CONFIRM_REASON = "Confirm you can unlock Matron"
    }
}
