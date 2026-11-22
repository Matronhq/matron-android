package chat.matron.android.features.share

import chat.matron.android.AppDependencies
import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.UserSession
import chat.matron.android.sync.MainAppVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/// [ShareTransport] over the app's own journal stack: the session's sync
/// engine carries the sends, through the same timeline service the composer
/// uses, so a share is uploaded and posted exactly as an attachment sent
/// from a chat is.
///
/// The share screen often opens while the app is not running. The engine is
/// then started for the share and stopped again once it is through, so a
/// share does not leave a socket open in the background; an engine the app
/// already had running is left alone.
class LiveShareTransport(
    private val deps: AppDependencies,
    private val session: UserSession,
    private val connectTimeoutMillis: Long = 20_000,
    /// Whether the main app is on screen: its engine is then its own, and
    /// the share must not stop it.
    private val isAppVisible: () -> Boolean = { MainAppVisibility.isStarted },
) : ShareTransport {
    private var startedHere = false

    override suspend fun connect() {
        val sync = deps.syncService(session)
        if (!sync.isRunning()) {
            startedHere = true
            sync.start()
        }
        withTimeoutOrNull(connectTimeoutMillis) {
            sync.stateStream.first { it is SyncConnectionState.Running }
        } ?: throw ShareConnectError()
    }

    override suspend fun sendText(convoID: String, body: String) {
        deps.timelineService(session, convoID).sendText(body)
    }

    override suspend fun sendFile(
        convoID: String,
        file: SharedFile,
        caption: String?,
        batch: AttachmentBatchTag?,
        progress: (Double) -> Unit,
    ) {
        // Streamed from our copy, never read into memory whole: a share can
        // be up to the 100 MB upload limit.
        deps.timelineService(session, convoID).sendAttachment(
            file.file, file.filename, file.mimeType, file.isImage, caption, batch, progress,
        )
    }

    override suspend fun finish() {
        // A text send rides the outbox, which flushes on its own once the
        // socket is up; give it a moment to leave before the screen closes.
        withTimeoutOrNull(10_000) {
            while (deps.hasPendingOutbox(session)) delay(250)
        }
        release()
    }

    override fun release() {
        if (!startedHere) return
        startedHere = false
        // Off the screen's scope, which ends as it closes. The pause lets
        // the last frame leave the socket; the close itself is graceful.
        background.launch {
            delay(1_000)
            // The app may have come to the front meanwhile ("Open Matron",
            // or the user switched to it): its own foreground start is a
            // no-op on a running engine, so stopping it now would leave the
            // visible app offline.
            if (isAppVisible()) return@launch
            val sync = deps.syncService(session)
            if (sync.isRunning()) sync.stop()
        }
    }

    private companion object {
        val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
