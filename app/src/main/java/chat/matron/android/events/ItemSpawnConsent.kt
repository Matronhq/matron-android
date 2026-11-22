package chat.matron.android.events

/// What an item page needs to draw the spawn consent card for a consent item:
/// the request id from the item's `matron://consent/spawn/<id>` link, the
/// card's own facts when its `permission_request` event is in the local
/// store, and where the ask is in its life.
///
/// [request] is null when the card event has not reached this device yet (a
/// consent item opened from a notification before its conversation synced).
/// Such an ask is drawn as waiting for its card and is NOT answerable: never
/// rebuilt from the item's text, and never answered on the request id alone.
/// The id comes from a link any agent can write into any item, so what the
/// user approves must be the card's own payload, or nothing.
data class ItemSpawnConsent(
    val requestID: String,
    val request: AgentSpawnRequest?,
    val state: AgentSpawnCardState,
)
