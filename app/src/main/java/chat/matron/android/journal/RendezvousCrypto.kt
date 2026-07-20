package chat.matron.android.journal

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/// base64url (RFC 4648 §5), no padding — the wire encoding for the QR key and
/// the offer box. Uses java.util.Base64 (NOT android.util.Base64, which is a
/// no-op stub in pure-JVM unit tests); java.util.Base64 is real on Android
/// API 26+. Apple's Base64URL agrees byte-for-byte.
object Base64URL {
    fun encode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    fun decode(s: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
}

/// End-to-end encryption for the rendezvous offer (rendezvous-offer-encryption
/// spec). The signed-out desktop generates the key and shows it in the QR; the
/// scanning phone seals {server, code} under it; the desktop opens locally.
/// The relay only ever holds the opaque box. AES-256-GCM, random 96-bit nonce,
/// framing nonce(12) ‖ ciphertext ‖ tag(16) — GCM's doFinal appends the tag.
object RendezvousCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun generateKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun seal(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext) // doFinal returns ciphertext ‖ tag
    }

    fun open(box: ByteArray, key: ByteArray): ByteArray {
        require(box.size >= NONCE_BYTES + TAG_BYTES) { "box too short" }
        val nonce = box.copyOfRange(0, NONCE_BYTES)
        val ciphertextAndTag = box.copyOfRange(NONCE_BYTES, box.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return cipher.doFinal(ciphertextAndTag) // throws AEADBadTagException on auth failure
    }
}
