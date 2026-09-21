package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// App shell (spec §5b): the coordinator conversation id, one per signed-in
/// journal user. Ported from matron-apple's `CoordinatorSettingTests`.
class CoordinatorSettingTest {
    @Test
    fun keyIsPerUser() {
        assertEquals("coordinator.convoID.@a:s", CoordinatorSetting.defaultsKey("@a:s"))
    }

    @Test
    fun roundTripsPerUser() {
        val store = InMemoryKeyValueStore()
        val a = CoordinatorSetting("@a:s", store)
        val b = CoordinatorSetting("@b:s", store)
        assertNull("null by default", a.convoID.value)
        a.set("cv_1")
        assertEquals("cv_1", a.convoID.value)
        assertNull("another user's setting is untouched", b.convoID.value)
        assertEquals("survives a new instance", "cv_1", CoordinatorSetting("@a:s", store).convoID.value)
    }

    @Test
    fun clears() {
        val store = InMemoryKeyValueStore()
        val a = CoordinatorSetting("@a:s", store)
        a.set("cv_1")
        a.set(null)
        assertNull(a.convoID.value)
        a.set("")
        assertNull("an empty stored value is no coordinator", a.convoID.value)
        a.set("cv_2")
        CoordinatorSetting.clear("@a:s", store)
        assertNull("clearing removes the key outright", store.getString(CoordinatorSetting.defaultsKey("@a:s")))
        assertNull(CoordinatorSetting("@a:s", store).convoID.value)
    }
}
