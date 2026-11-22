package chat.matron.android.features.chat

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// `copyWithinLimit`: a picked item whose provider doesn't report a size is
/// cut off as soon as it passes the limit, so an oversized pick never fills
/// the cache; one at exactly the limit copies whole.
class CopyWithinLimitTest {
    @Test
    fun anInputAtTheLimitCopiesWhole() {
        val bytes = ByteArray(20_000) { (it % 7).toByte() }
        val out = ByteArrayOutputStream()
        assertTrue(copyWithinLimit(ByteArrayInputStream(bytes), out, maxBytes = 20_000))
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun anInputOneByteOverTheLimitIsCutOff() {
        val out = ByteArrayOutputStream()
        assertFalse(copyWithinLimit(ByteArrayInputStream(ByteArray(20_001)), out, maxBytes = 20_000))
        assertTrue(out.size() <= 20_000)
    }
}
