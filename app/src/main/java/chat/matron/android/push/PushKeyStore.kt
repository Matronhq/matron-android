package chat.matron.android.push

import chat.matron.android.storage.SessionStore
import com.google.crypto.tink.subtle.X25519

/// This device's key for sealed notification content. The app makes it and
/// registers the public half with the journal; the messaging service reads
/// the private half to open what arrives. Port of matron-apple's
/// `PushKeyStore`.
///
/// The key lives in its own EncryptedSharedPreferences file, encrypted under
/// an AndroidKeyStore master key that never leaves this device, so a copy
/// of the file restored elsewhere opens nothing. It is readable once the
/// phone has been unlocked after a restart: a notification has to be opened
/// while the phone is locked.
class PushKeyStore(private val store: SessionStore) {

    /// The raw 32-byte X25519 private key, if this device has one.
    fun privateKey(): ByteArray? =
        runCatching { store.get(ACCOUNT) }.getOrNull()
            ?.let(PushBox::decodeBase64Url)
            ?.takeIf { it.size == 32 }

    /// The public half to register (unpadded base64url), making the key
    /// first if there is none. Null when the store cannot hold it: the
    /// device then registers without a key and its notifications keep their
    /// fixed text.
    fun publicKeyForRegistration(): String? {
        privateKey()?.let { return publicOf(it) }
        val fresh = X25519.generatePrivateKey()
        if (runCatching { store.set(PushBox.encodeBase64Url(fresh), ACCOUNT) }.isFailure) return null
        // Read back: what gets registered must be what the service will find.
        return privateKey()?.let(::publicOf)
    }

    /// Sign-out: the next session registers a new key.
    fun delete() {
        runCatching { store.delete(ACCOUNT) }
    }

    private fun publicOf(privateKey: ByteArray): String? =
        runCatching { PushBox.encodeBase64Url(X25519.publicFromPrivate(privateKey)) }.getOrNull()

    companion object {
        const val FILE = "matron-push"
        private const val ACCOUNT = "notification-content-key"
    }
}
