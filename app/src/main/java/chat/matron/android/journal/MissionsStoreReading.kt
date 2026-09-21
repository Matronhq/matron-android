package chat.matron.android.journal

import chat.matron.android.models.Milestone
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.SessionTagInputs
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.flow.Flow

/// The store reads the missions surfaces need, as an interface so tests fake
/// the store (mirrors [ItemsStoreReading]; the Swift original declares the
/// conformance in the ViewModels module, which Kotlin can't do
/// retroactively, so [JournalStore] implements it directly).
interface MissionsStoreReading {
    fun missionsFlow(state: MissionState?): Flow<List<Mission>>
    fun missionFlow(id: String): Flow<Mission?>
    fun milestonesFlow(missionID: String): Flow<List<Milestone>>
    /// The mission's OPEN items, awaiting-you first (the store's order).
    fun missionItemsFlow(missionID: String): Flow<List<TrackerItem>>
    fun missionConversationsFlow(missionID: String): Flow<List<MissionConversation>>
    /// The `A:bc` tag halves for several conversations, keyed by id, with
    /// no entry for a conversation this device has no cached row for (a
    /// milestone can name a conversation that has never synced here — it
    /// renders untagged). The box roster and letter overrides are read ONCE
    /// for the whole batch, not once per conversation — a mission page
    /// re-derives every tag on every milestone-stream emission.
    suspend fun sessionTags(convoIDs: Set<String>): Map<String, SessionTagInputs>
}
