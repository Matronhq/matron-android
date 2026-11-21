package chat.matron.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.approvals.ApprovalKeyEncoding
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalSigningKey
import chat.matron.android.journal.approvals.ApprovalSubjects
import chat.matron.android.viewmodels.KeyValueStore
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/// Whether this device can hold an approval key, and what unlocks it.
sealed interface ApprovalKeyAvailability {
    /// A strong (class 3) biometric is enrolled. [method] names it in
    /// sentences: "fingerprint", or "biometrics" when the device may use its
    /// face too.
    data class Available(override val method: String) : ApprovalKeyAvailability

    /// No strong biometric set up. [advice] says what to do instead.
    data class Unavailable(val advice: String) : ApprovalKeyAvailability

    val method: String? get() = null
    val isAvailable: Boolean get() = this is Available
}

/// The device's key as it is kept between launches: where the private half
/// lives and what is needed to recognise it. Nothing here is secret: the
/// private half never leaves the Android Keystore (a software key's [blob]
/// exists only in tests).
@Serializable
data class StoredApprovalKey(
    val kind: Kind,
    /// The Android Keystore alias of a [Kind.KEYSTORE] key.
    val alias: String = "",
    /// A [Kind.SOFTWARE] key's PKCS#8 private key, base64url. Tests only.
    val blob: String = "",
    /// The 65-byte uncompressed public point, base64url.
    val publicKey: String,
    val createdAt: Long,
) {
    @Serializable
    enum class Kind { KEYSTORE, SOFTWARE }

    val publicKeyBytes: ByteArray get() = ApprovalKeyEncoding.data(publicKey) ?: ByteArray(0)
    val keyID: String get() = ApprovalKeyEncoding.keyID(publicKeyBytes)
}

/// Where a device keeps the record of its approval key. One per journal
/// account: the key belongs to the account, not to a sign-in, so signing out
/// leaves it and signing in again finds it. Only "Remove" deletes it.
class ApprovalKeyStore(private val store: KeyValueStore, host: String, user: String) {
    private val name = "approval-key-" + ApprovalSubjects.sha256(host.lowercase() + "\n" + user).take(32)

    fun load(): StoredApprovalKey? = store.getString(name)?.let {
        runCatching { MatronJson.decodeFromString(StoredApprovalKey.serializer(), it) }.getOrNull()
    }

    fun save(key: StoredApprovalKey) {
        store.setString(name, MatronJson.encodeToString(StoredApprovalKey.serializer(), key))
    }

    fun delete() {
        store.remove(name)
    }

    /// The journal's name as statements carry it (`GET /approval-keys`'s
    /// `journal`), kept for a cold start while the account is locked: the
    /// keys cannot be read then, and the server address may not be the name.
    var lastJournal: String?
        get() = store.getString("$name-journal")
        set(value) { if (value == null) store.remove("$name-journal") else store.setString("$name-journal", value) }
}

/// Makes and loads the device's key. A seam so the approval centre's tests
/// run without a Keystore.
interface ApprovalKeyProviding {
    val availability: ApprovalKeyAvailability

    /// A new key, in the Keystore, behind a strong biometric.
    fun create(): StoredApprovalKey

    /// The signing key for a stored one; throws
    /// [ApprovalKeyFailure.Invalidated] when the device's biometrics changed
    /// since it was made (or the key is gone).
    fun signingKey(stored: StoredApprovalKey): ApprovalSigningKey

    /// Destroys the private half (the record is the store's to forget).
    fun destroy(stored: StoredApprovalKey) {}
}

/// The activity the biometric prompt is shown over. `BiometricPrompt` needs a
/// [FragmentActivity]; the app has one, which registers itself here while it
/// exists.
object BiometricPromptHost {
    @Volatile
    private var current: WeakReference<FragmentActivity>? = null

    fun attach(activity: FragmentActivity) {
        current = WeakReference(activity)
    }

    fun detach(activity: FragmentActivity) {
        if (current?.get() === activity) current = null
    }

    val activity: FragmentActivity? get() = current?.get()
}

/// The real thing: ECDSA P-256 in the Android Keystore (StrongBox when the
/// device has one), usable for one signature per strong-biometric check.
/// There is no screen-lock fallback, and enrolling a new fingerprint or face
/// destroys the key, as "biometry, current set" does on iOS.
class KeystoreApprovalKeyProvider(private val context: Context) : ApprovalKeyProviding {
    override val availability: ApprovalKeyAvailability
        get() {
            val manager = BiometricManager.from(context)
            if (manager.canAuthenticate(BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
                return ApprovalKeyAvailability.Unavailable(
                    "An approval key needs a fingerprint (or a face unlock the system rates as strong). " +
                        "Set one up in the device's settings first.",
                )
            }
            val pm = context.packageManager
            val other = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_FACE)) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_IRIS))
            val fingerprint = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            return ApprovalKeyAvailability.Available(if (fingerprint && !other) "fingerprint" else "biometrics")
        }

    override fun create(): StoredApprovalKey {
        if (!availability.isAvailable) throw ApprovalKeyFailure.Unavailable
        val alias = "matron-approval-" + UUID.randomUUID().toString()
        val made = runCatching { generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) }
            .recoverCatching {
                // No StrongBox, or it refused the spec (some devices wrap that
                // in a ProviderException): the TEE-backed keystore.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) generate(alias, strongBox = false) else throw it
            }
            .getOrElse { throw ApprovalKeyFailure.Other("Couldn't create the key: ${it.message ?: it}") }
        return StoredApprovalKey(
            kind = StoredApprovalKey.Kind.KEYSTORE, alias = alias,
            publicKey = ApprovalKeyEncoding.base64URL(made), createdAt = System.currentTimeMillis(),
        )
    }

    private fun generate(alias: String, strongBox: Boolean): ByteArray {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Enrolling a new fingerprint or face destroys the key.
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Every signature needs its own strong-biometric check; no
            // screen-lock fallback.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(builder.build())
        val pair = generator.generateKeyPair()
        return uncompressedPoint(pair.public as ECPublicKey)
    }

    override fun signingKey(stored: StoredApprovalKey): ApprovalSigningKey {
        // A shipped app never signs with a key that asks for no biometric check.
        if (stored.kind != StoredApprovalKey.Kind.KEYSTORE) throw ApprovalKeyFailure.Invalidated
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = runCatching { keyStore.getKey(stored.alias, null) as? PrivateKey }.getOrNull()
            ?: throw ApprovalKeyFailure.Invalidated
        // The probe: a key destroyed by a biometric change refuses initSign.
        try {
            Signature.getInstance(SIGNATURE).initSign(privateKey)
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw ApprovalKeyFailure.Invalidated
        }
        return KeystoreApprovalKey(privateKey, stored.publicKeyBytes)
    }

    override fun destroy(stored: StoredApprovalKey) {
        if (stored.kind != StoredApprovalKey.Kind.KEYSTORE) return
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(stored.alias) }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

private const val SIGNATURE = "SHA256withECDSA"

/// The 65-byte uncompressed point of a P-256 public key: `0x04 ‖ X ‖ Y`, each
/// coordinate left-padded to 32 bytes.
fun uncompressedPoint(key: ECPublicKey): ByteArray {
    fun fixed(n: BigInteger): ByteArray {
        val raw = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(raw.size <= 32) { "not a P-256 coordinate" }
        return ByteArray(32 - raw.size) + raw
    }
    return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
}

/// A key in the Android Keystore. Signing shows the system's biometric prompt
/// with the signature as its crypto object: the Keystore releases one
/// signature for one successful check, and the private half never leaves it.
class KeystoreApprovalKey internal constructor(
    private val privateKey: PrivateKey,
    override val publicKey: ByteArray,
) : ApprovalSigningKey {
    override suspend fun signature(data: ByteArray, reason: String): ByteArray = withContext(Dispatchers.Main.immediate) {
        val activity = BiometricPromptHost.activity ?: throw ApprovalKeyFailure.Other("The app isn't on screen.")
        val signature = Signature.getInstance(SIGNATURE)
        try {
            signature.initSign(privateKey)
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw ApprovalKeyFailure.Invalidated
        }
        val authorised = suspendCancellableCoroutine { continuation ->
            val settled = AtomicBoolean(false)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.signature
                    if (!settled.compareAndSet(false, true)) return
                    if (unlocked == null) {
                        continuation.resumeWithException(ApprovalKeyFailure.Other("The system did not unlock the key."))
                    } else {
                        continuation.resume(unlocked)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (settled.compareAndSet(false, true)) continuation.resumeWithException(failure(errorCode, errString.toString()))
                }
                // onAuthenticationFailed: one rejected scan; the prompt stays up.
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Approve with your key")
                .setSubtitle(reason.take(200))
                .setNegativeButtonText("Cancel")
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(true)
                .build()
            continuation.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
            prompt.authenticate(info, BiometricPrompt.CryptoObject(signature))
        }
        try {
            authorised.update(data)
            authorised.sign()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw ApprovalKeyFailure.Invalidated
        } catch (e: Exception) {
            throw ApprovalKeyFailure.Other("Couldn't sign: ${e.message ?: e}")
        }
    }

    companion object {
        fun failure(code: Int, text: String): ApprovalKeyFailure = when (code) {
            BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED ->
                ApprovalKeyFailure.Cancelled
            BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT, BiometricPrompt.ERROR_TIMEOUT ->
                ApprovalKeyFailure.BiometryFailed(text)
            BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                ApprovalKeyFailure.Unavailable
            else -> ApprovalKeyFailure.Other(text.ifEmpty { "The biometric check failed ($code)." })
        }
    }
}

/// A P-256 key held in memory. For unit tests only: it asks for no biometric
/// check, so nothing the app ships ever enrols one.
class SoftwareApprovalKey private constructor(private val privateKey: PrivateKey, override val publicKey: ByteArray) :
    ApprovalSigningKey {
    override suspend fun signature(data: ByteArray, reason: String): ByteArray =
        Signature.getInstance(SIGNATURE).run { initSign(privateKey); update(data); sign() }

    val pkcs8: ByteArray get() = privateKey.encoded

    companion object {
        fun generate(): SoftwareApprovalKey {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return SoftwareApprovalKey(pair.private, uncompressedPoint(pair.public as ECPublicKey))
        }

        fun fromPkcs8(pkcs8: ByteArray, publicKey: ByteArray): SoftwareApprovalKey =
            SoftwareApprovalKey(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)), publicKey)
    }
}

/// Makes software keys: unit tests only. Never the provider of the app.
class SoftwareKeyProvider(
    override val availability: ApprovalKeyAvailability = ApprovalKeyAvailability.Available("fingerprint"),
) : ApprovalKeyProviding {
    /// Keys whose [signingKey] throws, as a biometric change would.
    val invalidated = mutableSetOf<String>()

    override fun create(): StoredApprovalKey {
        if (!availability.isAvailable) throw ApprovalKeyFailure.Unavailable
        val key = SoftwareApprovalKey.generate()
        return StoredApprovalKey(
            kind = StoredApprovalKey.Kind.SOFTWARE, blob = ApprovalKeyEncoding.base64URL(key.pkcs8),
            publicKey = ApprovalKeyEncoding.base64URL(key.publicKey), createdAt = System.currentTimeMillis(),
        )
    }

    override fun signingKey(stored: StoredApprovalKey): ApprovalSigningKey {
        if (stored.keyID in invalidated) throw ApprovalKeyFailure.Invalidated
        return SoftwareApprovalKey.fromPkcs8(ApprovalKeyEncoding.data(stored.blob) ?: ByteArray(0), stored.publicKeyBytes)
    }
}
