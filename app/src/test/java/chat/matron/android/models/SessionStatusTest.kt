package chat.matron.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStatusTest {
    @Test
    fun mergedMergesPartsIndependently() {
        var status = SessionStatus()
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = "claude-fable-5",
            context = SessionStatus.Context(100_000, 1_000_000, 10),
            limits = null, email = "dan@example.com", workdir = null, vitals = null, taskRef = "toolu_parent_1"))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertNull(status.limits)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A limits-only frame must not clear model/context/email/taskRef.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null, context = null,
            limits = listOf(SessionStatus.Limit("Session", 39, "soon", null)),
            email = null, workdir = null, vitals = null, taskRef = null))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertEquals(1, status.limits?.size)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A newer context replaces the old one.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null,
            context = SessionStatus.Context(200_000, 1_000_000, 20),
            limits = null, email = null, workdir = null, vitals = null, taskRef = null))
        assertEquals(200_000, status.context?.tokens)
        assertEquals(1, status.limits?.size)

        // A newer email replaces the old one.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null, context = null, limits = null,
            email = "other@example.com", workdir = null, vitals = null, taskRef = null))
        assertEquals("other@example.com", status.email)
    }

    @Test
    fun mergedReturnsANewInstanceEachTime() {
        // StateFlow conflates by equality; if merged() ever mutated in place
        // and returned `this`, a MutableStateFlow assignment of the result
        // would compare equal to the previous value and silently drop the
        // emission. Guard the immutability contract directly.
        val original = SessionStatus(model = "claude-fable-5")
        val update = SessionStatusUpdate(
            convoID = "c1", model = null,
            context = SessionStatus.Context(100_000, 1_000_000, 10),
            limits = null, email = null, workdir = null, vitals = null, taskRef = null)

        val result = original.merged(update)

        assertNotSame(original, result)
        assertNull(original.context)
        assertEquals(10, result.context?.pct)
    }

    // MARK: workdir + vitals merge (matron-apple #90 port)

    @Test
    fun mergedKeepsWorkdirAndVitalsWhenUpdateOmitsThem() {
        var status = SessionStatus()
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null, context = null, limits = null, email = null,
            workdir = "/Users/dan/Dev/x", vitals = SessionStatus.Vitals(12, 63), taskRef = null))
        // A later frame without vitals/workdir must not blank the held sample.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = "claude-fable-5", context = null, limits = null, email = null,
            workdir = null, vitals = null, taskRef = null))
        assertEquals("/Users/dan/Dev/x", status.workdir)
        assertEquals(SessionStatus.Vitals(12, 63), status.vitals)
        assertEquals("claude-fable-5", status.model)
    }
}
