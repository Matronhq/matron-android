package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The journal's durable resolution event for an agent-spawn card
/// (matron-journal `docs/superpowers/specs/2026-08-11-spawn-outcome-events-design.md`).
class SpawnOutcomeTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parsesStartedWithRoomAndChildConvo() {
        val outcome = SpawnOutcome.parse(
            payload("""{"request_id":"spawn-1","outcome":"started","room_id":"room-9","child_convo_id":"child-1"}"""),
        )!!
        assertEquals("spawn-1", outcome.requestId)
        assertEquals("started", outcome.outcome)
        assertEquals("room-9", outcome.roomId)
        assertEquals("child-1", outcome.childConvoId)
        assertNull(outcome.errorCode)
        assertEquals("🚀 Spawned session started", outcome.displayLine)
    }

    @Test
    fun parsesDeclined() {
        val outcome = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"declined"}"""))!!
        assertEquals("🚫 Spawn declined", outcome.displayLine)
        assertNull(outcome.roomId)
    }

    @Test
    fun parsesExpired() {
        val outcome = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"expired"}"""))!!
        assertEquals("⌛ Spawn request expired", outcome.displayLine)
    }

    @Test
    fun parsesFailedWithErrorCode() {
        val outcome = SpawnOutcome.parse(
            payload("""{"request_id":"spawn-1","outcome":"failed","error_code":"agent_unreachable"}"""),
        )!!
        assertEquals("agent_unreachable", outcome.errorCode)
        assertEquals("❌ Spawn failed — agent_unreachable", outcome.displayLine)
    }

    @Test
    fun parsesFailedWithoutAnErrorCode() {
        val outcome = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"failed"}"""))!!
        assertNull(outcome.errorCode)
        assertEquals("❌ Spawn failed", outcome.displayLine)
    }

    /// A journal running ahead of this client must not crash on an outcome
    /// string it doesn't recognise — it resolves the card with generic copy
    /// instead.
    @Test
    fun unknownOutcomeParsesWithGenericDisplayLine() {
        val outcome = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"orphaned"}"""))!!
        assertEquals("orphaned", outcome.outcome)
        assertEquals("Spawn request resolved", outcome.displayLine)
    }

    @Test
    fun rejectsMissingRequestId() {
        assertNull(SpawnOutcome.parse(payload("""{"outcome":"started"}""")))
        assertNull(SpawnOutcome.parse(payload("""{"request_id":"","outcome":"started"}""")))
    }

    @Test
    fun rejectsMissingOutcome() {
        assertNull(SpawnOutcome.parse(payload("""{"request_id":"spawn-1"}""")))
        assertNull(SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":""}""")))
    }

    /// [SpawnOutcome.openRoomId] backs the "Open" action on both the
    /// resolved card (`AgentSpawnRequestCard`'s `ResolvedRow`) and the
    /// `SpawnOutcomeRow` timeline row — present only for `started`, even
    /// though `roomId` is a plain nullable field that could in principle
    /// carry a value alongside any outcome string.
    @Test
    fun openRoomIdIsPresentOnlyForStarted() {
        val started = SpawnOutcome.parse(
            payload("""{"request_id":"spawn-1","outcome":"started","room_id":"room-9"}"""),
        )!!
        assertEquals("room-9", started.openRoomId)

        val declined = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"declined"}"""))!!
        assertNull(declined.openRoomId)
    }

    @Test
    fun openRoomIdIsNullWhenStartedCarriesNoRoomId() {
        val outcome = SpawnOutcome.parse(payload("""{"request_id":"spawn-1","outcome":"started"}"""))!!
        assertNull(outcome.openRoomId)
    }

    /// `baseSnippet` is the journal server's own `snippetOf` string, byte
    /// -exact: no errorCode suffix on `failed`, `"[spawn_outcome]"` (not
    /// `displayLine`'s neutral "resolved") for an outcome it doesn't
    /// recognise. `JournalStore`'s chat-list snippet must use this, not
    /// `displayLine`, so a snapshot row (server-minted) and a live-mapped
    /// frame never disagree.
    @Test
    fun baseSnippetIsTheServersBareMappingNotDisplayLine() {
        assertEquals("🚀 Spawned session started", SpawnOutcome.baseSnippet("started"))
        assertEquals("🚫 Spawn declined", SpawnOutcome.baseSnippet("declined"))
        assertEquals("⌛ Spawn request expired", SpawnOutcome.baseSnippet("expired"))
        assertEquals("❌ Spawn failed", SpawnOutcome.baseSnippet("failed"))
        assertEquals("[spawn_outcome]", SpawnOutcome.baseSnippet("orphaned"))
    }
}
