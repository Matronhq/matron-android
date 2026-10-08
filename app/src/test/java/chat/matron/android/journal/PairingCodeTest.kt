package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun normalizeUppercasesAndStripsSeparators() {
        assertEquals("KTNM3VQ8", PairingCode.normalize("ktnm-3vq8"))
        assertEquals("KTNM3VQ8", PairingCode.normalize(" ktnm 3vq8 "))
        assertEquals("KTNM3VQ8", PairingCode.normalize("KTNM3VQ8"))
        assertEquals("KTNM3VQ8", PairingCode.normalize("k-t*n_m3.vq8"))
        assertEquals("", PairingCode.normalize(""))
    }

    @Test
    fun displayInsertsHyphenAfterFourChars() {
        assertEquals("KTNM-3VQ8", PairingCode.display("ktnm3vq8"))
        assertEquals("KTN", PairingCode.display("ktn"))
        assertEquals("KTNM", PairingCode.display("ktnm"))
        assertEquals("KTNM-3", PairingCode.display("ktnm3"))
        assertEquals("", PairingCode.display(""))
    }

    @Test
    fun isPlausibleExactlyEightNormalizedChars() {
        assertTrue(PairingCode.isPlausible("ktnm-3vq8"))
        assertTrue(PairingCode.isPlausible("KTNM3VQ8"))
        assertFalse(PairingCode.isPlausible("ktnm-3vq"))
        assertFalse(PairingCode.isPlausible("ktnm-3vq88"))
        assertFalse(PairingCode.isPlausible(""))
    }
}
