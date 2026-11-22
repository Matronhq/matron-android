package chat.matron.android.push

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Opens boxes the journal sealed. Every vector below was made by the
/// journal's own code (matron-journal `src/push-box.js` `sealBox` and
/// `boxPlaintext`, with `src/push.js` `pushContent` building the content),
/// sealed to the fixture recipient its tests use: the key derived as
/// SHA-256("fixture recipient"). Opening them here proves the Kotlin
/// recipient and the node sender agree byte for byte.
class PushBoxTest {
    private val recipient = MessageDigest.getInstance("SHA-256").digest("fixture recipient".toByteArray())

    /// The vector matron-journal's `test/push-box.test.js` pins (and the
    /// Apple app's tests open with CryptoKit).
    private val journalVector =
        "pSB6orUR--of_qx2eAlOOaZ21Ut5_WVkw9NJ3JPhGE2q0JsD_VvSzNA6StCz9rGrWiKgFQF6uyobsVS05yZ6fYhuxU9XxTlI-PJHA6_XgxRA73hDFGeTlN-WqmuK43qJY9VfUyyoxcvJ_S7RfIJCgceCpmIO8xnQm6Apt1RuYKCcJ6vw2wWsW0dnXV2HKDHDaHZ7IJR1EzzhGaQcXftdYY1C45aLoh3_6ZVCGDz_zbXiKy96b10UIsTZcpqMBw5Ap_eBZuf2GZCaBUlDxxeJf1EI3Z17WXX8jUzMQznoVy2Etxh8oobnhG7D61pEqmStSIbp3AJJk6hvmOi2JViIWhWg59oIgWpDEisthHRrZk1XZfR14wD7EDRL2roWOHpN5Aiz0BPT91zJyX7NzyKFLA"

    /// An item comment with three buttons, a reply-to and non-ASCII text,
    /// built by the journal's `pushContent` and sealed with the ephemeral
    /// key SHA-256("android vector item").
    private val itemVector =
        "G4aKnRf2vZxh3BsOM5i4sssxih3f_a0As-OjTNPknmJPu9gBrj5b7QD3P1YAJT659tWiky9qnXoh6rqKW86qHL-ILobGFEVCuz5SzkCJqJa1OqTFFCMSuEeCPQaJWJcBLtSP5sp0qHOifVkhJhyymioYjjiWX6pKPZzGNNCbolTNXht58EP4jSG77-JHUt8pr_5K1fC1_evbMiBDmfhWaMGQw6zVgnGHm51nV93GfRuiFzKdB5q5JROGQ3_tetsnsYzaJvjRF0rAr9m5-ey0PiFvjti741GA8usQN65AG6Zc023j0k4SZs2z9av14mVksiZcv-_TwAJ7PpzCFeZJ7_NAU12_SMF0svE-TJrkpQu-C70LuC4jq1pjkS03OFOD-4yCQ6IAo_JvwC4HQPj6juVKB15HYu8YTf_4GVwdhSifxNdT-QrDduh-cDHppWvcqgU5bvOBz6VPd5-qMztYi_PKf_JwIFePugOvN5m38XEQZ0YVlcbTPndgRbNCeJErnZ3jga2_wAKzotI_Ms8O9FVQDQ-Al1AKYrcLKXKSpeuNl2YSshb56VvmtZSYx3iE5-JIUsvE5mHVBELS9oDsj5nrZqEsIS8zJl-FgvBTZTaFZ2qxgwXRcTvIB6pLnqFzdvVhYSHbucwNFGzXobans61FJmKFPgBvhPFFVwePXJ4GjUOhw0O3bSCTgM0CpWV02GNHJwapD5uh2WpIGVSBx0js78_VlIcyYlbK1x3fOow"

    /// A finished turn's text message, ephemeral key SHA-256("android vector text").
    private val textVector =
        "61PF9q6oQRpD8AwfeNGiSu4DHiIhtpAbarOIUTBoLHGgE-QCn7ADFGcc-4JN2ap8fSqpcZG7RnWyDwKmuCqjNHFADK3p85Hz-zVUG_p-kpfCSN2kafte3RMU2l93kSAlnfmzg8OI2edyy5CwtehYVLyV_9Bz2uV5lOZMRCKcZqMHs56hjaYrYWExOKTcjDglkprNoNx-4yQst_X5DbCXrhrtC8kM_xZEbBcXS-jV7fgD0ROFWM2_YtJ-VtIbcUWjUxUvxBc4s8QcnjT2rITRFb27d5RZysEaGddKjrUQX2GiG12wwwCbtC_1osYjAnPeTG5bfpsLCRrl-MUfOUH6JVuEBIfSs4hCdUDZ3A0qRFrcuqQXdlxS-LJ2bgWDYbPZz2xAccpapq7bdZ0DH2RUiQ"

    @Test
    fun recipientPublicKeyMatchesTheJournalFixture() {
        val public = com.google.crypto.tink.subtle.X25519.publicFromPrivate(recipient)
        assertEquals("uLHqgzncBWWOwTpUixAlfi0qAZ_p0tSU6vRJv7LCvi4", PushBox.encodeBase64Url(public))
    }

    @Test
    fun opensTheJournalsPinnedVector() {
        val plain = PushBox.open(journalVector, recipient)!!
        // Padded with spaces to a multiple of 256 bytes, as the journal pads.
        assertEquals(0, plain.size % 256)
        assertEquals(
            PushContent(
                title = "Ship the release notes?", subtitle = "Docs",
                body = "Two options: ship now, or wait for the screenshots.",
                convoID = "c-1", seq = 42, kind = "attention",
                item = PushContent.Item("it_1", 12, "question", listOf("Ship", "Wait"), null),
            ),
            PushContent.parse(plain),
        )
    }

    @Test
    fun opensAnItemCommentSealedByTheJournal() {
        assertEquals(
            PushContent(
                title = "Deploy to box-a? ✅", subtitle = "Question · Release train",
                body = "Tests are green — ship café build?",
                convoID = "c-9", seq = 314, kind = "attention",
                item = PushContent.Item("it_77", 77, "question", listOf("Ship", "Hold", "Ask bob"), "cm_5"),
            ),
            PushBox.content(itemVector, recipient),
        )
    }

    @Test
    fun opensAFinishedTurnSealedByTheJournal() {
        assertEquals(
            PushContent(
                title = "Migrations", subtitle = null, body = "Finished the migration on box-b.",
                convoID = "c-2", seq = 9, kind = "done", item = null,
            ),
            PushBox.content(textVector, recipient),
        )
    }

    @Test
    fun anotherKeyOpensNothing() {
        val other = MessageDigest.getInstance("SHA-256").digest("someone else".toByteArray())
        assertNull(PushBox.open(journalVector, other))
    }

    @Test
    fun anAlteredBoxOpensNothing() {
        val raw = PushBox.decodeBase64Url(journalVector)!!
        for (index in listOf(0, 31, 40, raw.size - 1)) {
            val altered = raw.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull("byte $index", PushBox.open(PushBox.encodeBase64Url(altered), recipient))
        }
    }

    @Test
    fun malformedBoxesOpenNothing() {
        assertNull(PushBox.open("", recipient))
        assertNull(PushBox.open("short", recipient))
        assertNull(PushBox.open("$journalVector=", recipient)) // padding is not the wire form
        assertNull(PushBox.open(journalVector.replace('-', '+'), recipient))
        assertNull(PushBox.open("A".repeat(PushBox.MAX_CHARACTERS + 4), recipient))
        assertNull(PushBox.open(journalVector, ByteArray(31)))
    }

    @Test
    fun base64UrlRoundTrips() {
        val bytes = ByteArray(40) { it.toByte() }
        assertArrayEquals(bytes, PushBox.decodeBase64Url(PushBox.encodeBase64Url(bytes)))
        assertNotNull(PushBox.decodeBase64Url("uLHqgzncBWWOwTpUixAlfi0qAZ_p0tSU6vRJv7LCvi4"))
    }
}
