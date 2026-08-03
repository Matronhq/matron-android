package chat.matron.android.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import chat.matron.android.viewmodels.BiometricAuthenticating
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/// The platform half of the app lock: `androidx.biometric`'s [BiometricPrompt].
/// Ports the Swift `LocalBiometricAuthenticator` (matron-apple #83), which wrapped
/// `LAContext.evaluatePolicy(.deviceOwnerAuthentication)`.
///
/// `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` is the deliberate analogue of
/// `.deviceOwnerAuthentication`: biometrics with a PIN/pattern/password fallback,
/// so one failed face scan can never strand the user behind their own lock. Weak
/// rather than strong for two reasons — this gates a UI shield, not a
/// cryptographic key (there is no `CryptoObject` here), and
/// `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is explicitly unsupported on API
/// 28–29, which this app still targets (minSdk 26).
///
/// Bound to a [FragmentActivity] because `BiometricPrompt` needs one to host its
/// internal fragment. The instance is activity-scoped, exactly like the
/// controller that owns it.
class AndroidBiometricAuthenticator(
    private val activity: FragmentActivity,
) : BiometricAuthenticating {

    /// Android exposes no public API naming the enrolled sensor (no "Face ID" /
    /// "Touch ID" equivalent), so the two classes of method are probed
    /// separately and named generically. Probing them separately also sidesteps
    /// `canAuthenticate`'s awkward behaviour with combined constants on older
    /// API levels — each single-authenticator query is well defined everywhere.
    ///
    /// `null` (nothing enrolled, no device credential) is what makes the
    /// controller stand its lock down rather than strand the user.
    override fun availableMethodName(): String? {
        val manager = BiometricManager.from(activity)
        return when {
            manager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS ->
                "biometrics"
            manager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS ->
                "your screen lock"
            else -> null
        }
    }

    /// Shows one prompt and suspends until the system reports an outcome.
    ///
    /// Runs on the main dispatcher because `BiometricPrompt.authenticate` must
    /// be called from the main thread. The `AtomicBoolean` is not paranoia: the
    /// callback contract permits an error to arrive after a success (a
    /// cancellation racing the sensor), and resuming a continuation twice
    /// crashes the process rather than failing politely.
    override suspend fun authenticate(reason: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val settled = AtomicBoolean(false)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (settled.compareAndSet(false, true)) continuation.resume(true)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // The system's own wording is the useful one here
                        // ("Too many attempts. Try again later."), so it is
                        // surfaced verbatim rather than flattened to a generic
                        // failure. Includes user cancellation, which is an
                        // honest thing to show under the Unlock button.
                        if (settled.compareAndSet(false, true)) {
                            continuation.resumeWithException(
                                BiometricAuthenticationException(errorCode, errString.toString())
                            )
                        }
                    }

                    // onAuthenticationFailed is a single rejected scan, NOT the
                    // end of the attempt — the prompt stays up and the user can
                    // try again. Resuming here would tear down a live prompt.
                }

                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    callback,
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Matron")
                    .setSubtitle(reason)
                    // No negative-button text: setting one alongside
                    // DEVICE_CREDENTIAL throws, because the credential fallback
                    // IS the negative action.
                    .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                    .build()

                continuation.invokeOnCancellation {
                    // Best effort: dismisses the prompt if the caller's scope
                    // dies (activity destroyed) while it is on screen.
                    runCatching { prompt.cancelAuthentication() }
                }
                prompt.authenticate(info)
            }
        }
}

/// A completed-with-error outcome from [BiometricPrompt]. [message] is the
/// system's user-facing string; the controller shows it as-is.
class BiometricAuthenticationException(
    val errorCode: Int,
    message: String,
) : Exception(message)
