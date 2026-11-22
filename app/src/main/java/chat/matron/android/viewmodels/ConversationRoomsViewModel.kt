package chat.matron.android.viewmodels

import chat.matron.android.models.ConversationRoom
import chat.matron.android.models.ConversationRoomsRule
import chat.matron.android.models.MissionRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Drives a chat header's "Rooms · n" control: the agent-chat rooms ONE
/// conversation takes part in ([ConversationRoomsRule]), live off the store's
/// rooms stream. The stream carries every room and re-emits on any room's
/// activity, so [rooms] only changes when this conversation's own list does
/// (a `StateFlow` drops equal values). Port of matron-apple's
/// `ConversationRoomsViewModel`.
///
/// Same lifecycle as [SubChatStripViewModel]: hosts record
/// [observationGeneration] after [start] and tear down with
/// `stop(generation)`, so a stale screen's disposal cannot end a successor's
/// observation.
class ConversationRoomsViewModel(
    val convoID: String,
    private val source: () -> Flow<List<MissionRoom>>,
    private val scope: CoroutineScope,
) {
    private val _rooms = MutableStateFlow<List<ConversationRoom>>(emptyList())
    val rooms: StateFlow<List<ConversationRoom>> = _rooms.asStateFlow()

    private var observationTask: Job? = null

    var observationGeneration: Int = 0
        private set

    fun start(): Job {
        observationGeneration += 1
        observationTask?.cancel()
        val task = scope.launch {
            source().collect { all -> _rooms.value = ConversationRoomsRule.rooms(of = convoID, among = all) }
        }
        observationTask = task
        return task
    }

    fun stop(generation: Int) {
        if (generation == observationGeneration) stop()
    }

    fun stop() {
        observationTask?.cancel()
        observationTask = null
    }
}
