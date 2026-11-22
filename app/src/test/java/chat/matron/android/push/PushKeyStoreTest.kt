package chat.matron.android.push

import chat.matron.android.storage.InMemorySessionStore
import chat.matron.android.storage.SessionStore
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushKeyStoreTest {
    @Test
    fun makesOneKeyAndRegistersItsPublicHalf() {
        val keys = PushKeyStore(InMemorySessionStore())
        assertNull(keys.privateKey())
        val public = keys.publicKeyForRegistration()!!
        assertEquals(43, public.length)
        assertEquals(public, PushBox.encodeBase64Url(X25519.publicFromPrivate(keys.privateKey()!!)))
        assertEquals(public, keys.publicKeyForRegistration())
    }

    @Test
    fun signOutMeansANewKeyNextTime() {
        val keys = PushKeyStore(InMemorySessionStore())
        val first = keys.publicKeyForRegistration()
        keys.delete()
        assertNull(keys.privateKey())
        assertNotEquals(first, keys.publicKeyForRegistration())
    }

    @Test
    fun concurrentRegistrationsAgreeOnOneKey() {
        val keys = PushKeyStore(InMemorySessionStore())
        val results = java.util.concurrent.ConcurrentLinkedQueue<String?>()
        val threads = (1..8).map { Thread { results.add(keys.publicKeyForRegistration()) } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertEquals(1, results.toSet().size)
        assertEquals(results.first(), PushBox.encodeBase64Url(X25519.publicFromPrivate(keys.privateKey()!!)))
    }

    @Test
    fun aRegistrationNoLongerAllowedMakesNoKey() {
        val keys = PushKeyStore(InMemorySessionStore())
        assertNull(keys.publicKeyForRegistration { false })
        assertNull(keys.privateKey())
    }

    @Test
    fun aStoreThatCannotHoldTheKeyRegistersNone() {
        val broken = object : SessionStore {
            override fun set(value: String, key: String) = throw IllegalStateException("keystore unavailable")
            override fun get(key: String): String? = null
            override fun delete(key: String) = Unit
        }
        assertNull(PushKeyStore(broken).publicKeyForRegistration())
    }
}
