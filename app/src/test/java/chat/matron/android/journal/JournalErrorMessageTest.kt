package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins the human-readable `message` of every journal error case — these
/// surface verbatim in UI banners (chat error overlay, composer send error,
/// sign-in form). Ported from matron-apple's `JournalErrorDescriptionTests`;
/// without them an offline send rendered as an enum dump.
class JournalErrorMessageTest {

    @Test
    fun apiErrorMessagesAreHuman() {
        assertEquals("Invalid credentials.", JournalApiError.BadCredentials.message)
        assertEquals("Too many attempts — try again in 30s.", JournalApiError.LockedOut(30).message)
        assertEquals("The server is busy — trying again shortly.", JournalApiError.RateLimited.message)
        assertEquals("Signed out by the server — please sign in again.", JournalApiError.Unauthenticated.message)
        assertEquals("The server refused the request.", JournalApiError.Forbidden.message)
        assertEquals("Not found on the server.", JournalApiError.NotFound.message)
        assertEquals("Already handled — possibly on another device.", JournalApiError.Conflict.message)
    }

    @Test
    fun httpErrorPrefersServerMessageOverStatus() {
        assertEquals("upstream exploded", JournalApiError.Http(502, "upstream exploded").message)
        assertEquals("Server error (HTTP 502).", JournalApiError.Http(502, "").message)
    }

    @Test
    fun transportErrorIncludesDetailWhenPresent() {
        assertEquals("Couldn't reach the server.", JournalApiError.Transport("").message)
        assertEquals(
            "Couldn't reach the server — connection reset",
            JournalApiError.Transport("connection reset").message,
        )
    }

    @Test
    fun syncAndRpcErrorMessagesAreHuman() {
        assertEquals("No connection to the server.", JournalSyncError.Offline.message)
        assertEquals("This device was signed out by the server.", JournalSyncError.AuthRevoked.message)
        assertEquals("The agent didn't answer in time.", RPCRequestError.Timeout.message)
        assertEquals("No connection to the server.", RPCRequestError.Offline.message)
    }
}
