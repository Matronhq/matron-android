package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSessionSignInTest {
    private val alice = UserSession(
        userID = "alice", deviceID = "12", homeserverURL = "https://journal.example.com/", accessToken = "t1",
    )

    @Test
    fun sameSignIn_ignoresTheToken() {
        // An account unlock gives the same device a new token.
        assertTrue(alice.isSameSignIn(alice.copy(accessToken = "t2", refreshToken = "r")))
        assertEquals(alice.signInKey, alice.copy(accessToken = "t2").signInKey)
    }

    @Test
    fun aNewDeviceID_isANewSignIn() {
        assertFalse(alice.isSameSignIn(alice.copy(deviceID = "13")))
    }

    @Test
    fun anotherJournal_isAnotherSignIn() {
        assertFalse(alice.isSameSignIn(alice.copy(homeserverURL = "https://other.example.com/")))
    }

    @Test
    fun anotherAccount_isAnotherSignIn() {
        assertFalse(alice.isSameSignIn(alice.copy(userID = "bob")))
    }

    @Test
    fun storeFileName_isPerSignIn() {
        assertEquals("alice.d12.sqlite", alice.journalStoreFileName)
        assertNotEquals(alice.journalStoreFileName, alice.copy(deviceID = "13").journalStoreFileName)
        assertEquals("alice.sqlite", alice.legacyJournalStoreFileName)
    }

    @Test
    fun storeFileName_keepsOnlySafeCharacters() {
        val odd = alice.copy(userID = "@al/ice:s", deviceID = "1/../2")
        assertEquals("_al_ice_s.sqlite", odd.legacyJournalStoreFileName)
        assertEquals("_al_ice_s.d12.sqlite", odd.journalStoreFileName)
    }
}
