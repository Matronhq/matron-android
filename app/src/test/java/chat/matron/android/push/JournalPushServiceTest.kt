package chat.matron.android.push

import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins [JournalPushService]'s APNs-token hex encoding, mirroring
/// matron-apple's `JournalPushServiceTests`. The register/unregister/permission
/// paths round-trip through the network / notification runtime and aren't
/// unit-testable headlessly, so only the pure encoding helper is pinned.
class JournalPushServiceTest {
    @Test
    fun hexStringLowercasePadded() {
        val bytes = byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte(), 0x01)
        assertEquals("abcdef01", JournalPushService.hexString(bytes))
    }

    @Test
    fun hexStringPadsSingleDigitBytes() {
        val bytes = byteArrayOf(0x00, 0x05, 0x0a, 0xff.toByte())
        assertEquals("00050aff", JournalPushService.hexString(bytes))
    }

    @Test
    fun hexStringEmptyDataReturnsEmpty() {
        assertEquals("", JournalPushService.hexString(byteArrayOf()))
    }
}
