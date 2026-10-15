package chat.matron.android.journal

import java.util.UUID

/// Stops one session: the badge's **Stop session** on a spawn card the
/// Coordinator approved. It sends `/stop` into the spawned session's own
/// conversation, the same command the user would type there, through the
/// outbox, so it survives being offline. A slice, like [AgentSpawnAnswering],
/// so the chat view model can stop a session and nothing else. Ported from
/// matron-apple's `SessionStopping`.
fun interface SessionStopping {
    suspend fun stopSession(convoID: String)
}

/// [SessionStopping] over the sync engine's outbox.
class OutboxSessionStopper(private val engine: JournalSyncEngine) : SessionStopping {
    override suspend fun stopSession(convoID: String) {
        engine.sendMessage(convoID = convoID, body = "/stop", localID = UUID.randomUUID().toString())
    }
}
