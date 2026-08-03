package chat.matron.android.features.chat

import chat.matron.android.AppDependencies
import chat.matron.android.models.BotCommandCatalog
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.SubChatStripViewModel
import kotlinx.coroutines.CoroutineScope

/**
 * Bounded per-room cache of (ChatViewModel, ComposerViewModel) pairs plus the
 * shared per-parent running-subagent strip VMs. Ports the `ChatVMCache` from
 * Features/ChatList/ChatListView.swift: LRU-bounded at 8 so a session that visits
 * many rooms doesn't pin every timeline's items forever, and so a remount of a
 * pushed chat screen reuses the same VMs instead of rebooting the timeline.
 *
 * All VMs share the session-lifetime [scope] (cancelled on sign-out); an evicted
 * chat VM is additionally `stop()`ped to release its observation, matching iOS.
 */
class ChatVMCache(
    private val deps: AppDependencies,
    private val session: UserSession,
    private val scope: CoroutineScope,
) {
    private val entries = LinkedHashMap<String, Pair<ChatViewModel, ComposerViewModel>>()
    private val stripEntries = mutableMapOf<String, SubChatStripViewModel>()
    private val limit = 8

    /** The (chat, composer) VM pair for [roomID], created and cached on first use. */
    fun viewModels(roomID: String): Pair<ChatViewModel, ComposerViewModel> {
        entries.remove(roomID)?.let { cached ->
            entries[roomID] = cached // move to MRU end
            return cached
        }
        val timeline = deps.timelineService(session, roomID)
        val media = deps.mediaService(session)
        val pair = ChatViewModel(
            roomID = roomID,
            timeline = timeline,
            media = media,
            scope = scope,
            answeredPromptStore = deps.preferences,
            haptics = deps.haptics,
        ) to ComposerViewModel(
            roomID = roomID,
            timeline = timeline,
            commands = BotCommandCatalog.claudeBridge,
            recentFolders = deps.recentStartFolders,
            stagingDirectory = deps.stagingDirectory,
        )
        entries[roomID] = pair
        if (entries.size > limit) {
            val eldest = entries.keys.first()
            entries.remove(eldest)?.first?.stop()
        }
        return pair
    }

    /** The shared running-subagent strip VM for a parent conversation. */
    fun stripViewModel(parentConvoID: String): SubChatStripViewModel =
        stripEntries.getOrPut(parentConvoID) {
            SubChatStripViewModel(
                chat = deps.chatService(session),
                parentConvoID = parentConvoID,
                scope = scope,
            )
        }

    /** The (read-only timeline VM, switcher strip VM) pair for a subagent child. */
    fun subChatViewModels(childID: String, parentConvoID: String): Pair<ChatViewModel, SubChatStripViewModel> =
        viewModels(childID).first to stripViewModel(parentConvoID)
}
