package chat.matron.android.viewmodels

import android.util.Log
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.models.UsageHistory
import chat.matron.android.models.UsageHistoryTable
import chat.matron.android.models.UsageMeterChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/// What the usage history reads.
interface UsageHistoryProviding {
    suspend fun usageHistory(weeks: Int): UsageHistory
}

/// Production adapter over the session's [JournalApi].
class JournalUsageHistoryService(private val api: JournalApi) : UsageHistoryProviding {
    override suspend fun usageHistory(weeks: Int): UsageHistory = api.usageHistory(weeks)
}

/// The Boxes screen's usage history: per account, how much of each week's
/// allowance went unused. Read once when shown and again on a pull; it
/// changes once a week per account. Port of matron-apple's
/// `UsageHistoryViewModel`.
class UsageHistoryViewModel(private val api: UsageHistoryProviding) {
    data class State(
        val history: UsageHistory? = null,
        val hasLoaded: Boolean = false,
        val errorMessage: String? = null,
        /// The chosen meter's id ([UsageMeterChoice.id]).
        val choiceID: String = UsageMeterChoice.PRIMARY_ID,
    ) {
        val choices: List<UsageMeterChoice> get() = history?.let(UsageHistoryTable::choices).orEmpty()

        /// The chosen meter, falling back to the headline one if the choice
        /// has gone (its last account dropped out of the window).
        val choice: UsageMeterChoice get() = choices.firstOrNull { it.id == choiceID } ?: UsageMeterChoice.primary

        fun table(nowMs: Long = System.currentTimeMillis()): UsageHistoryTable =
            UsageHistoryTable.build(history ?: UsageHistory(emptyList()), choice, nowMs = nowMs)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load() {
        try {
            val history = api.usageHistory(UsageHistoryTable.DEFAULT_WEEKS)
            _state.update { held ->
                val loaded = held.copy(history = history, hasLoaded = true, errorMessage = null)
                // A meter that dropped out of the window leaves the picker on
                // the headline one, as the table already shows.
                if (loaded.choices.none { it.id == loaded.choiceID }) loaded.copy(choiceID = UsageMeterChoice.PRIMARY_ID) else loaded
            }
        } catch (cancel: CancellationException) {
            // The screen went away mid-read: not a failure to show.
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            _state.update {
                it.copy(hasLoaded = true, errorMessage = "This journal doesn't keep usage history yet. It needs an update.")
            }
        } catch (error: Throwable) {
            runCatching { Log.e(TAG, "usage history load failed", error) }
            _state.update { it.copy(hasLoaded = true, errorMessage = "Couldn't load the usage history. Try again.") }
        }
    }

    fun choose(choiceID: String) = _state.update { it.copy(choiceID = choiceID) }

    private companion object {
        const val TAG = "UsageHistoryViewModel"
    }
}
