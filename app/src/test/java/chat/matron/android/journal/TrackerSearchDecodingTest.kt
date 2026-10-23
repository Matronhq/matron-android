package chat.matron.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerSearchDecodingTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun decodesEachKnownKindAndDropsTheRest() {
        val hits = TrackerSearchDecoding.hits(
            obj(
                """{"results":[
                  {"kind":"item","id":"it_1","num":12,"name":null,"title":"Pick the nginx timeout","state":"open","why":"exact","snippet":null},
                  {"kind":"mission","id":"ms_1","num":20,"title":"Proxy hardening","why":"words","snippet":"**nginx** reload"},
                  {"kind":"memory","id":"proxy-timeouts","num":null,"name":"proxy-timeouts","title":"Keep it at 120s","why":"similar"},
                  {"kind":"elsewhere","id":"x","title":"x"},
                  {"kind":"item","title":"no id"}
                ],"semantic":"ready"}""",
            ),
        )
        assertEquals(listOf(TrackerSearchKind.ITEM, TrackerSearchKind.MISSION, TrackerSearchKind.MEMORY), hits.map { it.kind })
        assertEquals(12, hits[0].num)
        assertNull(hits[0].snippet)
        assertEquals("**nginx** reload", hits[1].snippet)
        assertEquals("proxy-timeouts", hits[2].name)
        assertNull(hits[2].num)
    }

    @Test
    fun aMissingResultsArrayIsNoHits() {
        assertEquals(emptyList<TrackerSearchHit>(), TrackerSearchDecoding.hits(obj("""{"error":"x"}""")))
        assertEquals(emptyList<TrackerSearchHit>(), TrackerSearchDecoding.hits(null))
    }

    @Test
    fun onlyTheLooseTiersAreLabelled() {
        assertNull(TrackerSearchDecoding.whyLabel("exact"))
        assertNull(TrackerSearchDecoding.whyLabel("number"))
        assertEquals("similar meaning", TrackerSearchDecoding.whyLabel("similar"))
    }
}
