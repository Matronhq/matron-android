package chat.matron.android.journal

/// Session-state wire values (`ConvoSummaryDTO.sessionState` /
/// `ConversationEntity.sessionState` / `session_status` payload `state`). The
/// DB column and DTOs stay raw `String` — schema parity with matron-apple, and
/// an unrecognized state (e.g. a future server addition) must round-trip
/// unchanged rather than being coerced or dropped. This type exists only to
/// name the two known wire values so call sites stop repeating bare literals.
sealed class SessionState {
    data object Running : SessionState()
    data object Done : SessionState()
    data class Other(val raw: String) : SessionState()

    val wire: String
        get() = when (this) {
            Running -> RUNNING
            Done -> DONE
            is Other -> raw
        }

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"

        fun fromWire(raw: String): SessionState = when (raw) {
            RUNNING -> Running
            DONE -> Done
            else -> Other(raw)
        }
    }
}
