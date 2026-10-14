package chat.matron.android.models

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionsFilterTest {
    private val missions = listOf(
        Mission("ms_1", 21, title = "Café launch", name = "Website", originConvoID = "c1"),
        Mission("ms_2", 34, title = "Upload retry", name = "Photo tools", originConvoID = "c1"),
        Mission("ms_3", 56, state = MissionState.CLOSED, title = "Old export", name = "Cafe archive", originConvoID = "c1"),
        Mission("ms_4", 78, title = "Icon cleanup", originConvoID = "c1"),
    )

    private fun ids(query: String) = MissionsFilter(query).apply(missions).map { it.id }

    @Test
    fun emptyAndWhitespaceQueriesKeepEverythingInOrder() {
        for (query in listOf("", "  \t\n", "\u00a0\u2003")) {
            assertFalse(MissionsFilter(query).isActive)
            assertEquals(missions, MissionsFilter(query).apply(missions))
        }
    }

    @Test
    fun findsTitlesShortNamesAndClosedMissions() {
        assertEquals(listOf("ms_1", "ms_3"), ids("cafe"))
        assertEquals(listOf("ms_2"), ids("photo"))
        assertEquals(listOf("ms_4"), ids("cleanup"))
        assertEquals(listOf("ms_3"), ids("archive"))
        assertEquals(listOf("ms_2"), ids("load"))
    }

    @Test
    fun everyWordMustMatchInAnyOrderAcrossFields() {
        assertEquals(listOf("ms_1"), ids("WEBSITE launch"))
        assertEquals(listOf("ms_1"), ids("launch WEBSITE"))
        assertEquals(listOf("ms_1"), ids("  launch\tWEBSITE\n#21  "))
        assertEquals(listOf("ms_1"), ids("launch\u00a0website\u2003#21"))
        assertEquals(emptyList<String>(), ids("launch zebra"))
        assertEquals(emptyList<String>(), ids("launch photo"))
    }

    @Test
    fun ignoresAccentsInBothQueryAndMissionIncludingDecomposedText() {
        assertEquals(listOf("ms_1", "ms_3"), ids("CAFÉ"))
        assertEquals(listOf("ms_1", "ms_3"), ids("CAFE\u0301"))
        val decomposed = missions.first().copy(title = "Cafe\u0301 launch")
        assertTrue(MissionsFilter("cafe").matches(decomposed))
    }

    @Test
    fun matchesNumbersWithOrWithoutHash() {
        assertEquals(listOf("ms_2"), ids("#34"))
        assertEquals(listOf("ms_2"), ids("34"))
        assertEquals(listOf("ms_2"), ids("#34 retry"))
        assertEquals(emptyList<String>(), ids("#99"))
    }

    @Test
    fun ignoresDeviceLocaleWhenFoldingCase() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf("ms_1"), ids("WEBSITE"))
            assertEquals(listOf("ms_4"), ids("ICON"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
