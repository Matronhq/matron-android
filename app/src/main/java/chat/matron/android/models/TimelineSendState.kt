package chat.matron.android.models

/// Send-state for an own-message timeline row.
sealed interface TimelineSendState {
    data object Sent : TimelineSendState
    data object Sending : TimelineSendState
    data class Failed(val reason: String) : TimelineSendState
}
