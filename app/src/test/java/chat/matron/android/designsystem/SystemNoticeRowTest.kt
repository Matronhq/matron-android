package chat.matron.android.designsystem

import chat.matron.android.events.SystemNoticeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNoticeRowTest {
    private val notice = SystemNoticeEvent("routine", "⏰", "Routine fired", "Daily sweep: check every open mission.")

    @Test
    fun theBodyShowsOnlyWhenExpanded() {
        assertFalse(systemNoticeShowsBody(notice, expanded = false))
        assertTrue(systemNoticeShowsBody(notice, expanded = true))
        assertFalse("nothing to show", systemNoticeShowsBody(notice.copy(body = ""), expanded = true))
    }

    @Test
    fun theTapSaysWhatItWillDo() {
        assertEquals("Show the full text", systemNoticeToggleLabel(expanded = false))
        assertEquals("Hide the full text", systemNoticeToggleLabel(expanded = true))
    }
}
