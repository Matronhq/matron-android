package chat.matron.android

import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// The bottom tabs (app shell, spec §3), left to right in the bar — and
/// `entries` order is the swipe order too ([AppShellNavigation.swipeRoot]).
/// The app opens on Conversations. [route] names the tab's nested
/// navigation graph; [rootRoute] its root destination (the only place the
/// bar shows).
enum class AppTab(val route: String, val rootRoute: String, val label: String) {
    CONVERSATIONS("conversations", "chats", "Conversations"),
    DECISIONS("decisions", "decisions/list", "Decisions"),
}

/// The signed-in shell's navigation rules, ported from matron-apple's
/// `AppShellNavigation`. Apple's object IS the two `NavigationStack` paths;
/// on Android the `NavController` owns the back stacks, so this class keeps
/// a mirror of them ([chatPath] / [decisionsPath], fed by [noteDestination]
/// from the host's destination-changed listener) and expresses every
/// cross-tab rule as plain, testable state changes plus [Host] commands the
/// Compose shell executes on the controller. Tests construct it without a
/// host (or with a recording one) and set the paths directly, as Apple's do.
///
/// Path values: a bare room id for a chat (Apple's `[String]` path, where
/// `ChatSummary.ID == String`), `item/<id>` for an item detail (Apple's
/// `ItemRoute.pathValue`), and the route itself for anything else pushed
/// on the Conversations stack (`items/<convo>`, `search`, `settings`, …).
class AppShellNavigation(var host: Host? = null) {

    /// What the shell does to the `NavController` for each rule below. Kept
    /// as an interface so the rules stay a pure function of this object.
    interface Host {
        /// Select [tab], restoring its saved stack (the idiomatic
        /// `saveState` / `restoreState` tab switch).
        fun switchTab(tab: AppTab)
        /// REPLACE the Conversations stack with [roomID] over the list.
        fun replaceChats(roomID: String)
        /// Push [roomID] on the Conversations stack.
        fun pushChat(roomID: String)
        /// Push item [itemID]'s detail on the Decisions stack.
        fun pushDecision(itemID: String)
        /// Pop [tab]'s stack back to its root (re-tapping the selected tab).
        fun popToRoot(tab: AppTab)
    }

    private val _tab = MutableStateFlow(AppTab.CONVERSATIONS)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    /// Conversations tab stack (see the class doc for the value shapes).
    var chatPath: List<String> = emptyList()
    /// Decisions tab stack: `item/<id>` values.
    var decisionsPath: List<String> = emptyList()

    /// The controller's entry ids, parallel to each path, so a destination
    /// change can be told apart as a push (new id) or a pop (known id).
    private val entryIDs: MutableMap<AppTab, MutableList<String>> =
        AppTab.entries.associateWith { mutableListOf<String>() }.toMutableMap()

    /// The path value a rule below has just navigated to, per tab, so the
    /// mirror stays idempotent (Bugbot, #75): the rule updates the path
    /// FIRST and the controller then reports the same destination as a new
    /// entry — without this token [noteDestination] would append it a
    /// second time (`[id, id]`), the sole-open-chat check would stop
    /// matching, and a repeated `openChat` for the same room would remount
    /// the screen. A matching report binds the entry id to the existing
    /// top instead; the token is one-shot.
    private val expected: MutableMap<AppTab, String?> = mutableMapOf()

    /// Records [pathValue] as the destination the controller is about to
    /// report for [tab], then runs the host command that navigates there.
    private fun navigateExpecting(tab: AppTab, pathValue: String, command: () -> Unit) {
        expected[tab] = pathValue
        command()
    }

    /// Open a top-level conversation by REPLACING the whole Conversations
    /// path, never appending: notification taps, search results and
    /// auto-opened new conversations used to stack chat-on-chat. Back from
    /// a conversation always returns to the chat list (Dan, 2026-08-06).
    /// No-op on the path when the target is already the sole open chat.
    fun openChat(roomID: String) {
        selectTabInternal(AppTab.CONVERSATIONS)
        if (chatPath != listOf(roomID)) {
            chatPath = listOf(roomID)
            navigateExpecting(AppTab.CONVERSATIONS, roomID) { host?.replaceChats(roomID) }
        }
    }

    /// "Open conversation" from a Decisions row or its detail: switch to
    /// Conversations first, then push, in that order so the push lands in
    /// the visible stack (spec §3). The Decisions stack is left where it was.
    fun openConversationFromDecisions(convoID: String) {
        selectTabInternal(AppTab.CONVERSATIONS)
        if (chatPath.lastOrNull() != convoID) {
            chatPath = chatPath + convoID
            navigateExpecting(AppTab.CONVERSATIONS, convoID) { host?.pushChat(convoID) }
        }
    }

    /// Push an item's detail on the Decisions stack. Never changes the tab.
    fun pushDecision(itemID: String) {
        val value = itemRoute(itemID)
        decisionsPath = decisionsPath + value
        navigateExpecting(AppTab.DECISIONS, value) { host?.pushDecision(itemID) }
    }

    /// A bar tap: select [tab], or pop the already-selected tab to its root
    /// (the SwiftUI default for a `NavigationStack` tab).
    fun selectTab(tab: AppTab) {
        if (tab == _tab.value) {
            if (!isAtRoot) host?.popToRoot(tab)
            return
        }
        selectTabInternal(tab)
    }

    /// Whether the selected tab is showing its root (nothing pushed).
    val isAtRoot: Boolean
        get() = path(_tab.value).isEmpty()

    /// Dan, 2026-09-09: swipe between the conversation list and the
    /// decisions list. A mostly horizontal drag past 80dp at a tab's ROOT
    /// moves one tab in bar order (left = next, right = previous). Deeper
    /// in a stack the chat and the item detail own horizontal drags, so a
    /// non-empty path ignores it. Returns whether the tab changed.
    fun swipeRoot(dx: Float, dy: Float): Boolean {
        if (!isAtRoot || abs(dx) <= SWIPE_THRESHOLD_DP || abs(dx) <= abs(dy)) return false
        val index = AppTab.entries.indexOf(_tab.value)
        val next = if (dx < 0) index + 1 else index - 1
        if (next !in AppTab.entries.indices) return false
        selectTabInternal(AppTab.entries[next])
        return true
    }

    /// Host feedback: the controller's current destination changed. [tab]
    /// is the graph it belongs to, [entryID] the controller's back-stack
    /// entry id and [pathValue] the value to mirror — `null` for a tab's
    /// root, which empties that tab's path. A known entry id is a pop
    /// (truncate after it); a new one is a push (append). The tab follows
    /// the destination so a system back out of Decisions into
    /// Conversations reselects the right item in the bar.
    fun noteDestination(tab: AppTab, entryID: String, pathValue: String?) {
        _tab.value = tab
        val ids = entryIDs.getValue(tab)
        if (pathValue == null) {
            ids.clear()
            setPath(tab, emptyList())
            return
        }
        val current = path(tab)
        val index = ids.indexOf(entryID)
        val pending = expected.remove(tab)
        when {
            index >= 0 && index < current.size -> {
                while (ids.size > index + 1) ids.removeAt(ids.size - 1)
                setPath(tab, current.take(index + 1))
            }
            pending != null && pending == pathValue && pathValue == current.lastOrNull() -> {
                // The rule already put this value on top; bind the entry id
                // to it rather than appending a duplicate.
                while (ids.size > current.size - 1) ids.removeAt(ids.size - 1)
                ids.add(entryID)
            }
            else -> {
                ids.add(entryID)
                setPath(tab, current + pathValue)
            }
        }
    }

    private fun selectTabInternal(tab: AppTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        host?.switchTab(tab)
    }

    private fun path(tab: AppTab): List<String> = when (tab) {
        AppTab.CONVERSATIONS -> chatPath
        AppTab.DECISIONS -> decisionsPath
    }

    private fun setPath(tab: AppTab, value: List<String>) {
        when (tab) {
            AppTab.CONVERSATIONS -> chatPath = value
            AppTab.DECISIONS -> decisionsPath = value
        }
    }

    companion object {
        /// Apple's 80pt, in dp.
        const val SWIPE_THRESHOLD_DP = 80f

        /// Apple's `ItemRoute.pathValue`.
        fun itemRoute(itemID: String): String = "item/$itemID"

        /// The path value a `NavController` destination mirrors to
        /// ([noteDestination]), from its route pattern and argument lookup:
        /// `null` for a tab root, the bare room id for a chat, `item/<id>`
        /// for either item-detail route, and the route with its argument
        /// filled in for everything else.
        fun pathValue(route: String?, argument: (String) -> String?): String? = when (route) {
            null -> null
            AppTab.CONVERSATIONS.rootRoute, AppTab.DECISIONS.rootRoute -> null
            "chat/{convoID}" -> argument("convoID")
            "item/{itemID}", "decision/{itemID}" -> argument("itemID")?.let(::itemRoute)
            else -> Regex("\\{([^}]+)\\}").replace(route) { m -> argument(m.groupValues[1]) ?: m.value }
        }
    }
}
