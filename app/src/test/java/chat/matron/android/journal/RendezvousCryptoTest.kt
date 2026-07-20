package chat.matron.android.journal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousCryptoTest {
    // The cross-language interop vector — the SAME literals are asserted in
    // the Apple suite so a Swift-sealed box opens under Kotlin and vice versa.
    // AES-256-GCM, framing nonce(12)‖ciphertext‖tag(16), base64url.
    private val vectorKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val vectorBox =
        "oKGio6SlpqeoqaqrnToPSDe9Z81AX6W7cw6wrUqDdnP61jZC-XZH6w_HEC-xGSrdgwAwUjv5JvIrSLDNcjZwf1rpOAMFFZLM4JJwtKZY9E-Fmmfg"
    private val vectorPlaintext = """{"server":"https://chat.example.com","code":"2345-6789"}"""

    @Test
    fun base64URL_roundTrips_andStripsPadding() {
        val raw = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xff.toByte(), 0xfe.toByte())
        val encoded = Base64URL.encode(raw)
        assertFalse(encoded.contains("="))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
        assertArrayEquals(raw, Base64URL.decode(encoded))
    }

    @Test
    fun base64URL_decode_returnsNullOnGarbage() {
        assertEquals(null, Base64URL.decode("!!! not base64 !!!"))
    }

    @Test
    fun generateKey_is32RandomBytes() {
        val a = RendezvousCrypto.generateKey()
        val b = RendezvousCrypto.generateKey()
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun seal_then_open_roundTrips() {
        val key = RendezvousCrypto.generateKey()
        val plaintext = vectorPlaintext.encodeToByteArray()
        val box = RendezvousCrypto.seal(plaintext, key)
        assertEquals(12 + plaintext.size + 16, box.size) // nonce + ciphertext + tag
        assertArrayEquals(plaintext, RendezvousCrypto.open(box, key))
    }

    @Test
    fun open_theSharedInteropVector() {
        val key = Base64URL.decode(vectorKey); assertNotNull(key)
        val box = Base64URL.decode(vectorBox); assertNotNull(box)
        val plaintext = RendezvousCrypto.open(box!!, key!!)
        assertEquals(vectorPlaintext, plaintext.decodeToString())
    }

    @Test
    fun open_tamperedBox_throws() {
        val key = RendezvousCrypto.generateKey()
        val box = RendezvousCrypto.seal(vectorPlaintext.encodeToByteArray(), key)
        box[box.size - 1] = (box[box.size - 1].toInt() xor 0x01).toByte() // flip a tag bit
        var threw = false
        try { RendezvousCrypto.open(box, key) } catch (e: Throwable) { threw = true }
        assertTrue("open must reject a tampered box", threw)
    }

    @Test
    fun open_wrongKey_throws() {
        val box = RendezvousCrypto.seal(vectorPlaintext.encodeToByteArray(), RendezvousCrypto.generateKey())
        var threw = false
        try { RendezvousCrypto.open(box, RendezvousCrypto.generateKey()) } catch (e: Throwable) { threw = true }
        assertTrue("open must reject a wrong key", threw)
    }

    @Test
    fun open_truncatedInput_throwsCleanly() {
        val key = RendezvousCrypto.generateKey()
        var threw = false
        try { RendezvousCrypto.open(byteArrayOf(0x00, 0x01, 0x02), key) } catch (e: Throwable) { threw = true }
        assertTrue("open must reject short input", threw)
    }
}
