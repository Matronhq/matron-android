package chat.matron.android.features.missions

/// What a mission page hosted on a CHAT tab's stack (the Conversations or
/// Coordinator tab — reached by a title tap or a milestone card) does when
/// it is asked to open a conversation, including under a milestone jump.
/// Ported from apple #209's `CoordinatorTabView.missionOpenConversationOutcome`
/// and `ChatListView.missionDestination`'s pop rule (Bugbot: a same-room
/// target used to no-op, leaving the page on screen while the jump had
/// already fired underneath it).
sealed interface MissionOpenConversationOutcome {
    /// The target is the chat the page was opened from: pop the page to
    /// reveal it (for the coordinator root, that lands on the root).
    data object PopMission : MissionOpenConversationOutcome

    /// The target is the coordinator's own room while some other chat sits
    /// underneath: clear the Coordinator stack to its root rather than
    /// stack a second copy.
    data object ClearToRoot : MissionOpenConversationOutcome

    /// Any other room: push it on top of the page.
    data class Push(val convoID: String) : MissionOpenConversationOutcome
}

/// [current] is the chat beneath the mission page (`null` when nothing is
/// beneath it but a tab root — the coordinator root chat is then
/// [coordinatorConvoID] itself).
fun missionOpenConversationOutcome(target: String, current: String?, coordinatorConvoID: String?): MissionOpenConversationOutcome {
    val beneath = current ?: coordinatorConvoID
    return when {
        target == beneath -> MissionOpenConversationOutcome.PopMission
        target == coordinatorConvoID -> MissionOpenConversationOutcome.ClearToRoot
        else -> MissionOpenConversationOutcome.Push(target)
    }
}
