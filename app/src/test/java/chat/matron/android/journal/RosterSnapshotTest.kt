package chat.matron.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RosterSnapshotTest {
    private fun decode(raw: String) = RosterSnapshot.decode(Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun decode_groupsOpenSessionsByBoxAndReadsWakeableAgents() {
        val snapshot = decode(
            """{"conversations":[
                 {"id":"c1","title":"Fix the import test","session_state":"running","agent_device_id":2,"last_ts":1000},
                 {"id":"c2","title":"Release notes","session_state":"waiting","agent_device_id":2},
                 {"id":"c3","title":"Old","session_state":"done","agent_device_id":2},
                 {"id":"c4","title":"No box","session_state":"running"},
                 {"id":"c5","session_state":"waiting","agent_device_id":3}],
               "agents":[{"device_id":2,"connected":true},{"device_id":3,"wakeable":true},{"device_id":4,"wakeable":false}]}""",
        )!!
        assertEquals(
            listOf(
                RosterSession("c1", "Fix the import test", isRunning = true, lastActivityMs = 1000),
                RosterSession("c2", "Release notes", isRunning = false, lastActivityMs = null),
            ),
            snapshot.sessionsByBox[2],
        )
        assertEquals(listOf(RosterSession("c5", "", false, null)), snapshot.sessionsByBox[3])
        assertEquals(setOf(3L), snapshot.wakeableBoxes)
    }

    @Test
    fun decode_withoutConversationsIsMalformed() {
        assertNull(decode("""{"agents":[]}"""))
        assertEquals(RosterSnapshot(), decode("""{"conversations":[]}"""))
    }
}
