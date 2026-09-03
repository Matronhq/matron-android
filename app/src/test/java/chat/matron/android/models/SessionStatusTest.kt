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
            limits = null, email = "dan@example.com", workdir = null, vitals = null, taskRef = "toolu_parent_1",
            modelOptions = null, effortLevels = null, effort = null,
        ))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertNull(status.limits)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A limits-only frame must not clear model/context/email/taskRef.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null, context = null,
            limits = listOf(SessionStatus.Limit("Session", 39, "soon", null)),
            email = null, workdir = null, vitals = null, taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        ))
        assertEquals("claude-fable-5", status.model)
        assertEquals(10, status.context?.pct)
        assertEquals(1, status.limits?.size)
        assertEquals("dan@example.com", status.email)
        assertEquals("toolu_parent_1", status.taskRef)

        // A newer context replaces the old one.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null,
            context = SessionStatus.Context(200_000, 1_000_000, 20),
            limits = null, email = null, workdir = null, vitals = null, taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        ))
        assertEquals(200_000, status.context?.tokens)
        assertEquals(1, status.limits?.size)

        // A newer email replaces the old one.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = null, context = null, limits = null,
            email = "other@example.com", workdir = null, vitals = null, taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        ))
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
            limits = null, email = null, workdir = null, vitals = null, taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        )

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
            workdir = "/Users/dan/Dev/x", vitals = SessionStatus.Vitals(12, 63), taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        ))
        // A later frame without vitals/workdir must not blank the held sample.
        status = status.merged(SessionStatusUpdate(
            convoID = "c1", model = "claude-fable-5", context = null, limits = null, email = null,
            workdir = null, vitals = null, taskRef = null,
            modelOptions = null, effortLevels = null, effort = null,
        ))
        assertEquals("/Users/dan/Dev/x", status.workdir)
        assertEquals(SessionStatus.Vitals(12, 63), status.vitals)
        assertEquals("claude-fable-5", status.model)
    }

    private fun update(
        modelOptions: List<SessionStatus.Option>? = null,
        effortLevels: List<SessionStatus.Option>? = null,
        effort: SessionStatusUpdate.Effort? = null,
        model: String? = null,
    ) = SessionStatusUpdate(
        convoID = "c1", model = model, context = null, limits = null, email = null, taskRef = null,
        workdir = null, vitals = null, modelOptions = modelOptions, effortLevels = effortLevels, effort = effort,
    )

    /// The session-derived argument lists and the effort level merge like every
    /// other field: a frame that omits one leaves the previous value standing.
    /// Absent and empty are distinct and must not be conflated — absent means
    /// "this bridge doesn't say", empty means "this agent offers nothing" — so
    /// an empty list overwrites a held one (apple #163).
    @Test
    fun mergedMergesOptionListsAndEffort() {
        var status = SessionStatus()
        assertNull(status.modelOptions)
        assertNull(status.effortLevels)
        assertNull(status.effort)
        status = status.merged(update(
            model = "opus",
            modelOptions = listOf(SessionStatus.Option("opus", "Opus")),
            effortLevels = listOf(SessionStatus.Option("high", "High")),
            effort = SessionStatusUpdate.Effort.Set("high"),
        ))
        assertEquals(listOf("opus"), status.modelOptions?.map { it.value })
        assertEquals(listOf("high"), status.effortLevels?.map { it.value })
        assertEquals("high", status.effort)
        // A frame carrying none of the three leaves all three standing.
        status = status.merged(update())
        assertEquals(listOf("opus"), status.modelOptions?.map { it.value })
        assertEquals(listOf("high"), status.effortLevels?.map { it.value })
        assertEquals("high", status.effort)
        // An empty list is a statement, not silence: the held list goes.
        status = status.merged(update(modelOptions = emptyList()))
        assertEquals(emptyList<SessionStatus.Option>(), status.modelOptions)
        assertEquals(listOf("high"), status.effortLevels?.map { it.value })
        status = status.merged(update(effort = SessionStatusUpdate.Effort.Set("max")))
        assertEquals("max", status.effort)
    }

    /// Effort is tri-state, unlike every other field: an explicit null on the
    /// wire is a statement — it clears. Absence stays silence.
    @Test
    fun mergedTreatsEffortAsTriState() {
        var status = SessionStatus().merged(update(effort = SessionStatusUpdate.Effort.Set("xhigh")))
        assertEquals("xhigh", status.effort)
        status = status.merged(update())
        assertEquals("an absent effort must leave the tracked level standing", "xhigh", status.effort)
        status = status.merged(update(effort = SessionStatusUpdate.Effort.Cleared))
        assertNull("an explicit null clears the tracked level", status.effort)
        status = status.merged(update(effort = SessionStatusUpdate.Effort.Cleared))
        assertNull(status.effort)
        status = status.merged(update(effort = SessionStatusUpdate.Effort.Set("low")))
        assertEquals("low", status.effort)
    }
}
