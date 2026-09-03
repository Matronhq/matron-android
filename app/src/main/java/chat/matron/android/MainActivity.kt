package chat.matron.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import chat.matron.android.designsystem.AppLockShield
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.SyncBannerState
import chat.matron.android.designsystem.syncBannerStateFrom
import chat.matron.android.features.chat.ChatScreen
import chat.matron.android.features.chat.ChatVMCache
import chat.matron.android.features.chat.SubChatView
import chat.matron.android.features.chatlist.ChatListScreen
import chat.matron.android.features.chatlist.NewChatSheet
import chat.matron.android.features.chatlist.currentSummary
import chat.matron.android.features.onboarding.SignInScreen
import chat.matron.android.features.search.SearchScreen
import chat.matron.android.features.settings.DeviceLinkScreen
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
import chat.matron.android.viewmodels.ChatListViewModel
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
 * session, switch signed-out → [SignInScreen] vs signed-in → the [NavHost]. Push
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
    val vmCache = remember(session.userID) { ChatVMCache(deps, session, sessionScope) }
    val chatListVM = remember(session.userID) { ChatListViewModel(deps.chatService(session), sessionScope) }

    var connectionState by remember { mutableStateOf<SyncBannerState>(SyncBannerState.Connecting) }
    var hasEverConnected by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }

    val groups by chatListVM.groups.collectAsStateWithLifecycle()
    val allChats = remember(groups) { groups.flatMap { it.summaries } }

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
            navigate = { id ->
                val entry = nav.currentBackStackEntry
                val alreadyOpen = entry?.destination?.route == "chat/{convoID}" &&
                    entry.arguments?.getString("convoID") == id
                if (!alreadyOpen) nav.navigate("chat/$id")
            },
        )
    }

    LaunchedEffect(session.userID) { chatListVM.start() }
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
        deps.syncService(session).newConversations().collect { convoID ->
            nav.navigate("chat/$convoID")
        }
    }

    NavHost(navController = nav, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                viewModel = chatListVM,
                connectionState = connectionState,
                hasEverConnected = hasEverConnected,
                searchAvailable = deps.search != null,
                chat = deps.chatService(session),
                onOpenChat = { nav.navigate("chat/$it") },
                onNewChat = { showNewChat = true },
                onOpenSearch = { nav.navigate("search") },
                onOpenSettings = { nav.navigate("settings") },
                onSignOut = onSignOut,
            )
        }

        composable(
            route = "chat/{convoID}",
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
                onOpenChild = { nav.navigate("chat/$it") },
                onSwitchTo = { sibling ->
                    nav.navigate("chat/$sibling") {
                        popUpTo("chat/$convoID") { inclusive = true }
                    }
                },
                onOpenConversation = onOpenConversation,
            )
        }

        composable("search") {
            val searchService = deps.search
            if (searchService == null) {
                // Navigation is a side effect — never call it straight from
                // the composable body (bugbot "Search route pops during
                // composition").
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                val searchVM = remember { SearchViewModel(searchService, allChats) }
                val armScope = rememberCoroutineScope()
                SearchScreen(
                    viewModel = searchVM,
                    onSelectChat = { chat -> nav.popBackStack(); nav.navigate("chat/${chat.id}") },
                    onSelectMessage = { hit ->
                        // Arm the (cached) chat VM's in-conversation search
                        // with the query, then navigate: a cold VM parks the
                        // jump until its first snapshot lands (apple #172).
                        val query = searchVM.trimmedQuery
                        val (chatVM, _) = vmCache.viewModels(hit.roomID)
                        armScope.launch { chatVM.beginChatSearch(query) }
                        nav.popBackStack()
                        nav.navigate("chat/${hit.roomID}")
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
                onBack = { nav.popBackStack() },
            )
        }

        composable("devices") {
            DevicesScreen(
                api = deps.devicesService(session),
                onSelfRevoked = onSignOut,
                onBack = { nav.popBackStack() },
                overrides = deps.boxLetterOverrides,
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

    if (showNewChat) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showNewChat = false }, sheetState = sheetState) {
            NewChatSheet(
                api = deps.agentRPCService(session),
                prepareConversation = { id -> deps.prepareConversation(session, id) },
                onCreated = { convoID ->
                    showNewChat = false
                    nav.navigate("chat/$convoID")
                },
                onCancel = { showNewChat = false },
            )
        }
    }
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

private data class ParentLookup(val parent: String?)

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
