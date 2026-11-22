package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRevocationMonitorTest {
    private var clock = 0L
    private var fired = 0

    private fun monitor(threshold: Int = 3, spanMillis: Long = 10_000) =
        CredentialRevocationMonitor(rejectionThreshold = threshold, minimumSpanMillis = spanMillis, now = { clock }) {
            fired += 1
        }

    @Test
    fun refusedHello_firesAtOnce() {
        val m = monitor()
        m.handle(CredentialSignal.Rejected)
        assertEquals(1, fired)
        assertTrue(m.hasFired)
    }

    @Test
    fun firesAtMostOnce() {
        val m = monitor()
        m.handle(CredentialSignal.Rejected)
        m.handle(CredentialSignal.Rejected)
        repeat(5) { clock += 10_000; m.noteResponse(401) }
        assertEquals(1, fired)
    }

    @Test
    fun acceptedHello_neverFires() {
        val m = monitor()
        m.handle(CredentialSignal.Accepted)
        assertEquals(0, fired)
        assertFalse(m.hasFired)
    }

    @Test
    fun accountLock_isNeverARevoke() {
        val m = monitor()
        repeat(10) { m.handle(CredentialSignal.AccountLocked) }
        assertEquals(0, fired)
    }

    @Test
    fun oneRest401_isNotEnough() {
        val m = monitor()
        m.noteResponse(401)
        assertEquals(0, fired)
    }

    @Test
    fun rest401s_inAQuickBurst_doNotFire() {
        val m = monitor()
        repeat(20) { clock += 100; m.noteResponse(401) }
        assertEquals("20 401s in 2 s are under the minimum span", 0, fired)
    }

    @Test
    fun rest401s_overTheMinimumSpan_fire() {
        val m = monitor()
        m.noteResponse(401)
        clock += 5_000
        m.noteResponse(401)
        assertEquals(0, fired)
        clock += 5_000
        m.noteResponse(401)
        assertEquals(1, fired)
    }

    @Test
    fun spreadOut401s_belowTheThreshold_doNotFire() {
        val m = monitor()
        m.noteResponse(401)
        clock += 60_000
        m.noteResponse(401)
        assertEquals("two 401s are under the threshold however far apart", 0, fired)
    }

    @Test
    fun aBurstThenOneMoreAfterTheSpan_fires() {
        val m = monitor()
        repeat(3) { m.noteResponse(401) }
        assertEquals(0, fired)
        clock += 10_000
        m.noteResponse(401)
        assertEquals(1, fired)
    }

    @Test
    fun anAnswerOnlyASignedInDeviceGets_resetsTheCount() {
        for (status in listOf(200, 204, 403, 404, 409, 423)) {
            fired = 0
            clock = 0
            val m = monitor()
            m.noteResponse(401)
            clock += 5_000
            m.noteResponse(401)
            m.noteResponse(status)
            clock += 5_000
            m.noteResponse(401)
            clock += 1
            m.noteResponse(401)
            assertEquals("status $status must reset the run of 401s", 0, fired)
        }
    }

    @Test
    fun acceptedHello_resetsTheCount() {
        val m = monitor()
        m.noteResponse(401)
        clock += 5_000
        m.noteResponse(401)
        m.handle(CredentialSignal.Accepted)
        clock += 5_000
        m.noteResponse(401)
        assertEquals(0, fired)
    }

    @Test
    fun throttlingAndServerErrors_leaveTheCountAlone() {
        val m = monitor()
        m.noteResponse(401)
        clock += 4_000
        m.noteResponse(429)
        m.noteResponse(500)
        m.noteResponse(502)
        m.noteResponse(503)
        m.noteResponse(401)
        assertEquals(0, fired)
        clock += 6_000
        m.noteResponse(401)
        assertEquals("the run of 401s survives 429 and 5xx", 1, fired)
    }

    @Test
    fun cancel_disarms() {
        val m = monitor()
        m.cancel()
        assertFalse(m.isArmed)
        m.handle(CredentialSignal.Rejected)
        repeat(5) { clock += 10_000; m.noteResponse(401) }
        assertEquals(0, fired)
        assertFalse(m.hasFired)
    }

    @Test
    fun aThresholdBelowOne_actsAsOne() {
        val m = monitor(threshold = 0, spanMillis = 0)
        m.noteResponse(401)
        assertEquals(1, fired)
    }

    @Test
    fun concurrentReports_fireOnce() {
        val m = CredentialRevocationMonitor(rejectionThreshold = 1, minimumSpanMillis = 0, now = { 0 }) {
            synchronized(this) { fired += 1 }
        }
        val threads = List(8) { Thread { repeat(200) { m.noteResponse(401); m.handle(CredentialSignal.Rejected) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, fired)
    }
}
