package chat.matron.android.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/// Minimal interface for persisting the post-login session blob (and any other
/// small secret string). Mirrors matron-apple's `SessionStore` protocol.
///
/// - Production: [EncryptedPrefsSessionStore] wraps `EncryptedSharedPreferences`
///   (the Android analogue of the iOS Keychain — values are AES-encrypted at
///   rest with a key held in the AndroidKeyStore).
/// - Tests: [InMemorySessionStore] keeps values in a map, so the store's
///   contract can be exercised without the Android keystore.
interface SessionStore {
    fun set(value: String, key: String)
    fun get(key: String): String?
    fun delete(key: String)
}

/// `EncryptedSharedPreferences`-backed [SessionStore]. This is the Keychain
/// analogue on Android: entries are encrypted with a `MasterKey` bound to the
/// hardware-backed AndroidKeyStore, so a plain-file read can't recover the
/// token. Thread-safe: `SharedPreferences` serialises its own access.
class EncryptedPrefsSessionStore private constructor(
    private val prefs: android.content.SharedPreferences,
) : SessionStore {

    override fun set(value: String, key: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        /// Opens (or creates) the encrypted preferences file named [fileName].
        fun create(context: Context, fileName: String = "matron-session"): EncryptedPrefsSessionStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedPrefsSessionStore(prefs)
        }
    }
}

/// In-memory [SessionStore] double for tests. Not persisted; not thread-safe
/// beyond the `synchronized` guard around the backing map.
class InMemorySessionStore : SessionStore {
    private val storage = mutableMapOf<String, String>()
    private val lock = Any()

    override fun set(value: String, key: String) = synchronized(lock) { storage[key] = value; Unit }
    override fun get(key: String): String? = synchronized(lock) { storage[key] }
    override fun delete(key: String) = synchronized(lock) { storage.remove(key); Unit }
}
