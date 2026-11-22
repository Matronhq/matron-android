package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentLimitsTest {
    @Test
    fun theLimitIs100MBInDecimalBytes() {
        assertEquals(100_000_000L, AttachmentLimits.MAX_BYTES)
    }

    @Test
    fun exactly100MBIsAcceptedAndOneByteMoreIsNot() {
        assertFalse(AttachmentLimits.isOversize(100_000_000L))
        assertTrue(AttachmentLimits.isOversize(100_000_001L))
    }

    @Test
    fun theRefusalNamesTheFile() {
        assertEquals(
            "clip.mov is larger than 100 MB and wasn't attached.",
            AttachmentLimits.oversizeMessage("clip.mov"),
        )
    }
}
