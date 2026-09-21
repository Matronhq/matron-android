package chat.matron.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import chat.matron.android.designsystem.AppLockShield
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.TrackerItemLinkHost
import chat.matron.android.designsystem.TrackerItemLinkOutcome
import chat.matron.android.designsystem.SyncBannerState
import chat.matron.android.designsystem.needsYouBadgeText
import chat.matron.android.designsystem.syncBannerStateFrom
import chat.matron.android.designsystem.tabRootSwipe
import chat.matron.android.features.coordinator.CoordinatorChooserSheet
import chat.matron.android.features.coordinator.CoordinatorRoot
import chat.matron.android.features.coordinator.CoordinatorSetupView
import chat.matron.android.features.coordinator.coordinatorRoot
import chat.matron.android.features.decisions.DecisionsScreen
import chat.matron.android.features.chat.ChatScreen
import chat.matron.android.features.chat.ChatVMCache
import chat.matron.android.features.chat.SubChatView
import chat.matron.android.features.chatlist.ChatListScreen
import chat.matron.android.features.chatlist.NewChatSheet
import chat.matron.android.features.chatlist.currentSummary
import chat.matron.android.features.items.ItemDetailScreen
import chat.matron.android.features.items.ItemsScreen
import chat.matron.android.viewmodels.ItemReadMemory
import chat.matron.android.features.onboarding.SignInScreen
import chat.matron.android.features.search.SearchScreen
import chat.matron.android.features.settings.DeviceLinkScreen
import chat.matron.android.features.settings.CoordinatorSettingRowModel
import chat.matron.android.features.settings.DeviceSettingsScreen
import chat.matron.android.features.settings.AgentChatScreen
import chat.matron.android.features.settings.DevicesScreen
import chat.matron.android.journal.RelayApi
import chat.matron.android.models.MatronDebug
import chat.matron.android.sync.OutboxCatchUpWorker
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.UserSession
import chat.matron.android.platform.AndroidBiometricAuthenticator
import chat.matron.android.viewmodels.AppLockController
import chat.matron.android.viewmodels.KeyValueBoxCapacityCache
import chat.matron.android.viewmodels.ChatListViewModel
import chat.matron.android.viewmodels.CoordinatorSetting
import chat.matron.android.viewmodels.LinkSignInViewModel
import chat.matron.android.viewmodels.MediaBrowserViewModel
import okhttp3.HttpUrl.Companion.toHttpUrl
import chat.matron.android.viewmodels.RendezvousSignInViewModel
import chat.matron.android.viewmodels.SearchViewModel
import chat.matron.android.viewmodels.SignInViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single-activity Compose host. Ports App/MatronApp.swift: bootstrap the persisted
 * session, switch signed-out → [SignInScreen] vs signed-in → the tabbed shell
 * (`SignedInApp`, apple's `AppShellView`) over the [NavHost]. Push
 * (APNs/FCM) and its notification-tap deep-link are NOT wired — Android push is
 * dormant; those iOS `.task`s are dropped (see the class docs).
 *
 * Extends [FragmentActivity] rather than `ComponentActivity` purely so
 * `BiometricPrompt` has a host for its internal fragment; `FragmentActivity` IS a
 * `ComponentActivity`, so `enableEdgeToEdge`/`setContent` are unaffected.
 */
class MainActivity : FragmentActivity() {

    /**
     * App lock, activity-scoped because its authenticator needs a
     * [FragmentActivity]. That scoping also gives the iOS "always lock on cold
     * launch" rule for free: a recreated activity builds a fresh controller,
     * which re-locks. Erring towards locking is the right direction for a lock.
     */
    private lateinit var appLock: AppLockController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) enforces edge-to-edge, which disables the
        // manifest's adjustResize; opting in on every version keeps inset
        // behaviour uniform so screens can rely on imePadding() for the IME.
        enableEdgeToEdge()
        val deps = (application as MatronApplication).dependencies
        appLock = AppLockController(
            auth = AndroidBiometricAuthenticator(this),
            store = deps.preferences,
        )
        setContent { MatronApp(deps, appLock) }
    }

    // Activity start/stop rather than ProcessLifecycleOwner: this is a
    // single-activity app, so the two coincide, and ON_START/ON_STOP need no
    // extra dependency. The controller's own guards absorb the churn from the
    // credential prompt, which runs in a system activity that stops ours.
    override fun onStart() {
        super.onStart()
        appLock.noteForegrounded()
    }

    override fun onStop() {
        super.onStop()
        appLock.noteBackgrounded()
    }
}

@Composable
private fun MatronApp(deps: AppDependencies, appLock: AppLockController) {
    val context = deps.context
    val prefs = remember { context.getSharedPreferences("matron-kv", android.content.Context.MODE_PRIVATE) }
    var appearance by remember {
        mutableStateOf(MatronAppearance.fromStored(prefs.getString(MatronAppearance.STORAGE_KEY, null)))
    }
    val scope = rememberCoroutineScope()
    val isLocked by appLock.isLocked.collectAsStateWithLifecycle()

    MatronTheme(appearance = appearance) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var bootstrapped by remember { mutableStateOf(false) }
            var session by remember { mutableStateOf<UserSession?>(null) }

            LaunchedEffect(Unit) {
                session = runCatching { deps.auth.restoreSession() }
                    .onFailure { MatronDebug.breadcrumb("restoreSession threw — starting signed out: $it") }
                    .getOrNull()
                bootstrapped = true
            }

            // The shield REPLACES the app rather than covering it. Sheets and
            // dialogs render in their own platform windows, so an overlay drawn
            // "on top" would leave an open one visible; not composing content at
            // all leaves nothing to escape through, and a cold launch that
            // starts locked never shows a frame of content. See AppLockShield.
            if (isLocked) {
                val authenticating by appLock.isAuthenticating.collectAsStateWithLifecycle()
                val error by appLock.authError.collectAsStateWithLifecycle()
                AppLockShield(
                    isAuthenticating = authenticating,
                    errorMessage = error,
                    onUnlock = { scope.launch { appLock.unlock() } },
                )
                return@Surface
            }

            when {
                !bootstrapped -> LoadingScreen()
                session == null -> {
                    val vm = remember { SignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android") }
                    val linkVm = remember {
                        LinkSignInViewModel(
                            auth = deps.auth,
                            deviceDisplayName = "Matron Android",
                            scope = scope,
                            haptics = deps.haptics,
                        )
                    }
                    val rendezvousVm = remember {
                        RendezvousSignInViewModel(
                            relay = RelayApi(client = deps.sharedClient),
                            link = linkVm,
                            scope = scope,
                            haptics = deps.haptics,
                        )
                    }
                    SignInScreen(
                        viewModel = vm,
                        linkViewModel = linkVm,
                        rendezvousViewModel = rendezvousVm,
                        onSignedIn = { s ->
                            // Gate on any in-flight sign-out teardown before publishing
                            // the new session (mirrors iOS awaitPendingTeardown), then
                            // clear any mirror files a crashed teardown left behind —
                            // a fresh login resyncs from a server snapshot anyway.
                            scope.launch {
                                deps.awaitPendingTeardown()
                                deps.wipeLocalDataForFreshLogin()
                                // Signing in interactively IS an authentication;
                                // the new session must not open behind a shield.
                                appLock.noteSignedIn()
                                session = s
                            }
                        },
                    )
                }
                else -> SignedInApp(
                    deps = deps,
                    session = session!!,
                    appearance = appearance,
                    appLock = appLock,
                    onAppearanceChange = { next ->
                        appearance = next
                        prefs.edit().putString(MatronAppearance.STORAGE_KEY, next.rawValue).apply()
                    },
                    // Refused while locked: signing out clears the lock, so it
                    // must not be reachable from behind the shield. Unreachable
                    // in practice (the menu isn't composed while locked) — the
                    // guard belongs with the lock state regardless.
                    onSignOut = {
                        appLock.signOutIfUnlocked {
                            OutboxCatchUpWorker.cancel(context)
                            deps.signOut()
                            // Per-account state, cleared like the rest: the next
                            // account starts opted out.
                            appLock.resetForSignOut()
                            session = null
                        }
                    },
                )
            }
        }
    }
}

/**
 * Builds the "open a spawned session's room" callback the agent-spawn card
 * and its `SpawnOutcomeRow` deep-link into (`TimelineItemView.onOpenSpawnedRoom`,
 * threaded through `ChatScreen`/`SubChatView` as `onOpenConversation`).
 *
 * Copies the exact [NewChatSheet] / `NewChatViewModel` precedent for a
 * freshly-started conversation: [prepareConversation] (ensures the
 * placeholder convo row) THEN [navigate] — in that order, so a `chat/$roomId`
 * navigation that lands before the room's first journal frame still has a
 * row to render against, rather than racing the journal's own snapshot.
 *
 * A plain top-level function — not a `@Composable` — so the ordering is unit
 * -testable without Compose: pass a test scope and fakes for the two
 * effects.
 */
fun openConversationCallback(
    scope: CoroutineScope,
    prepareConversation: suspend (roomId: String) -> Unit,
    navigate: (roomId: String) -> Unit,
): (roomId: String) -> Unit = { roomId ->
    scope.launch {
        prepareConversation(roomId)
        navigate(roomId)
    }
}

/// The tab-root routes: the only destinations where the bottom bar shows
/// (spec §3 — hidden inside a pushed chat and inside item detail).
private val tabRootRoutes: Set<String> = AppTab.entries.map { it.rootRoute }.toSet()

/// Which tab a destination belongs to, from its graph hierarchy.
private fun NavDestination.appTab(): AppTab =
    AppTab.entries.firstOrNull { tab -> hierarchy.any { it.route == tab.route } } ?: AppTab.CONVERSATIONS

/// [AppShellNavigation.Host] over the `NavController`: the idiomatic
/// per-tab back stacks (`saveState` / `restoreState` on the tab switch),
/// a chat REPLACE as pop-to-list-then-push, and plain pushes elsewhere.
private class NavControllerShellHost(private val nav: NavHostController) : AppShellNavigation.Host {
    override fun switchTab(tab: AppTab) {
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun replaceChats(roomID: String) {
        nav.navigate("chat/$roomID") {
            popUpTo(AppTab.CONVERSATIONS.rootRoute) { inclusive = false }
            launchSingleTop = true
        }
    }

    override fun pushChat(tab: AppTab, roomID: String) {
        nav.navigate("${tab.routePrefix}chat/$roomID")
    }

    override fun pushDecision(itemID: String) {
        nav.navigate("${AppTab.DECISIONS.routePrefix}item/$itemID")
    }

    override fun popToRoot(tab: AppTab) {
        nav.popBackStack(tab.rootRoute, inclusive = false)
    }

    /// The Conversations stack may be saved away (another tab showing):
    /// restore it first, then pop; the rule that asked for this switches
    /// to the Coordinator tab right after.
    override fun popChats(count: Int) {
        if (nav.currentDestination?.appTab() != AppTab.CONVERSATIONS) switchTab(AppTab.CONVERSATIONS)
        repeat(count) { nav.popBackStack() }
    }
}

/// The signed-in shell (app shell, spec §3): a Material3 `NavigationBar`
/// over the Conversations graph (the pre-existing chat list + every
/// deep-link path) and the Decisions graph. Owns the per-session Decisions
/// view model — one `ItemsPanelViewModel(convoID = null)` started here,
/// stopped when the shell leaves the composition on sign-out — so the tab
/// badge is live app-wide. Ported from matron-apple's `AppShellView`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInApp(
    deps: AppDependencies,
    session: UserSession,
    appearance: MatronAppearance,
    appLock: AppLockController,
    onAppearanceChange: (MatronAppearance) -> Unit,
    onSignOut: () -> Unit,
) {
    val nav = rememberNavController()
    val sessionScope = rememberCoroutineScope()
    val shell = remember(session.userID, nav) { AppShellNavigation(NavControllerShellHost(nav)) }
    val vmCache = remember(session.userID) { ChatVMCache(deps, session, sessionScope) }
    val chatListVM = remember(session.userID) { ChatListViewModel(deps.chatService(session), sessionScope) }
    val decisionsVM = remember(session.userID) { deps.makeDecisionsViewModel(session, sessionScope) }
    // The coordinator conversation (spec §5b), one live setting per session
    // shared by the tab, Settings and the nav rules.
    val coordinatorSetting = remember(session.userID) { CoordinatorSetting(session.userID, deps.preferences) }
    val coordinatorConvoID by coordinatorSetting.convoID.collectAsStateWithLifecycle()

    var connectionState by remember { mutableStateOf<SyncBannerState>(SyncBannerState.Connecting) }
    var hasEverConnected by remember { mutableStateOf(false) }
    var newChatTarget by remember { mutableStateOf<NewChatTarget?>(null) }
    var showCoordinatorChooser by remember { mutableStateOf(false) }

    val groups by chatListVM.groups.collectAsStateWithLifecycle()
    val allChats = remember(groups) { groups.flatMap { it.summaries } }

    // Mirror the controller's stacks into the shell's rules (pushes, pops,
    // system back, tab roots) — see AppShellNavigation.noteDestination.
    DisposableEffect(nav, shell) {
        val listener = NavController.OnDestinationChangedListener { controller, destination, args ->
            shell.noteDestination(
                tab = destination.appTab(),
                entryID = controller.currentBackStackEntry?.id ?: "",
                pathValue = AppShellNavigation.pathValue(destination.route) { args?.getString(it) },
            )
        }
        nav.addOnDestinationChangedListener(listener)
        onDispose { nav.removeOnDestinationChangedListener(listener) }
    }
    // The Decisions VM runs for the whole signed-in session: the badge
    // must be live while any tab shows.
    DisposableEffect(decisionsVM) {
        decisionsVM.start()
        onDispose { decisionsVM.stop() }
    }
    // The nav rules route the coordinator conversation to its own tab
    // (Bugbot, apple #197): mirror the setting into the shell.
    LaunchedEffect(shell, coordinatorConvoID) { shell.coordinatorConvoID = coordinatorConvoID }

    // Agent-spawn card / SpawnOutcomeRow "Open" deep link. remembered (keyed
    // on session.userID, matching vmCache/chatListVM above) because
    // openConversationCallback is a plain function, not @Composable — its
    // returned lambda is NOT compiler-memoised, so calling it unremembered
    // would allocate a fresh instance on every SignedInApp recomposition
    // (e.g. every `groups` emission) and, as the only unstable parameter in
    // the ChatRoute -> ChatScreen/SubChatView -> TimelineList ->
    // TimelineRowView chain, force every visible timeline row to recompose
    // under strong skipping.
    val onOpenConversation = remember(session.userID) {
        openConversationCallback(
            scope = sessionScope,
            prepareConversation = { id -> deps.prepareConversation(session, id) },
            // A repeat tap (no immediate feedback — the navigation is
            // deferred behind the suspend placeholder write, which invites a
            // double-tap) or an Open for the room already on screen must
            // no-op rather than push a duplicate back-stack entry — matches
            // the port source's explicit `path.wrappedValue.last != roomID`
            // guard (ChatView.swift). NOT launchSingleTop: that matches on
            // the destination id, so all `chat/{convoID}` screens count as
            // "the same" — opening a spawned room from its parent chat would
            // REPLACE the parent's back-stack entry (Back then skips to the
            // list and the parent's state is lost) instead of pushing.
            // The shell's push rule keeps the guard (the chat already on top
            // of the selected tab's stack is a no-op) and adds the
            // coordinator hand-off (apple #197).
            navigate = { id -> shell.pushChat(id) },
        )
    }
    // "Open conversation" from a Decisions row or its detail: the shell's
    // rule (switch to Conversations, then push — spec §3), behind the same
    // placeholder-first ordering as every other conversation deep link.
    val onOpenConversationFromDecisions = remember(session.userID, shell) {
        openConversationCallback(
            scope = sessionScope,
            prepareConversation = { id -> deps.prepareConversation(session, id) },
            navigate = { id -> shell.openConversationFromDecisions(id) },
        )
    }
    // Swipe between the conversation list and the decisions list at a
    // tab's root (apple #196); the rule itself is AppShellNavigation.swipeRoot.
    val rootSwipe = remember(shell) { Modifier.tabRootSwipe { dx, dy -> shell.swipeRoot(dx, dy) } }

    LaunchedEffect(session.userID) { chatListVM.start() }
    // One-time push of legacy local tag letters up to the journal (apple
    // #158); idempotent, and a no-op once the install carries none.
    LaunchedEffect(session.userID) { deps.migrateBoxLetters(session) }
    LaunchedEffect(session.userID) {
        // Periodic background catch-up (journal + offline outbox flush) for
        // when the process is gone — the analog of iOS's BGAppRefresh.
        runCatching { OutboxCatchUpWorker.schedule(deps.context) }
            .onFailure { MatronDebug.breadcrumb("OutboxCatchUpWorker.schedule failed: $it") }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session.userID) {
        val sync = deps.syncService(session)
        // (Re)start on EVERY foreground entry, not once: the catch-up worker
        // legitimately stops an engine it started when the app isn't visible
        // at its teardown, which can happen while this composition is alive
        // in the background (bugbot "Worker teardown stops foreground sync").
        // start() is a no-op on an already-running engine.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            runCatching { sync.start() }
            sync.stateStream.collect { state ->
                connectionState = syncBannerStateFrom(state)
                if (state is SyncConnectionState.Running) hasEverConnected = true
            }
        }
    }
    LaunchedEffect(session.userID) {
        // Auto-open a conversation the bridge just created while we're live.
        // A deep link REPLACES the Conversations path (never chat-on-chat)
        // and lands in that tab whichever was showing.
        deps.syncService(session).newConversations().collect { convoID ->
            shell.openChat(convoID)
        }
    }

    val currentEntry by nav.currentBackStackEntryAsState()
    val atTabRoot = currentEntry?.destination?.route in tabRootRoutes
    val selectedTab by shell.tab.collectAsStateWithLifecycle()
    val awaitingYouCount by decisionsVM.awaitingYouCount.collectAsStateWithLifecycle()
    // The chat-list unread rule as a dot on the Coordinator tab.
    val coordinatorHasUnread = coordinatorConvoID?.let { id -> (currentSummary(groups, id)?.unreadCount ?: 0) > 0 } ?: false
    val coordinatorTitle = coordinatorConvoID?.let { id -> currentSummary(groups, id)?.title?.ifEmpty { null } ?: id }

    /// One chat / tasks / item-detail destination set per tab that hosts
    /// chats (Conversations, Coordinator), under the tab's route prefix.
    fun NavGraphBuilder.chatDestinations(tab: AppTab) {
        val prefix = tab.routePrefix
        composable(
            route = "${prefix}chat/{convoID}",
            arguments = listOf(navArgument("convoID") { type = NavType.StringType }),
        ) { entry ->
            val convoID = entry.arguments?.getString("convoID") ?: return@composable
            ChatRoute(
                deps = deps,
                session = session,
                convoID = convoID,
                vmCache = vmCache,
                title = currentSummary(groups, convoID)?.title ?: "",
                boxName = currentSummary(groups, convoID)?.boxName,
                sessionShort = currentSummary(groups, convoID)?.sessionShort,
                boxShort = currentSummary(groups, convoID)?.boxShort,
                roomBoxNames = currentSummary(groups, convoID)?.roomBoxNames ?: emptyList(),
                roomBoxShorts = currentSummary(groups, convoID)?.roomBoxShorts ?: emptyList(),
                onBack = { nav.popBackStack() },
                onOpenChild = { shell.pushChat(it) },
                onSwitchTo = { sibling ->
                    nav.navigate("${prefix}chat/$sibling") {
                        popUpTo("${prefix}chat/$convoID") { inclusive = true }
                    }
                },
                onOpenConversation = onOpenConversation,
                onOpenItems = { nav.navigate("${prefix}items/$convoID") },
                onOpenItem = { nav.navigate("${prefix}item/$it") },
            )
        }

        // The conversation's tasks page (apple #185 / #194): the items panel
        // as its own destination, reached from the chat top bar.
        composable(
            route = "${prefix}items/{convoID}",
            arguments = listOf(navArgument("convoID") { type = NavType.StringType }),
        ) { entry ->
            val convoID = entry.arguments?.getString("convoID") ?: return@composable
            val itemsVM = remember(convoID) { vmCache.itemsPanelViewModel(convoID) }
            ItemsScreen(
                viewModel = itemsVM,
                originLabels = { deps.journalStore(session).conversationOriginLabels() },
                onBack = { nav.popBackStack() },
                onSelect = { item -> nav.navigate("${prefix}item/${item.id}") },
                onOpenConversation = onOpenConversation,
            )
        }

        // One item's thread. Its own route (not a sheet inside the list) so
        // the system back returns to the list and a later port can deep-link
        // `matron://item/N` straight here.
        composable(
            route = "${prefix}item/{itemID}",
            arguments = listOf(navArgument("itemID") { type = NavType.StringType }),
        ) { entry ->
            val itemID = entry.arguments?.getString("itemID") ?: return@composable
            ItemDetailRoute(
                deps = deps, session = session, vmCache = vmCache, itemID = itemID,
                onBack = { nav.popBackStack() },
                onOpenConversation = onOpenConversation,
                // `[#12](matron://item/12)` inside the body or a comment pushes
                // that item over this one (apple #208).
                resolveItemLink = { num -> deps.trackerItemLinkOutcome(num, session) },
                onOpenItem = { nav.navigate("${prefix}item/$it") },
            )
        }
    }

    Scaffold(
        // Only the bottom bar contributes padding; each screen keeps its own
        // Scaffold and its own status-bar / IME handling.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (atTabRoot) {
                AppTabBar(
                    selected = selectedTab,
                    awaitingYouCount = awaitingYouCount,
                    coordinatorHasUnread = coordinatorHasUnread,
                    onSelect = { shell.selectTab(it) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = AppTab.CONVERSATIONS.route,
            // consumeWindowInsets: the bar already sits above the system
            // navigation bar, so the screens beneath must not pad for it again.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            // The Coordinator tab (spec §3, §5b): its own graph, rooted at the
            // setup view or the coordinator chat — full screen, no back
            // button, the bar visible (it is the only way out of the tab).
            // Sub-chats, the tasks page and items opened from it push here.
            navigation(route = AppTab.COORDINATOR.route, startDestination = AppTab.COORDINATOR.rootRoute) {
                composable(AppTab.COORDINATOR.rootRoute) {
                    when (val root = coordinatorRoot(coordinatorConvoID)) {
                        CoordinatorRoot.Setup -> CoordinatorSetupView(onChoose = { showCoordinatorChooser = true }, modifier = rootSwipe)
                        is CoordinatorRoot.Chat -> {
                            val convoID = root.convoID
                            ChatRoute(
                                deps = deps,
                                session = session,
                                convoID = convoID,
                                vmCache = vmCache,
                                title = currentSummary(groups, convoID)?.title ?: "",
                                boxName = currentSummary(groups, convoID)?.boxName,
                                sessionShort = currentSummary(groups, convoID)?.sessionShort,
                                boxShort = currentSummary(groups, convoID)?.boxShort,
                                roomBoxNames = currentSummary(groups, convoID)?.roomBoxNames ?: emptyList(),
                                roomBoxShorts = currentSummary(groups, convoID)?.roomBoxShorts ?: emptyList(),
                                onBack = {},
                                showsBackButton = false,
                                onOpenChild = { shell.pushChat(it) },
                                onSwitchTo = { sibling -> shell.pushChat(sibling) },
                                onOpenConversation = onOpenConversation,
                                onOpenItems = { nav.navigate("${AppTab.COORDINATOR.routePrefix}items/$convoID") },
                                onOpenItem = { nav.navigate("${AppTab.COORDINATOR.routePrefix}item/$it") },
                            )
                        }
                    }
                }
                chatDestinations(AppTab.COORDINATOR)
            }

            navigation(route = AppTab.CONVERSATIONS.route, startDestination = AppTab.CONVERSATIONS.rootRoute) {
                composable(AppTab.CONVERSATIONS.rootRoute) {
                    ChatListScreen(
                        viewModel = chatListVM,
                        connectionState = connectionState,
                        hasEverConnected = hasEverConnected,
                        searchAvailable = deps.search != null,
                        chat = deps.chatService(session),
                        onOpenChat = { shell.openChat(it) },
                        onNewChat = { newChatTarget = NewChatTarget.CONVERSATIONS },
                        onOpenSearch = { nav.navigate("search") },
                        onOpenSettings = { nav.navigate("settings") },
                        onSignOut = onSignOut,
                        rootGesture = rootSwipe,
                    )
                }

                chatDestinations(AppTab.CONVERSATIONS)

                composable("search") {
                    val searchService = deps.search
                    if (searchService == null) {
                        // Navigation is a side effect — never call it straight from
                        // the composable body (bugbot "Search route pops during
                        // composition").
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        val searchVM = remember { SearchViewModel(searchService, allChats) }
                        SearchScreen(
                            viewModel = searchVM,
                            // A result REPLACES the Conversations path (the shell's
                            // openChat pops search along with anything else over
                            // the list).
                            onSelectChat = { chat -> shell.openChat(chat.id) },
                            onSelectMessage = { hit ->
                                // Arm the (cached) chat VM's in-conversation search
                                // with the query, then navigate: a cold VM parks the
                                // jump until its first snapshot lands (apple #172).
                                val query = searchVM.trimmedQuery
                                val (chatVM, _) = vmCache.viewModels(hit.roomID)
                                // sessionScope, not this route's composition scope:
                                // the navigation below cancels the latter before the
                                // query's first suspend (Bugbot, #56).
                                sessionScope.launch { chatVM.beginChatSearch(query) }
                                shell.openChat(hit.roomID)
                            },
                            onBack = { nav.popBackStack() },
                            liveChats = allChats,
                        )
                    }
                }

                composable("settings") {
                    DeviceSettingsScreen(
                        session = session,
                        devicesApi = deps.devicesService(session),
                        appearance = appearance,
                        onAppearanceChange = onAppearanceChange,
                        onManageDevices = { nav.navigate("devices") },
                        onLinkDevice = { nav.navigate("link-device") },
                        onAgentChats = { nav.navigate("agent-chats") },
                        appLock = appLock,
                        coordinator = CoordinatorSettingRowModel(
                            title = coordinatorTitle,
                            onChoose = { showCoordinatorChooser = true },
                            onClear = { coordinatorSetting.set(null) },
                        ),
                        onBack = { nav.popBackStack() },
                    )
                }

                composable("devices") {
                    DevicesScreen(
                        api = deps.devicesService(session),
                        onSelfRevoked = onSignOut,
                        onBack = { nav.popBackStack() },
                    )
                }

                composable("agent-chats") {
                    AgentChatScreen(
                        api = deps.agentChatService(session),
                        onBack = { nav.popBackStack() },
                    )
                }

                composable("link-device") {
                    DeviceLinkScreen(
                        api = deps.deviceLinkService(session),
                        serverURL = session.homeserverURL,
                        relay = RelayApi(client = deps.sharedClient),
                        haptics = deps.haptics,
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            // The Decisions tab (spec §3): its own graph, rooted at the list;
            // a tapped decision pushes item detail WITHIN this tab.
            navigation(route = AppTab.DECISIONS.route, startDestination = AppTab.DECISIONS.rootRoute) {
                composable(AppTab.DECISIONS.rootRoute) {
                    DecisionsScreen(
                        viewModel = decisionsVM,
                        originLabels = { deps.journalStore(session).conversationOriginLabels() },
                        onSelect = { shell.pushDecision(it) },
                        onOpenConversation = onOpenConversationFromDecisions,
                        rootGesture = rootSwipe,
                    )
                }
                composable(
                    route = "${AppTab.DECISIONS.routePrefix}item/{itemID}",
                    arguments = listOf(navArgument("itemID") { type = NavType.StringType }),
                ) { entry ->
                    val itemID = entry.arguments?.getString("itemID") ?: return@composable
                    ItemDetailRoute(
                        deps = deps, session = session, vmCache = vmCache, itemID = itemID,
                        onBack = { nav.popBackStack() },
                        onOpenConversation = onOpenConversationFromDecisions,
                        // A linked item pushes within the Decisions tab too, so
                        // Back returns to the decision the link was tapped in.
                        resolveItemLink = { num -> deps.trackerItemLinkOutcome(num, session) },
                        onOpenItem = { nav.navigate("${AppTab.DECISIONS.routePrefix}item/$it") },
                    )
                }
            }
        }
    }

    val target = newChatTarget
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { newChatTarget = null }, sheetState = sheetState) {
            NewChatSheet(
                api = deps.agentRPCService(session),
                capacityCache = KeyValueBoxCapacityCache(session.userID, deps.preferences),
                prepareConversation = { id -> deps.prepareConversation(session, id) },
                onCreated = { convoID ->
                    newChatTarget = null
                    // "New coordinator chat…" stores the new id first, so
                    // openChat lands it on the Coordinator tab.
                    if (target == NewChatTarget.COORDINATOR) coordinatorSetting.set(convoID)
                    shell.openChat(convoID)
                },
                onCancel = { newChatTarget = null },
            )
        }
    }

    if (showCoordinatorChooser) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val isLoading by chatListVM.isLoading.collectAsStateWithLifecycle()
        ModalBottomSheet(onDismissRequest = { showCoordinatorChooser = false }, sheetState = sheetState) {
            CoordinatorChooserSheet(
                chats = allChats,
                isLoading = isLoading,
                onPick = { id ->
                    showCoordinatorChooser = false
                    coordinatorSetting.set(id)
                    shell.openChat(id)
                },
                onNewChat = {
                    showCoordinatorChooser = false
                    newChatTarget = NewChatTarget.COORDINATOR
                },
                onCancel = { showCoordinatorChooser = false },
            )
        }
    }
}

/// Who asked for the New Chat sheet: the list (an ordinary chat) or the
/// coordinator chooser (the new chat becomes the coordinator).
private enum class NewChatTarget { CONVERSATIONS, COORDINATOR }

/// The bottom bar: Coordinator, Conversations and Decisions (Missions
/// later). The Decisions badge is the app-wide awaiting-you count, hidden
/// at zero; the Coordinator badge is the chat-list unread rule as a dot.
@Composable
private fun AppTabBar(
    selected: AppTab,
    awaitingYouCount: Int,
    coordinatorHasUnread: Boolean,
    onSelect: (AppTab) -> Unit,
) {
    NavigationBar {
        AppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = { Text(tab.label) },
                icon = {
                    val icon = when (tab) {
                        AppTab.COORDINATOR -> Icons.Filled.SupervisorAccount
                        AppTab.CONVERSATIONS -> Icons.Filled.Forum
                        AppTab.DECISIONS -> Icons.Filled.CheckCircle
                    }
                    when {
                        tab == AppTab.DECISIONS && awaitingYouCount > 0 ->
                            BadgedBox(badge = { Badge { Text(needsYouBadgeText(awaitingYouCount)) } }) {
                                Icon(icon, contentDescription = null)
                            }
                        tab == AppTab.COORDINATOR && coordinatorHasUnread ->
                            BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = null) }
                        else -> Icon(icon, contentDescription = null)
                    }
                },
            )
        }
    }
}

/// One item's thread, shared by the Conversations (`item/{itemID}`) and
/// Decisions (`decision/{itemID}`) graphs — same screen, different
/// "open conversation" rule.
@Composable
private fun ItemDetailRoute(
    deps: AppDependencies,
    session: UserSession,
    vmCache: ChatVMCache,
    itemID: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    /// `[#12](matron://item/12)` inside the body or a comment (apple #208):
    /// resolved through the session's store + sync …
    resolveItemLink: suspend (Int) -> TrackerItemLinkOutcome,
    /// … and pushed over this item WITHIN the hosting tab, so Back returns
    /// to the item the link was tapped in.
    onOpenItem: (String) -> Unit,
) {
    val detailVM = remember(itemID) { vmCache.itemDetailViewModel(itemID) }
    val readMemory = remember(session.userID) { ItemReadMemory(deps.preferences) }
    ItemDetailScreen(
        viewModel = detailVM,
        media = deps.mediaService(session),
        serverURL = session.homeserverURL.toHttpUrl(),
        originLabel = { deps.journalStore(session).conversationOriginLabel(it) },
        readMemory = readMemory,
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        resolveItemLink = resolveItemLink,
        onOpenItem = onOpenItem,
    )
}

/**
 * Routes a `chat/{convoID}` destination to the read-only [SubChatView] (subagent
 * child) or the full [ChatScreen]. The parent lookup is a suspend Room read on
 * Android, so a brief spinner covers it (iOS resolved it synchronously).
 */
@Composable
private fun ChatRoute(
    deps: AppDependencies,
    session: UserSession,
    convoID: String,
    vmCache: ChatVMCache,
    title: String,
    onBack: () -> Unit,
    onOpenChild: (String) -> Unit,
    onSwitchTo: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    /// Opens the conversation's tasks page (the tracker button in the top bar).
    onOpenItems: () -> Unit,
    /// Opens one item's thread from an inline marker card in the timeline
    /// (apple #186) — the same `item/{itemID}` route the tasks page pushes,
    /// under the hosting tab's prefix.
    onOpenItem: (String) -> Unit,
    /// `false` at the Coordinator tab's root, where the chat IS the tab.
    showsBackButton: Boolean = true,
    /// Which agent box runs this session, or null when the user has fewer
    /// than two boxes. Threaded from the list's ChatSummary (same source as
    /// the row chip) so header and row can never disagree.
    boxName: String? = null,
    /// The `A:bc` tag halves + multi-agent room participants, threaded from
    /// the same ChatSummary so the in-chat header matches the row.
    sessionShort: String? = null,
    boxShort: String? = null,
    roomBoxNames: List<String> = emptyList(),
    roomBoxShorts: List<String> = emptyList(),
) {
    // Observed, not one-shot: the mirror can learn parent_convo_id AFTER this
    // route composes (convo_meta or a snapshot upsert), and the route must
    // switch to the read-only sub-chat presentation when it does (bugbot
    // "Sub-chat parent never refreshes"). Linkage is immutable once set, so
    // emissions only ever go null → parent.
    val lookup by produceState<ParentLookup?>(initialValue = null, convoID) {
        deps.parentConvoIDFlow(session, convoID).collect { value = ParentLookup(it) }
    }
    val resolved = lookup
    if (resolved == null) {
        LoadingScreen()
        return
    }
    val parent = resolved.parent
    if (parent != null) {
        val (chatVM, stripVM) = vmCache.subChatViewModels(convoID, parent)
        SubChatView(
            chatVM = chatVM,
            stripVM = stripVM,
            childID = convoID,
            fallbackTitle = "Subagent",
            onBack = onBack,
            onSwitchTo = onSwitchTo,
            onOpenConversation = onOpenConversation,
        )
    } else {
        val (chatVM, composerVM) = vmCache.viewModels(convoID)
        val stripVM = vmCache.stripViewModel(convoID)
        // The per-room items panel VM runs while the chat is open so the
        // top-bar badge is live; the generation guard keeps a stale
        // disposal (this route re-entered from the tasks page) from
        // cancelling the successor's observation.
        val itemsVM = remember(convoID) { vmCache.itemsPanelViewModel(convoID) }
        DisposableEffect(itemsVM) {
            itemsVM.start()
            val generation = itemsVM.observationGeneration
            onDispose { itemsVM.stop(generation) }
        }
        val needsYouCount by itemsVM.needsYouCount.collectAsStateWithLifecycle()
        val itemsSupported by itemsVM.isSupported.collectAsStateWithLifecycle()
        // `[#65](matron://item/65)` links in any message body (apple #208):
        // resolved through the session's store + sync, opened where an
        // inline item card opens it; a miss stays put and explains itself.
        TrackerItemLinkHost(
            resolve = { num -> deps.trackerItemLinkOutcome(num, session) },
            open = onOpenItem,
        ) { gatedOpenItem ->
            ChatScreen(
                chatVM = chatVM,
                composerVM = composerVM,
                stripVM = stripVM,
                chatTitle = title,
                boxName = boxName,
                sessionShort = sessionShort,
                boxShort = boxShort,
                roomBoxNames = roomBoxNames,
                roomBoxShorts = roomBoxShorts,
                onBack = onBack,
                onOpenChild = onOpenChild,
                onOpenConversation = onOpenConversation,
                onOpenItems = onOpenItems,
                showsBackButton = showsBackButton,
                itemsSupported = itemsSupported,
                needsYouCount = needsYouCount,
                // Through the host's gate, not straight to the route: a card
                // tap must supersede a link resolve still in flight.
                onOpenItem = gatedOpenItem,
                // Deferred: built when the browser sheet opens, on the sheet's own
                // scope, over the same store the sync engine writes (apple #142).
                mediaBrowser = { scope ->
                    MediaBrowserViewModel(
                        store = deps.journalStore(session),
                        convoID = convoID,
                        serverURL = session.homeserverURL.toHttpUrl(),
                        media = deps.mediaService(session),
                        scope = scope,
                    )
                },
            )
        }
    }
}

private data class ParentLookup(val parent: String?)

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
