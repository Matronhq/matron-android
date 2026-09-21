package chat.matron.android.viewmodels

import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.MissionsStoreReading
import chat.matron.android.journal.MissionsSyncing
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.SessionTagInputs
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs one mission page (spec: Apps → Missions tab → Page). Reads flow
/// from the local store's flows; the single write — the user's close — goes
/// through [MissionsSyncing]. Ported from matron-apple's
/// `MissionDetailViewModel`.
class MissionDetailViewModel(
    val missionID: String,
    private val store: MissionsStoreReading,
    private val sync: MissionsSyncing,
    private val scope: CoroutineScope,
) {
    private val _mission = MutableStateFlow<Mission?>(null)
    val mission: StateFlow<Mission?> = _mission.asStateFlow()

    /// Newest first, already filtered by [showOnlyUserInput].
    private val _milestones = MutableStateFlow<List<Milestone>>(emptyList())
    val milestones: StateFlow<List<Milestone>> = _milestones.asStateFlow()

    /// "My inputs only" — the toggle that turns the page into a list of the
    /// user's own redirections.
    private val _showOnlyUserInput = MutableStateFlow(false)
    val showOnlyUserInput: StateFlow<Boolean> = _showOnlyUserInput.asStateFlow()

    /// Open items in this mission, awaiting-you first (the store's order).
    private val _openItems = MutableStateFlow<List<TrackerItem>>(emptyList())
    val openItems: StateFlow<List<TrackerItem>> = _openItems.asStateFlow()

    private val _conversations = MutableStateFlow<List<MissionConversation>>(emptyList())
    val conversations: StateFlow<List<MissionConversation>> = _conversations.asStateFlow()

    /// The `A:bc` tag halves for every conversation the milestones name,
    /// keyed by conversation id — a mission spans several sessions, so each
    /// row says which one it came from. A conversation this device has not
    /// cached has no entry, and its rows render untagged. Rebuilt whenever
    /// the milestone list changes.
    private val _sessionTags = MutableStateFlow<Map<String, SessionTagInputs>>(emptyMap())
    val sessionTags: StateFlow<Map<String, SessionTagInputs>> = _sessionTags.asStateFlow()

    private val _closeSummaryDraft = MutableStateFlow("")
    val closeSummaryDraft: StateFlow<String> = _closeSummaryDraft.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /// Unfiltered, as the store delivered it — [applyFilter] derives
    /// [milestones] from this, so toggling the filter needs no refetch.
    private var allMilestones: List<Milestone> = emptyList()
    private val jobs = mutableListOf<Job>()
    private var refreshJob: Job? = null

    fun setShowOnlyUserInput(value: Boolean) {
        if (_showOnlyUserInput.value == value) return
        _showOnlyUserInput.value = value
        applyFilter()
    }

    fun setCloseSummaryDraft(value: String) {
        _closeSummaryDraft.value = value
    }

    fun dismissError() {
        _error.value = null
    }

    private fun applyFilter() {
        _milestones.value = filtered(allMilestones, _showOnlyUserInput.value)
    }

    /// One batch read for every DISTINCT conversation in the unfiltered
    /// list, so toggling "My inputs only" costs nothing and a 40-milestone
    /// mission posted in three sessions does three conversation reads.
    private suspend fun refreshSessionTags() {
        _sessionTags.value = runCatching { store.sessionTags(allMilestones.map { it.convoID }.toSet()) }
            .getOrDefault(emptyMap())
    }

    fun start() {
        stop()
        val id = missionID
        jobs += scope.launch { store.missionFlow(id).collect { _mission.value = it } }
        jobs += scope.launch {
            store.milestonesFlow(id).collect { list ->
                allMilestones = list
                applyFilter()
                refreshSessionTags()
            }
        }
        jobs += scope.launch { store.missionItemsFlow(id).collect { _openItems.value = it } }
        jobs += scope.launch { store.missionConversationsFlow(id).collect { _conversations.value = it } }
        // Conversations and the full milestone list only reach the local
        // cache through a detail fetch — opening the page must trigger one.
        refreshJob = scope.launch { refresh() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        refreshJob?.cancel(); refreshJob = null
    }

    /// A failed refresh sets [error] — the same alert plumbing [close]
    /// already feeds — so a cold open with nothing cached while the journal
    /// is unreachable surfaces a retryable message instead of dead-ending on
    /// the "not on this device yet" placeholder forever. A success clears a
    /// stale banner from an earlier failure.
    suspend fun refresh() {
        when (val outcome = sync.refreshMission(missionID)) {
            MissionsRefreshOutcome.Succeeded -> _error.value = null
            is MissionsRefreshOutcome.Failed -> _error.value = outcome.message
            MissionsRefreshOutcome.Unsupported, MissionsRefreshOutcome.Stopped -> Unit
        }
    }

    /// The user's close. Always permitted server-side, even over open items
    /// — the journal records the override and the close marker names the
    /// numbers. The host shows a confirmation first
    /// (`missionCloseConfirmationTitle`) naming how many items stay open.
    suspend fun close() {
        val summary = _closeSummaryDraft.value.trim()
        if (summary.isEmpty()) {
            _error.value = "Write a short summary before closing the mission."
            return
        }
        _isBusy.value = true
        try {
            sync.closeMission(missionID, summary)
            _closeSummaryDraft.value = ""
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        } finally {
            _isBusy.value = false
        }
    }

    companion object {
        fun filtered(milestones: List<Milestone>, showOnlyUserInput: Boolean): List<Milestone> =
            if (showOnlyUserInput) milestones.filter { it.kind == MilestoneKind.USER_INPUT } else milestones
    }
}
