package chat.matron.android.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainAppVisibilityTest {
    @Test
    fun aRecreatedActivityStaysVisibleWhenTheOldOneStopsLast() {
        MainAppVisibility.noteStarted() // old instance
        MainAppVisibility.noteStarted() // new instance starts first
        MainAppVisibility.noteStopped() // old instance stops after
        assertTrue(MainAppVisibility.isStarted)
        MainAppVisibility.noteStopped()
        assertFalse(MainAppVisibility.isStarted)
        // An extra stop never drives it below zero.
        MainAppVisibility.noteStopped()
        MainAppVisibility.noteStarted()
        assertTrue(MainAppVisibility.isStarted)
        MainAppVisibility.noteStopped()
    }
}
