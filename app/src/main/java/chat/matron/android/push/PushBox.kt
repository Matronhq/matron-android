package chat.matron.android.push

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305
import com.google.crypto.tink.subtle.X25519
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/// Opens the sealed `box` a push carries. The journal seals with HPKE
/// (RFC 9180) in base mode: DHKEM(X25519, HKDF-SHA256), HKDF-SHA256,
/// ChaCha20-Poly1305, `info` = `matron-push-v1`, empty AAD. The box is the
/// 32-byte encapsulated key followed by the ciphertext and tag, as unpadded
/// base64url. Neither Google nor any relay holds a key that opens it.
///
/// Port of matron-apple's `PushBox`. CryptoKit has HPKE built in; here the
/// recipient half is written out over Tink's X25519 and ChaCha20-Poly1305
/// (both pure Java, so every API level from 26 gets the same code), exactly
/// as the journal writes out the sender half in `src/push-box.js`.
object PushBox {
    private val INFO = "matron-push-v1".toByteArray()
    private const val ENCAPSULATED_KEY_BYTES = 32
    private const val TAG_BYTES = 16
    /// The journal never sends more than this (1536 padded bytes of JSON).
    const val MAX_CHARACTERS = 2112

    private const val KEM_ID = 0x0020
    private const val KDF_ID = 0x0001
    private const val AEAD_ID = 0x0003
    private val VERSION = "HPKE-v1".toByteArray()
    private val KEM_SUITE = "KEM".toByteArray() + i2osp(KEM_ID, 2)
    private val HPKE_SUITE = "HPKE".toByteArray() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)

    /// The plaintext, or null when the box is malformed, was sealed for
    /// another key, or has been altered.
    fun open(box: String, privateKey: ByteArray): ByteArray? {
        if (box.length > MAX_CHARACTERS || privateKey.size != 32) return null
        val raw = decodeBase64Url(box) ?: return null
        if (raw.size <= ENCAPSULATED_KEY_BYTES + TAG_BYTES) return null
        return runCatching {
            val enc = raw.copyOfRange(0, ENCAPSULATED_KEY_BYTES)
            val recipientPublic = X25519.publicFromPrivate(privateKey)
            val dh = X25519.computeSharedSecret(privateKey, enc)
            val eaePrk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
            val sharedSecret = labeledExpand(KEM_SUITE, eaePrk, "shared_secret", enc + recipientPublic, 32)
            // Key schedule, mode_base: no PSK.
            val pskIdHash = labeledExtract(HPKE_SUITE, ByteArray(0), "psk_id_hash", ByteArray(0))
            val infoHash = labeledExtract(HPKE_SUITE, ByteArray(0), "info_hash", INFO)
            val context = byteArrayOf(0) + pskIdHash + infoHash
            val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", ByteArray(0))
            val key = labeledExpand(HPKE_SUITE, secret, "key", context, 32)
            val nonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12)
            // One message per context: sequence number 0, so the nonce is the base nonce.
            InsecureNonceChaCha20Poly1305(key).decrypt(nonce, raw.copyOfRange(ENCAPSULATED_KEY_BYTES, raw.size), ByteArray(0))
        }.getOrNull()
    }

    /// Opens and decodes in one step.
    fun content(box: String, privateKey: ByteArray): PushContent? =
        open(box, privateKey)?.let(PushContent::parse)

    private fun i2osp(value: Int, length: Int): ByteArray =
        ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }

    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // RFC 5869: an empty salt is HashLen zero bytes (which HMAC treats
        // the same as an empty key); SecretKeySpec refuses an empty one.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hmac(salt, VERSION, suite, label.toByteArray(), ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeledInfo = i2osp(length, 2) + VERSION + suite + label.toByteArray() + info
        val out = ByteArrayOutputStream()
        var previous = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            previous = hmac(prk, previous, labeledInfo, byteArrayOf(counter.toByte()))
            out.write(previous)
            counter += 1
        }
        return out.toByteArray().copyOf(length)
    }

    /// Unpadded base64url, the encoding keys and boxes travel in. Null for
    /// anything else, padding included.
    fun decodeBase64Url(text: String): ByteArray? {
        if (!text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) return null
        return runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
    }

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
