package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatusTest {
    @Test
    fun applyMergesPartsIndependently() {
        val status = SessionStatus()
        status.apply(SessionStatusUpdate(
            convoID = "c1", model = "claude-fable-5",
            context = SessionStatus.Context(100_000, 1_000_000, 10),
            limits = null, email = "dan@example.com", taskRef = "toolu_parent_1"))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertNull(status.limits)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A limits-only frame must not clear model/context/email/taskRef.
        status.apply(SessionStatusUpdate(
            convoID = "c1", model = null, context = null,
            limits = listOf(SessionStatus.Limit("Session", 39, "soon", null)),
            email = null, taskRef = null))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertEquals(1, status.limits?.size)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A newer context replaces the old one.
        status.apply(SessionStatusUpdate(
            convoID = "c1", model = null,
            context = SessionStatus.Context(200_000, 1_000_000, 20),
            limits = null, email = null, taskRef = null))
        assertEquals(200_000, status.context?.tokens)
        assertEquals(1, status.limits?.size)

        // A newer email replaces the old one.
        status.apply(SessionStatusUpdate(
            convoID = "c1", model = null, context = null, limits = null,
            email = "other@example.com", taskRef = null))
        assertEquals("other@example.com", status.email)
    }
}
