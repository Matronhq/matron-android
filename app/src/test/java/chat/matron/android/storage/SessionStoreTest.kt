package chat.matron.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple KeychainStoreTests: the [SessionStore] round-trip
/// contract, exercised against the [InMemorySessionStore] double. The
/// production [EncryptedPrefsSessionStore] (AndroidKeyStore-backed) is verified
/// on-device — the AndroidKeyStore has no reliable JVM/Robolectric shim.
class SessionStoreTest {

    @Test
    fun setAndGetRoundTripsString() {
        val store = InMemorySessionStore()
        store.set("hello world", "test-key")
        assertEquals("hello world", store.get("test-key"))
    }

    @Test
    fun getReturnsNullWhenKeyMissing() {
        val store = InMemorySessionStore()
        assertNull(store.get("missing-key"))
    }

    @Test
    fun deleteRemovesValue() {
        val store = InMemorySessionStore()
        store.set("transient", "test-key")
        store.delete("test-key")
        assertNull(store.get("test-key"))
    }

    @Test
    fun setOverwritesExistingValue() {
        val store = InMemorySessionStore()
        store.set("first", "test-key")
        store.set("second", "test-key")
        assertEquals("second", store.get("test-key"))
    }
}
