package chat.matron.android.viewmodels

import chat.matron.android.push.PushRegistration
import org.junit.Assert.assertEquals
import org.junit.Test

/// The footnote under "On this device" says whether this phone gets push.
class NotifySettingsTitlesPushNoteTest {
    private fun note(configured: Boolean = true, registration: PushRegistration? = PushRegistration.REGISTERED, enabled: Boolean = true) =
        NotifySettingsTitles.devicePushNote(configured, registration, enabled)

    @Test
    fun saysPushWorksWhenRegisteredAndAllowed() {
        assertEquals(NotifySettingsTitles.PUSH_WORKS, note())
    }

    @Test
    fun saysWhyNotOtherwise() {
        assertEquals(NotifySettingsTitles.NO_PUSH_BUILD, note(configured = false))
        assertEquals(NotifySettingsTitles.NOTIFICATIONS_OFF, note(enabled = false))
        assertEquals(NotifySettingsTitles.SERVER_OFF, note(registration = PushRegistration.SERVER_OFF))
        assertEquals(NotifySettingsTitles.NO_TOKEN, note(registration = PushRegistration.NO_TOKEN))
        assertEquals(NotifySettingsTitles.NOT_REGISTERED, note(registration = PushRegistration.FAILED))
        assertEquals(NotifySettingsTitles.NOT_REGISTERED, note(registration = null))
    }
}
