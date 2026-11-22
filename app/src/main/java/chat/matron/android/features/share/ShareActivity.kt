package chat.matron.android.features.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import chat.matron.android.AppDependencies
import chat.matron.android.MatronApplication
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.chatlist.NewChatSheet
import chat.matron.android.models.UserSession
import chat.matron.android.platform.AndroidBiometricAuthenticator
import chat.matron.android.viewmodels.AppLockController
import chat.matron.android.viewmodels.CoordinatorSetting
import chat.matron.android.viewmodels.KeyValueBoxCapacityCache
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// "Send to Matron" in the system share sheet: text, links, pictures and
/// files, one or several, into a conversation or a new one.
///
/// It runs in the app's own process and reuses the signed-in session, the
/// local chat list and the composer's upload path. The app lock is honoured
/// first: while the lock is engaged the lock shield is drawn instead of
/// anything else, exactly as in the main app. Its own [AppLockController]
/// starts locked whenever the lock is on (the cold-launch rule), so a share
/// always asks, however recently the app itself was unlocked.
class ShareActivity : FragmentActivity() {
    private lateinit var appLock: AppLockController
    private val holder: Holder by viewModels()

    /// Keeps the share across a configuration change, and deletes its
    /// copies once the screen is gone for good.
    class Holder : ViewModel() {
        var model: ShareViewModel? = null
        var workDirectory: File? = null

        override fun onCleared() {
            model?.close()
            workDirectory?.deleteRecursively()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deps = (application as MatronApplication).dependencies
        appLock = AppLockController(auth = AndroidBiometricAuthenticator(this), store = deps.preferences)
        val model = holder.model ?: makeModel(deps).also { holder.model = it; it.start() }

        val prefs = getSharedPreferences("matron-kv", MODE_PRIVATE)
        val appearance = MatronAppearance.fromStored(prefs.getString(MatronAppearance.STORAGE_KEY, null))
        setContent {
            MatronTheme(appearance = appearance) {
                Surface(Modifier.fillMaxSize()) { ShareContent(deps, model) }
            }
        }
    }

    private fun makeModel(deps: AppDependencies): ShareViewModel {
        val root = File(filesDir, WORK_ROOT)
        val work = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        // Copies left by a share the system ended part-way.
        ShareItemLoader.clearStale(root, keep = work)
        holder.workDirectory = work
        val content = ShareIntentContent.from(intent, packageName)
        val source = ContentResolverItemSource(contentResolver)
        val environment = object : ShareEnvironment {
            override suspend fun restoreSession(): UserSession? = deps.auth.restoreSession()

            override fun targets(session: UserSession): Flow<List<ShareTarget>> =
                combine(
                    deps.chatService(session).chatSummaries(),
                    CoordinatorSetting(session.userID, deps.preferences).convoID,
                ) { summaries, coordinator -> ShareTargets.from(summaries, coordinator) }.distinctUntilChanged()

            override suspend fun load(uri: Uri): SharedFile =
                withContext(Dispatchers.IO) { ShareItemLoader.load(uri, source, work) }

            override fun transport(session: UserSession): ShareTransport = LiveShareTransport(deps, session)
        }
        return ShareViewModel(content, environment, holder.viewModelScope)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun ShareContent(deps: AppDependencies, model: ShareViewModel) {
        val scope = rememberCoroutineScope()
        val isLocked by appLock.isLocked.collectAsStateWithLifecycle()
        val phase by model.phase.collectAsStateWithLifecycle()
        // Read so the screen redraws on each; the state is rebuilt below.
        model.files.collectAsStateWithLifecycle().value
        model.message.collectAsStateWithLifecycle().value
        model.query.collectAsStateWithLifecycle().value
        model.targets.collectAsStateWithLifecycle().value
        model.isLoadingTargets.collectAsStateWithLifecycle().value
        model.destination.collectAsStateWithLifecycle().value
        model.errorMessage.collectAsStateWithLifecycle().value
        val choosingNew by model.isChoosingNewConversation.collectAsStateWithLifecycle()

        LaunchedEffect(phase) {
            if (phase == ShareViewModel.Phase.Sent) {
                // Long enough to read "Sent", short enough not to be waited on.
                delay(700)
                finish()
            }
        }

        if (isLocked) {
            val authenticating by appLock.isAuthenticating.collectAsStateWithLifecycle()
            val error by appLock.authError.collectAsStateWithLifecycle()
            ShareLockedScreen(
                isAuthenticating = authenticating,
                errorMessage = error,
                onUnlock = { scope.launch { appLock.unlock() } },
                onCancel = ::finish,
            )
            return
        }

        ShareScreen(
            state = ShareScreenState.of(model, phase),
            onCancel = ::finish,
            onSend = model::send,
            onMessageChange = model::setMessage,
            onQueryChange = model::setQuery,
            onSelect = model::select,
            onSelectNewConversation = model::selectNewConversation,
            onRemoveFile = model::removeFile,
            onOpenApp = {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                finish()
            },
        )

        val session = remember(phase) { model.signedInSession }
        if (choosingNew && session != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = model::newConversationCancelled, sheetState = sheetState) {
                NewChatSheet(
                    api = deps.agentRPCService(session),
                    capacityCache = KeyValueBoxCapacityCache(session.userID, deps.preferences),
                    prepareConversation = { id -> deps.prepareConversation(session, id) },
                    onCreated = model::newConversationStarted,
                    onCancel = model::newConversationCancelled,
                )
            }
        }
    }

    private companion object {
        /// Under the app's private files, never the cache: the system may
        /// clear the cache while a share is still on screen.
        const val WORK_ROOT = "share-inbox"
    }
}
