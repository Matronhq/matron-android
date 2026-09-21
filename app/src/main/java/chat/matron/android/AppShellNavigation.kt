package chat.matron.android

import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// The bottom tabs (app shell, spec §3), left to right in the bar — and
/// `entries` order is the swipe order too ([AppShellNavigation.swipeRoot]).
/// The app opens on Conversations. [route] names the tab's nested
/// navigation graph; [rootRoute] its root destination (the only place the
/// bar shows); [routePrefix] prefixes the chat / tasks / item routes a tab
/// hosts on its own stack (the Coordinator tab keeps its own copies so a
/// sub-chat opened from the coordinator pushes there, not in Conversations).
///
/// Order (apple #209): Coordinator · Missions · Decisions · Conversations.
/// The Missions tab is in the bar only while the journal is known to
/// support `/missions` — see [AppShellNavigation.missionsSupported].
enum class AppTab(val route: String, val rootRoute: String, val label: String, val routePrefix: String) {
    COORDINATOR("coordinator", "coordinator/root", "Coordinator", "coordinator/"),
    MISSIONS("missions", "missions/list", "Missions", "missions/"),
    DECISIONS("decisions", "decisions/list", "Decisions", "decisions/"),
    CONVERSATIONS("conversations", "chats", "Conversations", ""),
}

/// The signed-in shell's navigation rules, ported from matron-apple's
/// `AppShellNavigation`. Apple's object IS the `NavigationStack` paths; on
/// Android the `NavController` owns the back stacks, so this class keeps a
/// mirror of them ([chatPath] / [decisionsPath] / [coordinatorPath] /
/// [missionsPath], fed by
/// [noteDestination] from the host's destination-changed listener) and
/// expresses every cross-tab rule as plain, testable state changes plus
/// [Host] commands the Compose shell executes on the controller. Tests
/// construct it without a host (or with a recording one) and set the paths
/// directly, as Apple's do.
///
/// Path values: a bare room id for a chat (Apple's `[String]` path, where
/// `ChatSummary.ID == String`), `item/<id>` for an item detail (Apple's
/// `ItemRoute.pathValue`), `mission/<id>` for a mission page, and the
/// unprefixed route for anything else
/// pushed on a stack (`items/<convo>`, `search`, `settings`, …).
class AppShellNavigation(var host: Host? = null) {

    /// What the shell does to the `NavController` for each rule below. Kept
    /// as an interface so the rules stay a pure function of this object.
    interface Host {
        /// Select [tab], restoring its saved stack (the idiomatic
        /// `saveState` / `restoreState` tab switch).
        fun switchTab(tab: AppTab)
        /// REPLACE the Conversations stack with [roomID] over the list.
        fun replaceChats(roomID: String)
        /// Push [roomID] on [tab]'s stack (Conversations or Coordinator).
        fun pushChat(tab: AppTab, roomID: String)
        /// Push item [itemID]'s detail on the Decisions stack.
        fun pushDecision(itemID: String)
        /// Push mission [missionID]'s page on [tab]'s stack — the Missions
        /// tab, or whichever chat tab a title tap / milestone card sat on.
        fun pushMission(tab: AppTab, missionID: String)
        /// REPLACE the Missions stack with [missionID]'s page over the list.
        fun replaceMissions(missionID: String)
        /// Push item [itemID]'s detail on the Missions stack.
        fun pushMissionItem(itemID: String)
        /// Pop [tab]'s stack back to its root.
        fun popToRoot(tab: AppTab)
        /// Pop the top [count] entries off the Conversations stack (a
        /// coordinator hand-off, see [redirectCoordinatorPush]).
        fun popChats(count: Int)
    }

    private val _tab = MutableStateFlow(AppTab.CONVERSATIONS)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    /// Conversations tab stack (see the class doc for the value shapes).
    var chatPath: List<String> = emptyList()
    /// Decisions tab stack: `item/<id>` values.
    var decisionsPath: List<String> = emptyList()
    /// Coordinator tab stack: sub-chats and items opened from the
    /// coordinator push here, so back returns to it.
    var coordinatorPath: List<String> = emptyList()
    /// Missions tab stack: `mission/<id>` entries, plus `item/<id>` for an
    /// item opened from a mission page.
    var missionsPath: List<String> = emptyList()

    /// `false` once `GET /missions` has 404'd (set by the shell from
    /// `MissionsListViewModel.isSupported` through [missionsTabShown]);
    /// `true` while support is unknown or confirmed — the Missions tab is
    /// shown until proven unsupported, exactly as iOS's `AppShellView`
    /// (`isSupported != false`). Once `false` the tab is absent from the
    /// bar, so nothing may select its tag: the root swipe walks [tabs]
    /// rather than the unconditional `AppTab.entries`, [openMission]
    /// no-ops, and a selected Missions tab is walked back to Conversations
    /// the instant the flag flips false (apple #209 / #216). The clamp lives
    /// here, in the setter, so it is testable without a view.
    var missionsSupported: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && _tab.value == AppTab.MISSIONS) selectTabInternal(AppTab.CONVERSATIONS)
        }

    /// The designated coordinator conversation, mirrored from
    /// `CoordinatorSetting` by the shell so the rules below can route to
    /// its tab. `null` when none is set. A change pops the Coordinator
    /// stack to its new root and evicts the chat from wherever else it is
    /// mounted (Bugbot, apple #197).
    var coordinatorConvoID: String? = null
        set(value) {
            if (field == value) return
            field = value
            if (coordinatorPath.isNotEmpty()) {
                coordinatorPath = emptyList()
                entryIDs.getValue(AppTab.COORDINATOR).clear()
                drivingController { host?.popToRoot(AppTab.COORDINATOR) }
            }
            redirectCoordinatorPush()
        }

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

    /// Set while a rule drives the controller through pops whose end state
    /// the model has ALREADY applied ([redirectCoordinatorPush]'s eviction,
    /// [landOnCoordinatorRoot]): each pop — and the restore `popChats` may
    /// need first — re-enters [noteDestination], which would re-append the
    /// evicted room and re-run the redirect mid-mutation, over-popping the
    /// stack (Bugbot, #76). The mirror ignores reports while this is set;
    /// the tab switch that follows is outside the guard and reports normally.
    private var drivingController = false

    private inline fun drivingController(command: () -> Unit) {
        val outer = drivingController
        drivingController = true
        try {
            command()
        } finally {
            drivingController = outer
        }
    }

    /// Settings → Coordinator / the chooser: persist the choice, mirror it
    /// into these rules SYNCHRONOUSLY (Compose copies the persisted value in
    /// later — too late for the `openChat` that follows a pick, which would
    /// mount the room in Conversations first and only then evict it —
    /// Bugbot, #76), and evict the room from wherever it is mounted.
    fun assignCoordinator(convoID: String?, persist: (String?) -> Unit) {
        persist(convoID)
        coordinatorConvoID = convoID
    }

    /// A chooser pick: assign, then land on the Coordinator tab's root.
    fun chooseCoordinator(convoID: String, persist: (String?) -> Unit) {
        assignCoordinator(convoID, persist)
        openChat(convoID)
    }

    /// Open a top-level conversation by REPLACING the whole Conversations
    /// path, never appending: notification taps, search results and
    /// auto-opened new conversations used to stack chat-on-chat. Back from
    /// a conversation always returns to the chat list (Dan, 2026-08-06).
    /// No-op on the path when the target is already the sole open chat.
    /// The coordinator has its own tab (spec §5b) and is never mounted in
    /// Conversations as well.
    fun openChat(roomID: String) {
        if (roomID == coordinatorConvoID) {
            landOnCoordinatorRoot()
            return
        }
        selectTabInternal(AppTab.CONVERSATIONS)
        if (chatPath != listOf(roomID)) {
            chatPath = listOf(roomID)
            navigateExpecting(AppTab.CONVERSATIONS, roomID) { host?.replaceChats(roomID) }
        }
    }

    /// "Open conversation" from a Decisions row or its detail: switch to
    /// Conversations first, then push, in that order so the push lands in
    /// the visible stack (spec §3). The Decisions stack is left where it was.
    fun openConversationFromDecisions(convoID: String) = handOffToConversations(convoID)

    /// "Open the conversation" from a Missions row, a milestone or a
    /// mission page's conversation list: the same hand-off, so the two
    /// entry points cannot drift on the coordinator special case.
    fun openConversationFromMissions(convoID: String) = handOffToConversations(convoID)

    /// Open a mission from anywhere: select the tab and REPLACE its stack,
    /// so the page is never stacked on a stale copy of itself. No-op on an
    /// old journal that has no Missions tab to select.
    fun openMission(missionID: String) {
        if (!missionsSupported) return
        selectTabInternal(AppTab.MISSIONS)
        val value = missionRoute(missionID)
        if (missionsPath != listOf(value)) {
            missionsPath = listOf(value)
            navigateExpecting(AppTab.MISSIONS, value) { host?.replaceMissions(missionID) }
        }
    }

    /// Push a mission page on the SELECTED tab's stack — a Missions row, a
    /// chat's title tap, a milestone card (apple #209, `ChatView.pushMission`).
    /// Idempotent for the mission already on top: a double title tap or a
    /// second card tap for the same mission must not stack two identical
    /// pages. From Decisions (which hosts no mission page) it opens the
    /// mission on the Missions tab.
    fun pushMission(missionID: String) {
        val current = _tab.value
        if (current == AppTab.DECISIONS) {
            openMission(missionID)
            return
        }
        val value = missionRoute(missionID)
        if (path(current).lastOrNull() == value) return
        setPath(current, path(current) + value)
        navigateExpecting(current, value) { host?.pushMission(current, missionID) }
    }

    /// Push an item's detail on the Missions stack (a mission page's open
    /// item). Never changes the tab.
    fun pushMissionItem(itemID: String) {
        val value = itemRoute(itemID)
        missionsPath = missionsPath + value
        navigateExpecting(AppTab.MISSIONS, value) { host?.pushMissionItem(itemID) }
    }

    private fun handOffToConversations(convoID: String) {
        if (convoID == coordinatorConvoID) {
            landOnCoordinatorRoot()
            return
        }
        selectTabInternal(AppTab.CONVERSATIONS)
        if (chatPath.lastOrNull() != convoID) {
            chatPath = chatPath + convoID
            navigateExpecting(AppTab.CONVERSATIONS, convoID) { host?.pushChat(AppTab.CONVERSATIONS, convoID) }
        }
    }

    /// Push a chat on the SELECTED tab's stack (a sub-chat from the strip,
    /// a spawn card's Open, an item detail's origin link): Apple's
    /// `push(_:on:)`. Idempotent for the chat already on top. The
    /// coordinator id is redirected on the way in (never stored, never
    /// mounted for a frame — Bugbot, apple #197): on its own tab that is a
    /// pop to the root, elsewhere a hand-off to the tab. From Decisions a
    /// chat push means "open the conversation" (spec §3).
    fun pushChat(roomID: String) {
        val current = _tab.value
        if (roomID == coordinatorConvoID) {
            landOnCoordinatorRoot()
            return
        }
        if (current == AppTab.DECISIONS) {
            openConversationFromDecisions(roomID)
            return
        }
        if (path(current).lastOrNull() == roomID) return
        setPath(current, path(current) + roomID)
        navigateExpecting(current, roomID) { host?.pushChat(current, roomID) }
    }

    /// Push an item's detail on the Decisions stack. Never changes the tab.
    fun pushDecision(itemID: String) {
        val value = itemRoute(itemID)
        decisionsPath = decisionsPath + value
        navigateExpecting(AppTab.DECISIONS, value) { host?.pushDecision(itemID) }
    }

    /// Chat-list rows and origin links can land the coordinator on the
    /// Conversations stack, or a second copy over the Coordinator root:
    /// cut the stack back to just below it and hand off to its tab instead
    /// of mounting it twice (Bugbot, apple #197 — the entries beneath stay,
    /// so back in Conversations is unchanged). Returns whether anything
    /// moved. Runs after every mirrored destination change and on a
    /// setting change.
    fun redirectCoordinatorPush(): Boolean {
        val coordinator = coordinatorConvoID ?: return false
        var moved = false
        if (coordinatorPath.contains(coordinator)) {
            coordinatorPath = emptyList()
            entryIDs.getValue(AppTab.COORDINATOR).clear()
            drivingController { host?.popToRoot(AppTab.COORDINATOR) }
            moved = true
        }
        val index = chatPath.indexOf(coordinator)
        if (index >= 0) {
            val count = chatPath.size - index
            chatPath = chatPath.take(index)
            entryIDs.getValue(AppTab.CONVERSATIONS).let { ids -> while (ids.size > index) ids.removeAt(ids.size - 1) }
            drivingController { host?.popChats(count) }
            _tab.value = AppTab.COORDINATOR
            host?.switchTab(AppTab.COORDINATOR)
            moved = true
        }
        return moved
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

    /// Dan, 2026-09-09: swipe between the tabs' root lists. A mostly
    /// horizontal drag past 80dp at a tab's ROOT moves one tab in bar order
    /// (left = next, right = previous). Deeper in a stack the chat and the
    /// item detail own horizontal drags, so a non-empty path ignores it.
    /// Returns whether the tab changed.
    fun swipeRoot(dx: Float, dy: Float): Boolean {
        if (!isAtRoot || abs(dx) <= SWIPE_THRESHOLD_DP || abs(dx) <= abs(dy)) return false
        val tabs = tabs(missionsSupported)
        val index = tabs.indexOf(_tab.value)
        if (index < 0) return false
        val next = if (dx < 0) index + 1 else index - 1
        if (next !in tabs.indices) return false
        selectTabInternal(tabs[next])
        return true
    }

    /// Host feedback: the controller's current destination changed. [tab]
    /// is the graph it belongs to, [entryID] the controller's back-stack
    /// entry id and [pathValue] the value to mirror — `null` for a tab's
    /// root, which empties that tab's path. A known entry id is a pop
    /// (truncate after it); a new one is a push (append). The tab follows
    /// the destination so a system back out of Decisions into
    /// Conversations reselects the right item in the bar. A coordinator
    /// landing anywhere it should not is redirected as a backstop.
    fun noteDestination(tab: AppTab, entryID: String, pathValue: String?) {
        if (drivingController) return
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
        redirectCoordinatorPush()
    }

    private fun landOnCoordinatorRoot() {
        if (coordinatorPath.isNotEmpty()) {
            coordinatorPath = emptyList()
            entryIDs.getValue(AppTab.COORDINATOR).clear()
            drivingController { host?.popToRoot(AppTab.COORDINATOR) }
        }
        selectTabInternal(AppTab.COORDINATOR)
    }

    private fun selectTabInternal(tab: AppTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        host?.switchTab(tab)
    }

    private fun path(tab: AppTab): List<String> = when (tab) {
        AppTab.COORDINATOR -> coordinatorPath
        AppTab.MISSIONS -> missionsPath
        AppTab.CONVERSATIONS -> chatPath
        AppTab.DECISIONS -> decisionsPath
    }

    private fun setPath(tab: AppTab, value: List<String>) {
        when (tab) {
            AppTab.COORDINATOR -> coordinatorPath = value
            AppTab.MISSIONS -> missionsPath = value
            AppTab.CONVERSATIONS -> chatPath = value
            AppTab.DECISIONS -> decisionsPath = value
        }
    }

    companion object {
        /// Apple's 80pt, in dp.
        const val SWIPE_THRESHOLD_DP = 80f

        /// Apple's `ItemRoute.pathValue`.
        fun itemRoute(itemID: String): String = "item/$itemID"

        /// Apple's `MissionRoute.pathValue`.
        fun missionRoute(missionID: String): String = "mission/$missionID"

        /// Apple's `missionsVM.isSupported != false`: the tri-state support
        /// flag as the bar reads it — unknown (`null`) and confirmed (`true`)
        /// both show the Missions tab; only a 404 (`false`) hides it.
        fun missionsTabShown(isSupported: Boolean?): Boolean = isSupported != false

        /// The tabs actually in the bar for a given support state — the same
        /// set the shell's `NavigationBar` renders, in bar (and swipe) order.
        fun tabs(missionsSupported: Boolean): List<AppTab> =
            if (missionsSupported) AppTab.entries else AppTab.entries.filter { it != AppTab.MISSIONS }

        /// The path value a `NavController` destination mirrors to
        /// ([noteDestination]), from its route pattern and argument lookup:
        /// `null` for a tab root, the bare room id for a chat, `item/<id>`
        /// for an item-detail route on any tab, and the (tab-prefix-free)
        /// route with its argument filled in for everything else.
        fun pathValue(route: String?, argument: (String) -> String?): String? {
            if (route == null || AppTab.entries.any { it.rootRoute == route }) return null
            val bare = AppTab.entries.firstOrNull { it.routePrefix.isNotEmpty() && route.startsWith(it.routePrefix) }
                ?.let { route.removePrefix(it.routePrefix) } ?: route
            return when (bare) {
                "chat/{convoID}" -> argument("convoID")
                "item/{itemID}" -> argument("itemID")?.let(::itemRoute)
                "mission/{missionID}" -> argument("missionID")?.let(::missionRoute)
                else -> Regex("\\{([^}]+)\\}").replace(bare) { m -> argument(m.groupValues[1]) ?: m.value }
            }
        }
    }
}
