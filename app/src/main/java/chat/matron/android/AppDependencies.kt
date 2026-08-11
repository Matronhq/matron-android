package chat.matron.android

import android.content.Context
import android.content.pm.ApplicationInfo
import chat.matron.android.auth.AuthService
import chat.matron.android.platform.Haptics
import chat.matron.android.platform.SystemHaptics
import chat.matron.android.auth.JournalAuthService
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.JournalChatService
import chat.matron.android.chat.JournalMediaService
import chat.matron.android.chat.JournalTimelineService
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.TimelineService
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.OkHttpWebSocketConnector
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.MatronDebug
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
import chat.matron.android.viewmodels.DeviceLinking
import chat.matron.android.viewmodels.AgentChatProviding
import chat.matron.android.viewmodels.JournalAgentChatService
import chat.matron.android.viewmodels.DevicesProviding
import chat.matron.android.viewmodels.JournalAgentRPCService
import chat.matron.android.viewmodels.JournalDeviceLinkService
import chat.matron.android.viewmodels.JournalDevicesService
import chat.matron.android.viewmodels.KeyValueStore
import chat.matron.android.viewmodels.RecentStartFolders
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import chat.matron.android.models.SyncConnectionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /**
     * Background scope for startup sweeps and sign-out teardown. Injectable so
     * tests can pump teardown jobs on a paused dispatcher and pin down the
     * signOut/awaitPendingTeardown interleavings deterministically.
     */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /**
     * One shared OkHttp client (keepalive pings) for REST + WebSocket. Internal
     * (not private) so the sign-in stage can hand it to [chat.matron.android.journal.RelayApi]
     * for the rendezvous relay, which is unauthenticated and predates a session.
     */
    internal val sharedClient: OkHttpClient = OkHttpWebSocketConnector.defaultClient()

    val auth: AuthService

    /**
     * The local FTS index. `null` only if the Room search DB can't be opened;
     * the journal services all treat search as optional, so the app degrades to
     * "search disabled" rather than failing to launch.
     */
    val search: SearchService?

    /**
     * The app's plain (unencrypted) preference store — the iOS originals'
     * `UserDefaults.standard`. Holds nothing secret: answered-prompt ids,
     * recent start folders, and the app-lock settings, all of which are
     * device-local UI state rather than credentials (those live in the
     * EncryptedSharedPreferences-backed session store).
     */
    val preferences: KeyValueStore

    /**
     * Recent start-folder completion, a `UserDefaults` singleton on iOS; here it
     * rides the same [preferences] store, injected into the VMs the UI stage
     * constructs.
     */
    val recentStartFolders: RecentStartFolders

    /** Where the composer stages picked/pasted attachment copies. */
    val stagingDirectory: File

    /** Foreground haptics singleton. No-op on devices without a vibrator. */
    val haptics: Haptics = SystemHaptics(context)

    private val appSupport: File = StoragePaths.appSupport(context)
    private val journalDirectory: File = File(appSupport, "journal-store").apply { mkdirs() }

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
        /** Boot-time TTL sweep; teardown joins it before wiping the same DB. */
        var purgeJob: Job? = null,
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
        }.onFailure { MatronDebug.breadcrumb("AppDependencies: search DB open failed: $it") }.getOrNull()

        val prefs = context.getSharedPreferences("matron-kv", Context.MODE_PRIVATE)
        preferences = SharedPreferencesKeyValueStore(prefs)
        recentStartFolders = RecentStartFolders(preferences)

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
        // documented on JournalStore.purgeExpiredToolOutputSnippets. Tracked on
        // the core so sign-out teardown joins it before wipe()/close() — an
        // untracked sweep could race the wipe on the same database (bugbot
        // "Boot purge races sign-out wipe").
        core.purgeJob = appScope.launch {
            runCatching { store.purgeExpiredToolOutputSnippets() }
                .onFailure { MatronDebug.breadcrumb("AppDependencies: boot purge failed: $it") }
        }
        return core
    }

    fun syncService(session: UserSession): SyncService = core(session).engine

    /**
     * Bounded background catch-up — the Android analog of iOS's BGAppRefresh
     * handler (`chat.matron.refresh`): ensures the sync engine is running,
     * waits (capped) until the journal is caught up, then gives queued outbox
     * rows a short grace to flush so a send-then-pocket actually delivers.
     * When this call started the engine itself (app not visible), it stops it
     * again so a background process doesn't hold a socket open between runs;
     * an engine the UI started is left untouched.
     */
    suspend fun backgroundCatchUp(session: UserSession, isAppVisible: () -> Boolean = { false }) {
        val engine = core(session).engine
        val startedHere = !engine.isRunning()
        if (startedHere) engine.beginSync()
        // stateStream is a StateFlow: an already-Running engine passes through
        // immediately. The cap bounds the whole wait, not each yield.
        withTimeoutOrNull(15_000) {
            engine.stateStream.first { it is SyncConnectionState.Running }
        }
        withTimeoutOrNull(10_000) {
            while (engine.hasPendingOutbox()) delay(500)
        }
        if (startedHere && !isAppVisible()) engine.endSync()
    }

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

    /**
     * Agent-chat consent surface: answering the cards inline in a chat, and the
     * Settings screen listing the parked requests.
     */
    fun agentChatService(session: UserSession): AgentChatProviding =
        JournalAgentChatService(core(session).api)

    /**
     * Agent-spawn consent surface: answering the card inline in a chat.
     * Unlike [agentChatService] there is no parked-list screen to back —
     * [JournalApi] implements [AgentSpawnAnswering] directly, so this is
     * just the session's existing API client.
     */
    fun agentSpawnService(session: UserSession): AgentSpawnAnswering = core(session).api

    /** Show-QR surface (Settings → Link a Device). */
    fun deviceLinkService(session: UserSession): DeviceLinking =
        JournalDeviceLinkService(core(session).api)

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
     * Live parent linkage for the nav router: re-emits when the mirror learns
     * a child's `parent_convo_id` (convo_meta / snapshot upsert) so a subagent
     * chat opened before the field landed still switches to the read-only
     * sub-chat presentation.
     */
    fun parentConvoIDFlow(session: UserSession, convoID: String): kotlinx.coroutines.flow.Flow<String?> =
        core(session).store.parentConvoIDFlow(convoID)

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
        // Chain onto any previous teardown: overwriting the job would leave
        // awaitPendingTeardown() watching only the newest one while an older
        // wipe/close still runs (bugbot "Sign-out drops prior teardown job").
        val previous = teardownJob
        teardownJob = appScope.launch {
            previous?.join()
            for (core in oldCores) {
                core.purgeJob?.join()
                val pushResult = withTimeoutOrNull(5_000) { runCatching { core.api.unregisterPush() } }
                when {
                    pushResult == null ->
                        MatronDebug.breadcrumb("signOut: unregisterPush timed out after 5s")
                    pushResult.isFailure ->
                        MatronDebug.breadcrumb("signOut: unregisterPush failed: ${pushResult.exceptionOrNull()}")
                }
                core.engine.endSync()
                runCatching { core.store.wipe() }
                    .onFailure { MatronDebug.breadcrumb("signOut: store.wipe failed: $it") }
                // wipe() deliberately preserves the offline outbox (a
                // snapshot_required mirror wipe must not eat unsent messages);
                // sign-out must clear it so the next account can't inherit —
                // or send — the previous user's queued messages.
                runCatching { core.store.wipeOutbox() }
                    .onFailure { MatronDebug.breadcrumb("signOut: store.wipeOutbox failed: $it") }
                runCatching { core.db.close() }
                    .onFailure { MatronDebug.breadcrumb("signOut: db.close failed: $it") }
            }
            runCatching { search?.wipe() }
                .onFailure { MatronDebug.breadcrumb("signOut: search.wipe failed: $it") }
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
        while (true) {
            val job = teardownJob ?: return
            job.join()
            // A signOut() that ran while we were joining chained a newer job onto
            // the field; loop so this caller waits for that one too. The field is
            // deliberately never cleared here — nulling it after the join could
            // drop a just-chained teardown, letting a later sign-in skip its wipe
            // (bugbot "Teardown await drops newer job").
            if (teardownJob === job) return
        }
    }

    /**
     * Removes every on-disk journal mirror plus the shared search index. Fresh
     * interactive sign-in calls this (after [awaitPendingTeardown], before the
     * first core opens): if the process died between `signOut()`'s synchronous
     * `clearSession()` and its background wipe, the previous user's mirror and
     * index survive on disk (bugbot "Sign-out leaves local mirror"). A fresh
     * login resyncs from a server snapshot, so the clean slate costs nothing.
     * Session restore must NOT call this — a restored session keeps its mirror.
     */
    suspend fun wipeLocalDataForFreshLogin() {
        withContext(Dispatchers.IO) {
            journalDirectory.listFiles()?.forEach { file ->
                if (!file.deleteRecursively()) {
                    MatronDebug.breadcrumb("freshLogin: could not delete ${file.name}")
                }
            }
        }
        runCatching { search?.wipe() }
            .onFailure { MatronDebug.breadcrumb("freshLogin: search.wipe failed: $it") }
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
