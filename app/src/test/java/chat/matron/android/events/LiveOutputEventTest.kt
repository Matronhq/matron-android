package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOutputEventTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parsesBridgePayload() {
        val event = LiveOutputEvent.parse(payload("""{"tool_use_id":"toolu_123","command":"npm test","viewer_url":"https://viewer.example.com/live?token=abc.def","expires_at":1760000000}"""))!!
        assertEquals("toolu_123", event.toolUseID)
        assertEquals("npm test", event.command)
        assertEquals(Instant.ofEpochSecond(1_760_000_000), event.expiresAt)
    }

    @Test
    fun parseRequiresCommandAndViewerURL() {
        assertNull(LiveOutputEvent.parse(payload("""{"command":"ls"}""")))
        assertNull(LiveOutputEvent.parse(payload("""{"viewer_url":"https://x/live?token=t"}""")))
        assertNull(LiveOutputEvent.parse(payload("""{"command":"","viewer_url":"https://x/live?token=t"}""")))
        assertNull(LiveOutputEvent.parse(payload("""{"tool_name":"Read","snippet":"some file contents"}""")))
    }

    @Test
    fun socketURLRewrite() {
        val event = LiveOutputEvent.parse(payload("""{"command":"ls","viewer_url":"https://viewer.example.com/live?token=abc.def"}"""))!!
        assertEquals("wss://viewer.example.com/live/ws?token=abc.def", event.socketURL)

        val plain = LiveOutputEvent.parse(payload("""{"command":"ls","viewer_url":"http://127.0.0.1:9803/live?token=t"}"""))!!
        assertEquals("ws://127.0.0.1:9803/live/ws?token=t", plain.socketURL)

        val odd = LiveOutputEvent("t", "ls", "https://x/view?token=t", null)
        assertNull(odd.socketURL)
    }

    @Test
    fun expiry() {
        val expired = LiveOutputEvent("t", "ls", "https://x/live?token=t",
            Instant.now().minusSeconds(60))
        assertTrue(expired.isExpired)
        val live = LiveOutputEvent("t", "ls", "https://x/live?token=t",
            Instant.now().plusSeconds(3600))
        assertFalse(live.isExpired)
        val noExpiry = LiveOutputEvent("t", "ls", "https://x/live?token=t", null)
        assertFalse(noExpiry.isExpired)
    }

    @Test
    fun frameDecode() {
        assertEquals(LiveOutputFrame.Data("hello\n"),
            LiveOutputFrame.decode("""{"type":"data","chunk":"hello\n"}"""))
        assertEquals(LiveOutputFrame.Complete(0, false, false),
            LiveOutputFrame.decode("""{"type":"complete","exitCode":0,"denied":false,"truncated":false}"""))
        assertEquals(LiveOutputFrame.Complete(1, false, true),
            LiveOutputFrame.decode("""{"type":"complete","exitCode":1,"truncated":true}"""))
        assertNull(LiveOutputFrame.decode("""{"type":"nonsense"}"""))
        assertNull(LiveOutputFrame.decode("not json"))
    }
}
