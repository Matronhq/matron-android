package chat.matron.android.models

/// Send-state for an own-message timeline row.
sealed interface TimelineSendState {
    data object Sent : TimelineSendState
    data object Sending : TimelineSendState
    /// Durably queued in the offline outbox — will send automatically when a
    /// connection is available. Distinct from [Sending] so the UI can be
    /// honest that nothing is in flight yet.
    data object Queued : TimelineSendState
    data class Failed(val reason: String) : TimelineSendState
}
