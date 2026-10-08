package chat.matron.android.models

/// User-facing rendering of the socket's connection state.
///
/// `Connecting` covers the "no signal yet" window so the user sees a banner
/// instead of a silently empty list while the socket warms up. `Running` is the
/// steady state (banner hides). `Offline` means we're not currently exchanging
/// data with the server, and carries a reason to render in a banner while a
/// reconnect is in flight.
sealed interface SyncConnectionState {
    data object Connecting : SyncConnectionState

    /// Socket is up and a reconnect backlog is replaying into the mirror.
    /// Distinct from [Connecting] so hosts can say "Loading messages…" —
    /// progress, not a stalled connection (port of matron-apple #84).
    data object CatchingUp : SyncConnectionState

    data object Running : SyncConnectionState
    data class Offline(val reason: String?) : SyncConnectionState
}
