package chat.matron.android.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// App shell (spec §5b): this device's cached copy of the journal-held
/// Coordinator, one per signed-in journal user, and the spec §3a reconcile
/// rule. Ported from matron-apple's `CoordinatorSettingTests`.
class CoordinatorSettingTest {
    @Test
    fun keysArePerUser() {
        assertEquals("coordinator.convoID.@a:s", CoordinatorSetting.defaultsKey("@a:s"))
        assertEquals("coordinator.migrated.@a:s", CoordinatorSetting.migratedKey("@a:s"))
    }

    @Test
    fun cacheRoundTripsPerUser() {
        val store = InMemoryKeyValueStore()
        val a = CoordinatorSetting("@a:s", store)
        val b = CoordinatorSetting("@b:s", store)
        assertNull("null by default", a.convoID.value)
        a.set("cv_1")
        assertEquals("cv_1", a.convoID.value)
        assertNull("another user's setting is untouched", b.convoID.value)
        assertEquals("the cache is what shows before the first connect", "cv_1", CoordinatorSetting("@a:s", store).convoID.value)
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
        a.migrated = true
        CoordinatorSetting.clear("@a:s", store)
        assertNull("clearing removes the key outright", store.getString(CoordinatorSetting.defaultsKey("@a:s")))
        assertNull(CoordinatorSetting("@a:s", store).convoID.value)
        assertFalse("and the migrated flag", CoordinatorSetting("@a:s", store).migrated)
    }

    @Test
    fun migratedFlagPersists() {
        val store = InMemoryKeyValueStore()
        assertFalse(CoordinatorSetting("@a:s", store).migrated)
        CoordinatorSetting("@a:s", store).migrated = true
        assertTrue(CoordinatorSetting("@a:s", store).migrated)
    }

    @Test
    fun theJournalAlwaysWinsAndACachedIdIsPushedOnlyBeforeTheFirstReconcile() {
        assertEquals(CoordinatorSetting.Reconcile.Adopt("j"), CoordinatorSetting.reconcile("j", "c", migrated = false))
        assertEquals(CoordinatorSetting.Reconcile.Adopt("j"), CoordinatorSetting.reconcile("j", null, migrated = true))
        assertEquals(CoordinatorSetting.Reconcile.Push("c"), CoordinatorSetting.reconcile(null, "c", migrated = false))
        assertEquals(CoordinatorSetting.Reconcile.Adopt(null), CoordinatorSetting.reconcile(null, "c", migrated = true))
        assertEquals(CoordinatorSetting.Reconcile.Adopt(null), CoordinatorSetting.reconcile(null, "", migrated = false))
        assertEquals(CoordinatorSetting.Reconcile.Adopt(null), CoordinatorSetting.reconcile(null, null, migrated = false))
    }
}
