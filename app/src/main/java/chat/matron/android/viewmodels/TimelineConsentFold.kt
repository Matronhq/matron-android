package chat.matron.android.viewmodels

import chat.matron.android.chat.TimelineItem
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.ConsentDecisionEvent
import chat.matron.android.events.ItemMarkerEvent

/**
 * Reads durable consent decisions and keeps one card per tracker item.
 * Port of the Apple client's TimelineConsentFold, over the whole loaded
 * snapshot before the display window is applied. Reply/reopen notes remain
 * separate events; the surviving card uses the latest marker's state.
 */
internal data class TimelineConsentFold(
    val chatResolutions: Map<String, AgentChatCardState>,
    /// The Coordinator's answer to a chat or spawn card, keyed by the card's
    /// event id: what the card's "Approved by the Coordinator — <reason>"
    /// badge reads.
    val coordinatorDecisions: Map<String, ConsentDecisionEvent>,
    val hiddenItemRows: Set<String>,
    val restatedItemMarkers: Map<String, ItemMarkerEvent>,
) {
    companion object {
        fun read(items: List<TimelineItem>): TimelineConsentFold {
            val resolutions = mutableMapOf<String, AgentChatCardState>()
            val coordinatorDecisions = mutableMapOf<String, ConsentDecisionEvent>()
            val hidden = mutableSetOf<String>()
            val latestChatCard = mutableMapOf<String, String>()
            // The latest spawn card per request id, for the same reason as
            // latestChatCard: a decision is always about the newest card.
            val latestSpawnCard = mutableMapOf<String, String>()
            val unpaired = linkedSetOf<String>()
            val cardForItem = mutableMapOf<String, String>()
            val itemsNamingTheirAsk = mutableSetOf<String>()
            val lastItemCard = mutableMapOf<String, Pair<String, ItemMarkerEvent>>()
            val latestItemMarker = mutableMapOf<String, ItemMarkerEvent>()

            for (item in items) {
                when (val kind = item.kind) {
                    is TimelineItem.Kind.AgentChatRequestCard -> {
                        latestChatCard[kind.request.askID] = kind.eventID
                        unpaired.add(kind.eventID)
                    }
                    is TimelineItem.Kind.AgentSpawnRequestCard -> {
                        latestSpawnCard[kind.request.requestId] = kind.eventID
                    }
                    is TimelineItem.Kind.ConsentDecision -> {
                        val decision = kind.decision
                        when (decision.kind) {
                            ConsentDecisionEvent.Kind.CHAT -> latestChatCard[decision.askID]?.let { card ->
                                resolutions[card] = AgentChatCardState.Answered(
                                    decision.decision == ConsentDecisionEvent.Decision.APPROVE,
                                    byCoordinator = true,
                                )
                                coordinatorDecisions[card] = decision
                            }
                            ConsentDecisionEvent.Kind.SPAWN -> latestSpawnCard[decision.askID]?.let { card ->
                                coordinatorDecisions[card] = decision
                            }
                        }
                    }
                    is TimelineItem.Kind.ItemMarker -> {
                        val marker = kind.marker
                        if (marker.consent == "chat") {
                            when (marker.action) {
                                ItemMarkerEvent.Action.CREATED -> {
                                    // A named marker must not claim an older, already
                                    // paired card when its own card is outside history.
                                    val card = if (marker.consentAsk != null) {
                                        latestChatCard[marker.consentAsk]?.takeIf { it in unpaired }
                                    } else {
                                        unpaired.lastOrNull()
                                    }
                                    if (card != null) {
                                        cardForItem[marker.itemID] = card
                                        if (marker.consentAsk != null) itemsNamingTheirAsk.add(marker.itemID)
                                        unpaired.remove(card)
                                    }
                                }
                                ItemMarkerEvent.Action.CLOSED -> {
                                    // Prefer the item's original card: a delayed sweep
                                    // of an old item must not settle a renewed request.
                                    val card = cardForItem[marker.itemID]
                                        ?: latestChatCard[marker.consentAsk]?.takeIf { it in unpaired }
                                    val previous = resolutions[card]
                                    val manualClose = marker.itemID in itemsNamingTheirAsk &&
                                        marker.consentAsk == null && marker.consentOutcome == null
                                    if (card != null && !manualClose &&
                                        (previous as? AgentChatCardState.Answered)?.byCoordinator != true
                                    ) {
                                        val resolved = chatResolution(marker)
                                        // A later sweep of a reopened mirror can't undo
                                        // an actual decision or its Coordinator badge.
                                        if (resolved != null && !(previous is AgentChatCardState.Answered &&
                                                resolved == AgentChatCardState.Expired)) {
                                            resolutions[card] = resolved
                                        }
                                    }
                                }
                                else -> Unit
                            }
                        }
                        latestItemMarker[marker.itemID] = marker
                        if (marker.action == ItemMarkerEvent.Action.CREATED || marker.action == ItemMarkerEvent.Action.CLOSED) {
                            lastItemCard.put(marker.itemID, item.id to marker)?.let { hidden.add(it.first) }
                        }
                    }
                    else -> Unit
                }
            }

            val restated = mutableMapOf<String, ItemMarkerEvent>()
            for ((itemID, card) in lastItemCard) {
                val latest = latestItemMarker.getValue(itemID)
                val updated = card.second.restated(latest)
                if (updated != card.second) restated[card.first] = updated
            }
            return TimelineConsentFold(resolutions, coordinatorDecisions, hidden, restated)
        }

        private fun chatResolution(marker: ItemMarkerEvent): AgentChatCardState? {
            val note = marker.comment?.body.orEmpty()
            val byCoordinator = marker.decidedBy == "coordinator" ||
                note.startsWith("Approved by the Coordinator") || note.startsWith("Declined by the Coordinator")
            val approved = if (marker.consentAsk != null || marker.consentOutcome != null) {
                when (marker.consentOutcome) {
                    "approved" -> true
                    "denied" -> false
                    null -> return null
                    else -> return AgentChatCardState.Expired
                }
            } else {
                // Older journals recorded outcomes only in fixed closing notes.
                // Other notes are manual item closes, which don't answer the ask.
                when {
                    note.startsWith("Approved") -> true
                    note.startsWith("Declined") -> false
                    listOf("Expired", "The room was closed", "Closed — ").any(note::startsWith) ->
                        return AgentChatCardState.Expired
                    else -> return null
                }
            }
            return AgentChatCardState.Answered(approved, byCoordinator)
        }
    }
}
