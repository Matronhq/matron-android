package chat.matron.android.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// Gated [BiometricAuthenticating] double. `hold` parks `authenticate()` on a
/// [CompletableDeferred] until [release], modelling the system prompt sitting on
/// screen — the Kotlin analogue of the Swift suite's `CheckedContinuation`
/// waiter list, and the same gate pattern `FakeDeviceLinker` uses.
class FakeBiometricAuthenticator : BiometricAuthenticating {
    var method: String? = "biometrics"

    /// Scripted outcome once the call is allowed to proceed: `success(true)`
    /// authenticated, `success(false)` completed without succeeding,
    /// `failure(_)` the platform reported an error.
    var result: Result<Boolean> = Result.success(true)

    var hold = false
    var authenticateCalls = 0
    val reasons = mutableListOf<String>()

    /// Completes as soon as a held call reaches the gate, so a test can wait for
    /// the prompt to be "on screen" instead of racing it.
    val gateReached = CompletableDeferred<Unit>()
    private val gateRelease = CompletableDeferred<Boolean>()

    override fun availableMethodName(): String? = method

    override suspend fun authenticate(reason: String): Boolean {
        authenticateCalls += 1
        reasons += reason
        if (hold) {
            gateReached.complete(Unit)
            return gateRelease.await()
        }
        return result.getOrThrow()
    }

    fun release(value: Boolean = true) {
        gateRelease.complete(value)
    }
}

/// Ported from matron-apple's `AppLockControllerTests` (#83), plus the cases the
/// Android adaptation adds: [AppLockController.noteSignedIn],
/// [AppLockController.resetForSignOut], the sign-out refusal, and the unlock
/// prompt's own stop/start churn.
///
/// Gated tests follow the layer's established recipe: a `CoroutineScope` built
/// from `runBlocking`'s context plus a fresh [Job], so the parked coroutine runs
/// cooperatively on the one test thread (main-thread confinement, no races) and
/// `runBlocking` doesn't await it.
class AppLockControllerTest {

    private lateinit var auth: FakeBiometricAuthenticator
    private lateinit var store: InMemoryKeyValueStore

    /// Injected clock, in epoch milliseconds. Tests advance it by assignment.
    private var clock = 1_000_000_000L

    @Before
    fun setUp() {
        auth = FakeBiometricAuthenticator()
        store = InMemoryKeyValueStore()
        clock = 1_000_000_000L
    }

    private fun makeController() =
        AppLockController(auth = auth, store = store, now = { clock })

    // MARK: - Defaults and cold launch

    @Test
    fun disabledByDefault_andUnlocked() {
        val lock = makeController()
        assertFalse(lock.isEnabled.value)
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun coldLaunch_whileEnabled_startsLocked() {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        assertTrue(makeController().isLocked.value)
    }

    @Test
    fun coldLaunch_enabledButNoAuthMethod_doesNotLock() {
        // Screen lock removed since opting in: a lock nothing can open is a
        // permanent lockout, not security — so it stands down.
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        auth.method = null
        assertFalse(makeController().isLocked.value)
    }

    @Test
    fun timeout_defaultsToFiveMinutes() {
        assertEquals(AppLockTimeout.FIVE_MINUTES, makeController().timeout.value)
    }

    @Test
    fun timeout_persistsAndRestores() {
        makeController().setTimeout(AppLockTimeout.ONE_HOUR)
        assertEquals(AppLockTimeout.ONE_HOUR, makeController().timeout.value)
    }

    @Test
    fun timeout_immediatelyPersists_andIsNotReadBackAsUnset() {
        // `Immediately` is raw value 0 — the case the Swift original's
        // `integer(forKey:)` could not tell apart from "never stored".
        makeController().setTimeout(AppLockTimeout.IMMEDIATELY)
        assertEquals(AppLockTimeout.IMMEDIATELY, makeController().timeout.value)
    }

    // MARK: - Enabling and disabling

    @Test
    fun enable_authenticatesFirst_andPersists() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        assertEquals(1, auth.authenticateCalls)
        assertEquals(AppLockController.CONFIRM_REASON, auth.reasons.single())
        assertTrue(lock.isEnabled.value)
        assertTrue(store.getBoolean(AppLockController.ENABLED_KEY))
        assertFalse("enabling must not lock the app the user is holding", lock.isLocked.value)
    }

    @Test
    fun enable_authFailure_leavesDisabled() = runBlocking {
        auth.result = Result.failure(IllegalStateException("sensor is busy"))
        val lock = makeController()
        lock.setEnabled(true)
        assertFalse(lock.isEnabled.value)
        assertFalse(store.getBoolean(AppLockController.ENABLED_KEY))
        assertEquals("the platform's own message is the useful one", "sensor is busy", lock.authError.value)
    }

    @Test
    fun enable_authDeclined_leavesDisabled() = runBlocking {
        auth.result = Result.success(false)
        val lock = makeController()
        lock.setEnabled(true)
        assertFalse(lock.isEnabled.value)
        assertNotNull(lock.authError.value)
    }

    @Test
    fun disable_needsNoAuth_andClearsLock() = runBlocking {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        assertTrue(lock.isLocked.value)
        lock.setEnabled(false)
        assertEquals(0, auth.authenticateCalls)
        assertFalse(lock.isEnabled.value)
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun disableDuringEnableAuthPrompt_wins() = runBlocking {
        // The user flips the toggle on, the system prompt sits on screen, they
        // change their mind and flip it off — the enable completing afterwards
        // must NOT re-enable behind their back.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            auth.hold = true
            val lock = makeController()
            val enable = scope.async { lock.setEnabled(true) }
            auth.gateReached.await()
            assertEquals("enable is parked in its prompt", 1, auth.authenticateCalls)

            lock.setEnabled(false)
            auth.release(true)
            enable.await()

            assertFalse("the later disable intent wins", lock.isEnabled.value)
            assertFalse(store.getBoolean(AppLockController.ENABLED_KEY))
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun enable_survivesTheConfirmPromptsOwnBackgroundTrip() = runBlocking {
        // The device-credential fallback runs in a separate system activity, so
        // it stops ours and starts it again — churn the lock must not read as
        // the user leaving, or opting in would instantly lock the app.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val lock = makeController()
            lock.setTimeout(AppLockTimeout.IMMEDIATELY)
            auth.hold = true

            val enable = scope.async { lock.setEnabled(true) }
            auth.gateReached.await()
            lock.noteBackgrounded() // credential activity takes the foreground
            auth.release(true)
            enable.await()
            lock.noteForegrounded() // and hands it back

            assertTrue(lock.isEnabled.value)
            assertFalse("opting in must not immediately lock", lock.isLocked.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    // MARK: - Idle timeout

    @Test
    fun backgroundShorterThanTimeout_doesNotLock() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.FIVE_MINUTES)
        lock.noteBackgrounded()
        clock += 299_000
        lock.noteForegrounded()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun backgroundLongerThanTimeout_locks() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.FIVE_MINUTES)
        lock.noteBackgrounded()
        clock += 301_000
        lock.noteForegrounded()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun immediately_locksOnAnyRoundTrip() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.IMMEDIATELY)
        lock.noteBackgrounded()
        lock.noteForegrounded()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun repeatedBackgrounded_keepsEarliestTimestamp() = runBlocking {
        // A stop/start blip on the way out must not restart the countdown from
        // a later moment than the user's actual departure.
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.FIVE_MINUTES)
        lock.noteBackgrounded()
        clock += 200_000
        lock.noteBackgrounded()
        clock += 101_000
        lock.noteForegrounded()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun foregrounded_withoutBackgrounded_doesNotLock() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.IMMEDIATELY)
        lock.noteForegrounded()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun idleTimeout_whileDisabled_doesNotLock() {
        val lock = makeController()
        lock.setTimeout(AppLockTimeout.IMMEDIATELY)
        lock.noteBackgrounded()
        clock += 3_600_000
        lock.noteForegrounded()
        assertFalse(lock.isLocked.value)
    }

    @Test
    fun idleTimeout_withNoAuthMethod_doesNotEngage() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        lock.setTimeout(AppLockTimeout.IMMEDIATELY)
        auth.method = null
        lock.noteBackgrounded()
        lock.noteForegrounded()
        assertFalse(lock.isLocked.value)
    }

    // MARK: - Unlocking

    @Test
    fun unlock_success_clearsLock() = runBlocking {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        lock.unlock()
        assertFalse(lock.isLocked.value)
        assertNull(lock.authError.value)
        assertEquals(AppLockController.UNLOCK_REASON, auth.reasons.single())
    }

    @Test
    fun unlock_declined_staysLockedWithError() = runBlocking {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        auth.result = Result.success(false)
        val lock = makeController()
        lock.unlock()
        assertTrue(lock.isLocked.value)
        assertNotNull(lock.authError.value)
    }

    @Test
    fun unlock_error_staysLockedWithPlatformMessage() = runBlocking {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        auth.result = Result.failure(IllegalStateException("Too many attempts. Try again later."))
        val lock = makeController()
        lock.unlock()
        assertTrue(lock.isLocked.value)
        assertEquals("Too many attempts. Try again later.", lock.authError.value)
    }

    @Test
    fun unlock_whenNotLocked_doesNotAuthenticate() = runBlocking {
        val lock = makeController()
        lock.unlock()
        assertEquals(0, auth.authenticateCalls)
    }

    @Test
    fun unlock_whileAlreadyPrompting_doesNotPromptTwice() = runBlocking {
        // The shield auto-prompts once on appearing; a user jabbing the Unlock
        // button underneath must not stack a second system prompt.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            store.setBoolean(AppLockController.ENABLED_KEY, true)
            auth.hold = true
            val lock = makeController()
            val first = scope.async { lock.unlock() }
            auth.gateReached.await()

            lock.unlock()
            assertEquals(1, auth.authenticateCalls)

            auth.release(true)
            first.await()
            assertFalse(lock.isLocked.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun unlockPromptsOwnBackgroundTrip_doesNotRelockAfterSuccess() = runBlocking {
        // Same churn as the confirm prompt, from the shield side: the credential
        // activity stops us mid-prompt, and no stale timestamp may survive to
        // relock the app the instant the prompt that just unlocked it dismisses.
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            store.setBoolean(AppLockController.ENABLED_KEY, true)
            auth.hold = true
            val lock = makeController()
            assertTrue(lock.isLocked.value)

            val unlocking = scope.async { lock.unlock() }
            auth.gateReached.await()
            assertTrue(lock.isAuthenticating.value)

            lock.noteBackgrounded()
            auth.release(true)
            unlocking.await()
            assertFalse(lock.isLocked.value)

            lock.setTimeout(AppLockTimeout.IMMEDIATELY)
            lock.noteForegrounded()
            assertFalse("the prompt's own churn must not relock", lock.isLocked.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun unlock_afterAuthMethodVanishes_standsDown() = runBlocking {
        // Locked while resident, screen lock removed in the background.
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        assertTrue(lock.isLocked.value)
        auth.method = null
        lock.unlock()
        assertFalse(lock.isLocked.value)
        assertEquals("nothing to evaluate against", 0, auth.authenticateCalls)
    }

    @Test
    fun backgroundedWhileAlreadyLocked_thenQuickReturn_staysLocked() {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        lock.noteBackgrounded()
        lock.noteForegrounded()
        assertTrue(lock.isLocked.value)
    }

    @Test
    fun unlockAttempt_stampsLastAuthEndedAt() = runBlocking {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        auth.result = Result.success(false)
        val lock = makeController()
        assertNull(lock.lastAuthEndedAt)
        lock.unlock()
        assertEquals(
            "hosts use this stamp to tell prompt churn from a real app switch",
            clock,
            lock.lastAuthEndedAt,
        )
    }

    // MARK: - Session boundaries

    @Test
    fun signOut_isRefusedWhileLocked() {
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        var signedOut = false
        assertFalse(lock.signOutIfUnlocked { signedOut = true })
        assertFalse(
            "signing out clears the lock — it must not be reachable from the shield",
            signedOut,
        )
    }

    @Test
    fun signOut_runsWhileUnlocked() {
        val lock = makeController()
        var signedOut = false
        assertTrue(lock.signOutIfUnlocked { signedOut = true })
        assertTrue(signedOut)
    }

    @Test
    fun resetForSignOut_optsTheNextAccountOut() = runBlocking {
        val lock = makeController()
        lock.setEnabled(true)
        assertTrue(lock.isEnabled.value)

        lock.resetForSignOut()

        assertFalse(lock.isEnabled.value)
        assertFalse(lock.isLocked.value)
        assertFalse(
            "a fresh account must not inherit a lock it never agreed to",
            store.getBoolean(AppLockController.ENABLED_KEY),
        )
    }

    @Test
    fun resetForSignOut_invalidatesAnInFlightEnablePrompt() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            auth.hold = true
            val lock = makeController()
            val enable = scope.async { lock.setEnabled(true) }
            auth.gateReached.await()

            lock.resetForSignOut()
            auth.release(true)
            enable.await()

            assertFalse(lock.isEnabled.value)
            assertFalse(store.getBoolean(AppLockController.ENABLED_KEY))
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun noteSignedIn_clearsAStaleShield() {
        // The user just proved account access interactively; the new session
        // must not open behind the previous one's shield.
        store.setBoolean(AppLockController.ENABLED_KEY, true)
        val lock = makeController()
        assertTrue(lock.isLocked.value)
        lock.noteSignedIn()
        assertFalse(lock.isLocked.value)
        assertEquals("an interactive sign-in IS the authentication", 0, auth.authenticateCalls)
    }

    @Test
    fun methodName_reflectsTheAuthenticator() {
        val lock = makeController()
        assertEquals("biometrics", lock.methodName)
        auth.method = null
        assertNull(lock.methodName)
    }
}
