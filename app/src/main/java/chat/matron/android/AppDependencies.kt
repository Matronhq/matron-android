package chat.matron.android

import android.content.Context
import android.content.pm.ApplicationInfo
import chat.matron.android.auth.AuthService
import chat.matron.android.auth.JournalAuthService
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.JournalChatService
import chat.matron.android.chat.JournalMediaService
import chat.matron.android.chat.JournalTimelineService
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.TimelineService
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.OkHttpWebSocketConnector
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.UserSession
import chat.matron.android.push.JournalPushService
import chat.matron.android.push.PushService
import chat.matron.android.search.SearchDatabase
import chat.matron.android.search.SearchService
import chat.matron.android.search.SearchServiceLive
import chat.matron.android.storage.EncryptedPrefsSessionStore
import chat.matron.android.storage.SessionStore
import chat.matron.android.storage.LRUCache
import chat.matron.android.storage.StoragePaths
import chat.matron.android.storage.TimelineCacheKey
import chat.matron.android.sync.SyncService
import chat.matron.android.viewmodels.AgentRPCProviding
import chat.matron.android.viewmodels.DevicesProviding
import chat.matron.android.viewmodels.JournalAgentRPCService
import chat.matron.android.viewmodels.JournalDevicesService
import chat.matron.android.viewmodels.KeyValueStore
import chat.matron.android.viewmodels.RecentStartFolders
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Composition root, porting matron-apple's `AppDependencies` (App/AppDependencies.swift).
 *
 * One [JournalCore] (API client + local Room mirror + sync engine) is built per
 * signed-in session; every per-session / per-room service factory below is a thin
 * wrapper over the same core so the engine, store, and API client stay singletons
 * for the session's lifetime. The single [sharedClient] (built with `pingInterval`
 * so the WebSocket keepalive fires) backs both the REST [JournalApi] and the
 * [OkHttpWebSocketConnector], matching the Swift note that one URLSession serves
 * both surfaces.
 *
 * Android adaptations vs iOS:
 * - No App Group container: the Room databases live under [StoragePaths.appSupport].
 * - `FileSessionStore` → [EncryptedPrefsSessionStore] (EncryptedSharedPreferences).
 * - Push is dormant (FCM not wired). [pushService] returns a [JournalPushService]
 *   whose `requestPermission()` is a stub — kept so a future FCM token can flow to
 *   the same `/push/register` endpoint. APNs delegate plumbing is dropped entirely.
 */
class AppDependencies(
    val context: Context,
    /**
     * Test seams (production defaults). The Swift `AppDependencies()` was
     * directly constructible in the test runner because `FileSessionStore` and
     * on-disk SQLite work there; on Android EncryptedSharedPreferences needs the
     * AndroidKeyStore and Room a file, neither hermetic under Robolectric — so the
     * session store and both databases are injectable, letting the smoke test
     * build the whole graph with an in-memory store + in-memory Room.
     */
    private val sessionStoreFactory: (Context) -> SessionStore = { EncryptedPrefsSessionStore.create(it) },
    private val journalDatabaseFactory: (Context, File) -> MatronDatabase = { c, f -> MatronDatabase.open(c, f) },
    private val searchDatabaseFactory: (Context, File) -> SearchDatabase = { c, f -> SearchDatabase.open(c, f) },
) {

    /** One shared OkHttp client (keepalive pings) for REST + WebSocket. */
    private val sharedClient: OkHttpClient = OkHttpWebSocketConnector.defaultClient()

    val auth: AuthService

    /**
     * The local FTS index. `null` only if the Room search DB can't be opened;
     * the journal services all treat search as optional, so the app degrades to
     * "search disabled" rather than failing to launch.
     */
    val search: SearchService?

    /**
     * Per-screen view-model dependencies. Answered-prompt persistence and
     * recent-folder completion were `UserDefaults` singletons on iOS; here they
     * ride one EncryptedSharedPreferences-free plain prefs store, injected into
     * the VMs the UI stage constructs.
     */
    val answeredPromptStore: KeyValueStore
    val recentStartFolders: RecentStartFolders

    /** Where the composer stages picked/pasted attachment copies. */
    val stagingDirectory: File

    private val appSupport: File = StoragePaths.appSupport(context)
    private val journalDirectory: File = File(appSupport, "journal-store").apply { mkdirs() }

    /** Background scope for startup sweeps and sign-out teardown. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One journal stack per signed-in session: the API client, the local Room
     * mirror (+ its database handle), and the sync engine that's the sole writer
     * of that mirror.
     */
    class JournalCore(
        val api: JournalApi,
        val db: MatronDatabase,
        val store: JournalStore,
        val engine: JournalSyncEngine,
    )

    private val cores: MutableMap<String, JournalCore> = mutableMapOf()

    /** Per-session [MediaService] cache — one instance (one image cache) per user. */
    private val mediaServices: MutableMap<String, MediaService> = mutableMapOf()

    /**
     * Per-room [TimelineService] cache, bounded LRU so a long session that visits
     * many rooms doesn't accumulate one timeline handle per room forever. Mirrors
     * the iOS `timelineCache`.
     */
    private var timelineCache = LRUCache<TimelineCacheKey, JournalTimelineService>(timelineCacheLimit)

    init {
        val sessionStore = sessionStoreFactory(context)
        auth = JournalAuthService(sessionStore = sessionStore, client = sharedClient)

        search = runCatching {
            SearchServiceLive(searchDatabaseFactory(context, StoragePaths.searchDb(appSupport)))
        }.getOrNull()

        val prefs = context.getSharedPreferences("matron-kv", Context.MODE_PRIVATE)
        answeredPromptStore = SharedPreferencesKeyValueStore(prefs)
        recentStartFolders = RecentStartFolders(answeredPromptStore)

        stagingDirectory = File(context.cacheDir, "attachments").apply { mkdirs() }
    }

    /** Debuggable builds register sandbox push tokens; release builds are prod. */
    private val pushEnvironment: JournalApi.PushEnvironment
        get() =
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                JournalApi.PushEnvironment.SANDBOX
            } else {
                JournalApi.PushEnvironment.PROD
            }

    /**
     * Builds (or returns the cached) journal stack for [session]. A store that
     * fails to open is unrecoverable config; the exception propagates rather than
     * limping along with a null store every caller would have to guard.
     */
    private fun core(session: UserSession): JournalCore {
        cores[session.userID]?.let { return it }
        val api = JournalApi(
            baseUrl = session.homeserverURL,
            client = sharedClient,
            token = session.accessToken,
        )
        val dbFile = File(journalDirectory, "${session.userID.sanitizedForFilename()}.sqlite")
        val db = journalDatabaseFactory(context, dbFile)
        val store = JournalStore(db = db, ownSender = "user:${session.userID}")
        val engine = JournalSyncEngine(
            api = api,
            store = store,
            connector = OkHttpWebSocketConnector(client = sharedClient),
            token = session.accessToken,
            ownSender = "user:${session.userID}",
            search = search,
        )
        val core = JournalCore(api, db, store, engine)
        cores[session.userID] = core
        // Boot-time TTL sweep of expired tool-output snippets. Kotlin constructors
        // can't suspend, so the composition root drives it once the store exists —
        // documented on JournalStore.purgeExpiredToolOutputSnippets.
        appScope.launch { runCatching { store.purgeExpiredToolOutputSnippets() } }
        return core
    }

    fun syncService(session: UserSession): SyncService = core(session).engine

    fun chatService(session: UserSession): ChatService {
        val core = core(session)
        return JournalChatService(store = core.store, engine = core.engine)
    }

    fun mediaService(session: UserSession): MediaService =
        mediaServices.getOrPut(session.userID) { JournalMediaService(core(session).api) }

    fun pushService(session: UserSession): PushService =
        JournalPushService(api = core(session).api, environment = pushEnvironment)

    /** Devices/pairing surface (Settings → Manage Devices). */
    fun devicesService(session: UserSession): DevicesProviding =
        JournalDevicesService(core(session).api)

    /** New Chat surface: agent roster + recent-folders / start RPCs. */
    fun agentRPCService(session: UserSession): AgentRPCProviding {
        val core = core(session)
        return JournalAgentRPCService(api = core.api, engine = core.engine)
    }

    /**
     * Placeholder conversation row so navigating to a just-started conversation
     * holds even when the `start` answer beats the convo's first journal frame.
     */
    suspend fun prepareConversation(session: UserSession, id: String) {
        core(session).engine.ensurePlaceholderConversation(id = id, title = "New chat")
    }

    /**
     * Per-room [TimelineService] factory, cached by `(userID, roomID)` so repeat
     * navigations reuse the same overlay state instead of rebuilding it.
     */
    fun timelineService(session: UserSession, roomID: String): TimelineService {
        val key = TimelineCacheKey(userID = session.userID, roomID = roomID)
        timelineCache[key]?.let { return it }
        val core = core(session)
        val service = JournalTimelineService(
            convoID = roomID,
            store = core.store,
            engine = core.engine,
            api = core.api,
            session = session,
            search = search,
        )
        timelineCache[key] = service
        return service
    }

    /**
     * The parent conversation id of [convoID], or `null` for a top-level
     * conversation. Backs the nav router's read-only sub-chat vs full-chat
     * decision. Suspend on Android (the Room read is suspend) where iOS was
     * synchronous.
     */
    suspend fun parentConvoID(session: UserSession, convoID: String): String? =
        runCatching { core(session).store.parentConvoID(convoID) }.getOrNull()

    /**
     * Sign-out path. Ends every session's sync engine, wipes and closes its local
     * mirror, clears every per-session/per-room cache, wipes the search index, and
     * drops the persisted auth session. Runs as one sequenced teardown job — push
     * deregistration first (while the token is still valid), then `endSync()` to
     * stop the writer, then `wipe()` + `close()` — so the wipe can never race a
     * still-running sync write. The job closes over its own cores, so it's safe to
     * clear [cores] synchronously right after.
     */
    fun signOut() {
        val oldCores = cores.values.toList()
        teardownJob = appScope.launch {
            for (core in oldCores) {
                withTimeoutOrNull(5_000) { runCatching { core.api.unregisterPush() } }
                core.engine.endSync()
                runCatching { core.store.wipe() }
                runCatching { core.db.close() }
            }
            runCatching { search?.wipe() }
        }
        cores.clear()
        mediaServices.clear()
        timelineCache = LRUCache(timelineCacheLimit)
        runCatching { auth.clearSession() }
    }

    private var teardownJob: Job? = null

    /**
     * Blocks until any pending sign-out teardown finishes. The sign-in path calls
     * this before publishing the new session so no new journal core races the old
     * one's endSync/wipe.
     */
    suspend fun awaitPendingTeardown() {
        teardownJob?.join()
        teardownJob = null
    }

    // MARK: - Test seams (mirror AppDependenciesTests.swift)

    /** Number of entries currently held by the timeline cache. */
    val timelineCacheCount: Int get() = timelineCache.count

    /** Whether the timeline cache currently holds an entry for `(userID, roomID)`. */
    fun timelineCacheContains(userID: String, roomID: String): Boolean =
        timelineCache.contains(TimelineCacheKey(userID = userID, roomID = roomID))

    companion object {
        /** How many distinct rooms the timeline cache holds before LRU eviction. */
        const val timelineCacheLimit = 16
    }
}

/** Keeps a user id usable as a filename (ids are opaque but may carry `/` `:`). */
private fun String.sanitizedForFilename(): String =
    map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }.joinToString("")
