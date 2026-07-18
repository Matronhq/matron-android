package chat.matron.android.journal

import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayApiTest {
    private val secret = "a".repeat(64)

    @Test
    fun mapCreate_parses201() {
        val r = RelayApi.mapCreate(201, """{"rid":"23456789BCDFGHJKMNPQRSTVWX","secret":"$secret","expires_in":180}""")
        assertEquals(Rendezvous("23456789BCDFGHJKMNPQRSTVWX", secret, 180), r)
    }

    @Test
    fun mapCreate_errors() {
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapCreate(429, """{"status":429,"reason":"rate_limited"}""") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapCreate(201, """{"nope":true}""") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapCreate(500, "") }
    }

    @Test
    fun mapPoll_coversAllStates() {
        assertEquals(RendezvousPollResult.Waiting, RelayApi.mapPoll(204, ""))
        assertEquals(
            RendezvousPollResult.Offered("https://j.example.com", "2345-6789"),
            RelayApi.mapPoll(200, """{"server":"https://j.example.com","code":"2345-6789"}"""),
        )
        assertFailsWith<RelayError.NotFound> { RelayApi.mapPoll(404, "") }
        assertFailsWith<RelayError.Forbidden> { RelayApi.mapPoll(403, "") }
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapPoll(429, "") }
        assertFailsWith<RelayError.Transport> { RelayApi.mapPoll(200, """{"server":"https://x"}""") }
    }

    @Test
    fun mapOffer_coversAllStates() {
        RelayApi.mapOffer(204) // no throw
        assertFailsWith<RelayError.Conflict> { RelayApi.mapOffer(409) }
        assertFailsWith<RelayError.NotFound> { RelayApi.mapOffer(404) }
        assertFailsWith<RelayError.RateLimited> { RelayApi.mapOffer(429) }
        assertFailsWith<RelayError.Transport> { RelayApi.mapOffer(400) }
    }

    @Test
    fun requestBuilders_hitTheDocumentedPathsAndBodies() {
        val create = RelayApi.createRequest("https://push.matron.chat")
        assertEquals("https://push.matron.chat/link/rendezvous", create.url.toString())
        assertEquals("POST", create.method)

        val poll = RelayApi.pollRequest("https://push.matron.chat", "RID23456789BCDFGHJKMNPQRST", "SEC")
        assertEquals("https://push.matron.chat/link/rendezvous/RID23456789BCDFGHJKMNPQRST?secret=SEC", poll.url.toString())
        assertEquals("GET", poll.method)

        val offer = RelayApi.offerRequest("https://push.matron.chat", "RID23456789BCDFGHJKMNPQRST", "https://j.example.com", "2345-6789")
        assertEquals("https://push.matron.chat/link/rendezvous/RID23456789BCDFGHJKMNPQRST/offer", offer.url.toString())
        assertEquals("POST", offer.method)
        val buffer = okio.Buffer().also { offer.body!!.writeTo(it) }
        assertEquals("""{"server":"https://j.example.com","code":"2345-6789"}""", buffer.readUtf8())
    }
}
