package chat.matron.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import chat.matron.android.features.settings.DevicesScreen
import chat.matron.android.journal.RelayApi
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.ChatListViewModel
import chat.matron.android.viewmodels.LinkSignInViewModel
import chat.matron.android.viewmodels.RendezvousSignInViewModel
import chat.matron.android.viewmodels.SearchViewModel
import chat.matron.android.viewmodels.SignInViewModel
import kotlinx.coroutines.launch

/**
 * Single-activity Compose host. Ports App/MatronApp.swift: bootstrap the persisted
 * session, switch signed-out → [SignInScreen] vs signed-in → the [NavHost]. Push
 * (APNs/FCM) and its notification-tap deep-link are NOT wired — Android push is
 * dormant; those iOS `.task`s are dropped (see the class docs).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = (application as MatronApplication).dependencies
        setContent { MatronApp(deps) }
    }
}

@Composable
private fun MatronApp(deps: AppDependencies) {
    val context = deps.context
    val prefs = remember { context.getSharedPreferences("matron-kv", android.content.Context.MODE_PRIVATE) }
    var appearance by remember {
        mutableStateOf(MatronAppearance.fromStored(prefs.getString(MatronAppearance.STORAGE_KEY, null)))
    }
    val scope = rememberCoroutineScope()

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

            when {
                !bootstrapped -> LoadingScreen()
                session == null -> {
                    val vm = remember { SignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android") }
                    val linkVm = remember {
                        LinkSignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android", scope = scope)
                    }
                    val rendezvousVm = remember {
                        RendezvousSignInViewModel(
                            relay = RelayApi(client = deps.sharedClient),
                            link = linkVm,
                            scope = scope,
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
                                session = s
                            }
                        },
                    )
                }
                else -> SignedInApp(
                    deps = deps,
                    session = session!!,
                    appearance = appearance,
                    onAppearanceChange = { next ->
                        appearance = next
                        prefs.edit().putString(MatronAppearance.STORAGE_KEY, next.rawValue).apply()
                    },
                    onSignOut = {
                        deps.signOut()
                        session = null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInApp(
    deps: AppDependencies,
    session: UserSession,
    appearance: MatronAppearance,
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

    LaunchedEffect(session.userID) { chatListVM.start() }
    LaunchedEffect(session.userID) {
        val sync = deps.syncService(session)
        runCatching { sync.start() }
        sync.stateStream.collect { state ->
            connectionState = syncBannerStateFrom(state)
            if (state is SyncConnectionState.Running) hasEverConnected = true
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
                onBack = { nav.popBackStack() },
                onOpenChild = { nav.navigate("chat/$it") },
                onSwitchTo = { sibling ->
                    nav.navigate("chat/$sibling") {
                        popUpTo("chat/$convoID") { inclusive = true }
                    }
                },
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
                SearchScreen(
                    viewModel = searchVM,
                    onSelectChat = { chat -> nav.popBackStack(); nav.navigate("chat/${chat.id}") },
                    onSelectMessage = { hit -> nav.popBackStack(); nav.navigate("chat/${hit.roomID}") },
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

        composable("link-device") {
            DeviceLinkScreen(
                api = deps.deviceLinkService(session),
                serverURL = session.homeserverURL,
                relay = RelayApi(client = deps.sharedClient),
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
        )
    } else {
        val (chatVM, composerVM) = vmCache.viewModels(convoID)
        val stripVM = vmCache.stripViewModel(convoID)
        ChatScreen(
            chatVM = chatVM,
            composerVM = composerVM,
            stripVM = stripVM,
            chatTitle = title,
            onBack = onBack,
            onOpenChild = onOpenChild,
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
