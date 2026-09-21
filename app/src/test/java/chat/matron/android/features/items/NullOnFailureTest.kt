package chat.matron.android.features.items

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/// Pins the item screen's failure rule for its two suspending loads (image
/// bytes, attachment cache write): a real failure is a `null` the caller turns
/// into a banner, but cancellation goes through untouched — a plain
/// `runCatching` would absorb it and let a cancelled load keep writing to
/// screen state and report itself to the reader as a failure.
class NullOnFailureTest {
    @Test
    fun aRealFailureBecomesNull() = runBlocking {
        assertEquals("ok", nullOnFailure { "ok" })
        assertNull(nullOnFailure { throw IllegalStateException("boom") })
    }

    @Test
    fun cancellationIsRethrownNotSwallowed() = runBlocking {
        try {
            nullOnFailure { throw CancellationException("cancelled") }
            fail("cancellation must propagate")
        } catch (cancel: CancellationException) {
            assertEquals("cancelled", cancel.message)
        }
    }
}
