package chat.matron.android.models

import chat.matron.android.designsystem.ConversationRoomsFormat
import chat.matron.android.features.chat.RoomsSheetPath
import chat.matron.android.journal.db.ConversationEntity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRoomsTest {
    private fun room(id: String, participants: List<String>, at: Long?, state: String = "waiting") =
        MissionRoom(id, "Room $id", state, at?.let(Instant::ofEpochMilli), participants)

    @Test
    fun listsEveryRoomTheConversationTakesPartInNewestFirst() {
        val rooms = listOf(
            room("r-old", listOf("c-a", "c-b"), at = 100),
            room("r-other", listOf("c-b", "c-c"), at = 900),
            room("r-new", listOf("c-c", "c-a"), at = 500, state = "running"),
            room("r-never-b", listOf("c-a"), at = null),
            room("r-never-a", listOf("c-a"), at = null, state = "done"),
        )
        val mine = ConversationRoomsRule.rooms(of = "c-a", among = rooms)
        assertEquals(listOf("r-new", "r-old", "r-never-a", "r-never-b"), mine.map { it.id })
        assertEquals(RoomSessionState.RUNNING, mine[0].state)
        assertEquals(RoomSessionState.WAITING, mine[1].state)
        assertEquals(RoomSessionState.DONE, mine[2].state)
    }

    @Test
    fun aRoomIsNeverARoomOfItself() {
        val rooms = listOf(room("r", listOf("r", "c-a"), at = 1))
        assertTrue(ConversationRoomsRule.rooms(of = "r", among = rooms).isEmpty())
    }

    @Test
    fun anUnknownSessionStateReadsAsWaiting() {
        assertEquals(RoomSessionState.WAITING, RoomSessionState.from("idle"))
        assertEquals(RoomSessionState.RUNNING, RoomSessionState.from("running"))
        assertEquals(RoomSessionState.DONE, RoomSessionState.from("done"))
    }

    @Test
    fun rowsBecomeRoomsWithCleanTitlesAndDropUnknownParticipants() {
        fun row(id: String, title: String, convos: String?, ts: Long? = null, created: Long = 0) = ConversationEntity(
            id = id, title = title, sessionState = "waiting", lastSeq = 1, snippet = "", createdAt = created,
            lastActivityTS = ts, muted = false, hidden = false, readUpToSeq = 0, unreadCount = 0,
            parentConvoID = null, participantConvos = convos,
        )
        val rooms = MissionRoom.fromRows(
            listOf(
                row("r1", "🔗 [ab] Plan the launch", """["c-a","c-b"]""", ts = 50),
                row("r2", "↔️ box-a ↔ box-b", """["c-a"]""", created = 7),
                row("r3", "🔗 [cd] ", """["c-a"]"""),
                row("r4", "Unreadable", "not json"),
                row("r5", "Empty", "[]"),
            ),
        )
        assertEquals(listOf("r1", "r2", "r3"), rooms.map { it.id })
        assertEquals("Plan the launch", rooms[0].title)
        assertEquals(50L, rooms[0].lastActivity?.toEpochMilli())
        assertEquals("box-a ↔ box-b", rooms[1].title)
        assertEquals("created_at stands in for a room that never had activity", 7L, rooms[1].lastActivity?.toEpochMilli())
        assertNull(rooms[2].lastActivity)
        assertTrue(rooms[2].title.isNotEmpty())
    }

    @Test
    fun formatSaysHowManyRooms() {
        assertNull(ConversationRoomsFormat.label(0))
        assertEquals("Rooms · 1", ConversationRoomsFormat.label(1))
        assertEquals("Rooms · 3", ConversationRoomsFormat.label(3))
        assertEquals("1 agent chat room", ConversationRoomsFormat.accessibilityLabel(1))
        assertEquals("2 agent chat rooms", ConversationRoomsFormat.accessibilityLabel(2))
        assertEquals(
            "Running, Plan the launch",
            ConversationRoomsFormat.rowAccessibilityLabel(ConversationRoom("r", "Plan the launch", RoomSessionState.RUNNING)),
        )
    }

    @Test
    fun aRoomsTitleFallsBackToTheStoredTitleThenAPlainWord() {
        val rooms = listOf(ConversationRoom("r", "Live title", RoomSessionState.WAITING))
        assertEquals("Live title", ConversationRoomsFormat.title("r", rooms, stored = "Stored"))
        assertEquals("Stored", ConversationRoomsFormat.title("x", rooms, stored = "Stored"))
        assertEquals("Room", ConversationRoomsFormat.title("x", rooms, stored = null))
    }

    @Test
    fun theSheetOpensStraightOnASoleRoomAndOnTheListOtherwise() {
        val one = listOf(ConversationRoom("r", "R", RoomSessionState.WAITING))
        val two = one + ConversationRoom("s", "S", RoomSessionState.DONE)
        assertEquals("r", RoomsSheetPath.soleRoomID(one))
        assertNull(RoomsSheetPath.soleRoomID(two))
        assertNull(RoomsSheetPath.soleRoomID(emptyList()))

        val sole = RoomsSheetPath(soleRoomID = "r")
        assertEquals(RoomsSheetPath.Page.room("r"), sole.top)
        assertFalse("Back from the sole room closes the sheet", sole.canGoBack)

        val list = RoomsSheetPath(soleRoomID = null)
        assertNull(list.top)
        val opened = list.openRoom("s")
        assertEquals("s", opened.top?.id)
        assertTrue(opened.canGoBack)
        assertNull(opened.back().top)
    }

    @Test
    fun aSubagentGoesOnTopOfItsRoomAndSiblingsReplaceEachOther() {
        val path = RoomsSheetPath(soleRoomID = "r").openSubagent("kid-1")
        assertEquals(RoomsSheetPath.Page("kid-1", isRoom = false, roomID = "r"), path.top)
        val switched = path.openSubagent("kid-2")
        assertEquals(1, switched.pages.size)
        assertEquals("kid-2", switched.top?.id)
        assertEquals("r", switched.top?.roomID)
        assertEquals("Back from a subagent returns to the room", "r", switched.back().top?.id)
        assertEquals("opening the page already on top is a no-op", switched, switched.openSubagent("kid-2"))
    }
}
