package chat.matron.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// Ported from matron-apple KeychainProbeTests. Exercises every
/// [KeychainProbeError] branch with in-memory [SessionStore] doubles.
class KeychainProbeTest {

    private class FakeStoreError : Exception("injected")

    private class ThrowingSessionStore(private val mode: Mode) : SessionStore {
        enum class Mode { ON_SET, ON_GET, RETURNS_WRONG_VALUE, ON_DELETE }

        private val storage = mutableMapOf<String, String>()
        var didAttemptDelete = false
            private set

        override fun set(value: String, key: String) {
            if (mode == Mode.ON_SET) throw FakeStoreError()
            storage[key] = value
        }

        override fun get(key: String): String? = when (mode) {
            Mode.ON_GET -> throw FakeStoreError()
            Mode.RETURNS_WRONG_VALUE -> "wrong-value"
            else -> storage[key]
        }

        override fun delete(key: String) {
            didAttemptDelete = true
            if (mode == Mode.ON_DELETE) throw FakeStoreError()
            storage.remove(key)
        }
    }

    @Test
    fun runSucceedsAgainstInMemoryStore() {
        val store = InMemorySessionStore()
        KeychainProbe.run(store)
        assertNull("probe must delete its entry on success", store.get(KeychainProbe.probeKey))
    }

    @Test
    fun runThrowsSetFailedWhenStoreSetFails() {
        val store = ThrowingSessionStore(ThrowingSessionStore.Mode.ON_SET)
        try {
            KeychainProbe.run(store)
            fail("expected SetFailed")
        } catch (e: KeychainProbeError.SetFailed) {
            assertTrue(e.underlying is FakeStoreError)
        }
    }

    @Test
    fun runThrowsGetFailedWhenStoreGetFails() {
        val store = ThrowingSessionStore(ThrowingSessionStore.Mode.ON_GET)
        try {
            KeychainProbe.run(store)
            fail("expected GetFailed")
        } catch (e: KeychainProbeError.GetFailed) {
            // expected
        }
        assertTrue("probe must attempt cleanup even when get fails", store.didAttemptDelete)
    }

    @Test
    fun runThrowsRoundTripMismatchWhenGetReturnsWrongValue() {
        val store = ThrowingSessionStore(ThrowingSessionStore.Mode.RETURNS_WRONG_VALUE)
        try {
            KeychainProbe.run(store)
            fail("expected RoundTripMismatch")
        } catch (e: KeychainProbeError.RoundTripMismatch) {
            assertEquals("wrong-value", e.got)
        }
    }

    @Test
    fun probeErrorMessagesAreNonEmpty() {
        assertTrue(KeychainProbeError.SetFailed(FakeStoreError()).message!!.isNotEmpty())
        assertTrue(KeychainProbeError.GetFailed(FakeStoreError()).message!!.isNotEmpty())
        assertTrue(KeychainProbeError.RoundTripMismatch("a", "b").message!!.isNotEmpty())
        assertTrue(KeychainProbeError.DeleteFailed(FakeStoreError()).message!!.isNotEmpty())
    }
}
