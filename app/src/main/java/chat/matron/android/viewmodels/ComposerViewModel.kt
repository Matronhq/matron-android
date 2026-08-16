package chat.matron.android.viewmodels

import chat.matron.android.chat.TimelineService
import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.BotCommand
import chat.matron.android.models.BotCommandCatalog
import chat.matron.android.models.StagedAttachment
import java.io.File
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/// Drives the message composer: text input, slash-command palette, recent-folder
/// completion, sent-message recall, and the send / attach actions. Ported from
/// matron-apple's `ComposerViewModel`.
///
/// Platform adaptation: the Swift `attachFiles([URL])` / `StagedAttachment.stage(
/// copying:)` become [attachFiles] over `File`s staged into an injected
/// [stagingDirectory]; [recentFolders] is injected (the UI stage supplies the
/// SharedPreferences-backed store, tests an in-memory one).
class ComposerViewModel(
    val roomID: String,
    private val timeline: TimelineService,
    private val commands: List<BotCommand>,
    private val recentFolders: RecentStartFolders,
    private val stagingDirectory: File,
) {
    /// User-editable input text.
    var input: String = ""

    /// Mac slash palette is also openable via a shortcut; typing `/`/`!` opens it.
    var palettePinnedOpen: Boolean = false

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    /// Live state of an in-flight attachment upload, or null when none. On a
    /// slow uplink a multi-MB screenshot takes long enough that a bare spinner
    /// reads as "frozen" — the composer renders this as a labelled progress
    /// bar ("Uploading photo 1 of 2…") above the input.
    data class UploadProgress(
        val filename: String,
        /// 1-based position within this send's batch.
        val index: Int,
        val count: Int,
        /// Uploaded fraction of the current attachment (0…1).
        val fraction: Double,
    ) {
        /// Display label — batch position when sending several, filename when
        /// sending one.
        val label: String
            get() = if (count > 1) "Uploading $index of $count…" else "Uploading $filename…"
    }

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    /// Attachments picked/pasted/dropped but not yet sent, in add order. Order is
    /// load-bearing: the caption rides on the first one.
    private val _stagedAttachments = MutableStateFlow<List<StagedAttachment>>(emptyList())
    val stagedAttachments: StateFlow<List<StagedAttachment>> = _stagedAttachments.asStateFlow()

    /// True while the user is walking sent-message history via Up/Down.
    private val _isNavigatingHistory = MutableStateFlow(false)
    val isNavigatingHistory: StateFlow<Boolean> = _isNavigatingHistory.asStateFlow()

    /// Index of the keyboard-highlighted palette row, or `null` when none.
    private val _paletteSelection = MutableStateFlow<Int?>(null)
    val paletteSelection: StateFlow<Int?> = _paletteSelection.asStateFlow()

    /// Terminal-style recall of previously-sent messages, keyed by room. Owned
    /// here (not injected) so its lifetime tracks the view model.
    private val history = SentMessageHistory()

    /// The last value a recall write put into [input] — so [handleInputChange]
    /// can tell a programmatic recall from a user keystroke.
    private var lastRecalledValue: String? = null

    /// Input string for which folder suggestions are suppressed (set by
    /// [selectFolder] so the palette closes on pick).
    private var folderSuggestionsSuppressedFor: String? = null

    /// Whether [send] would do anything — an attachment alone is a valid message.
    val canSend: Boolean
        get() = input.trim().isNotEmpty() || _stagedAttachments.value.isNotEmpty()

    /// Whether the slash palette should be visible. Trailing whitespace is NOT
    /// trimmed: `selectCommand` leaves `"/start "` to position the caret for
    /// arguments, which means "command chosen" → palette closed.
    val showPalette: Boolean
        get() {
            if (palettePinnedOpen) return true
            if (folderSuggestions.isNotEmpty()) return true
            val leading = input.dropWhile { it == ' ' || it == '\t' }
            if (!(leading.startsWith("/") || leading.startsWith("!"))) return false
            return leading.split(" ").size == 1
        }

    /// Filtered command list for the current input (leading whitespace stripped
    /// so `showPalette` and the filter agree).
    val filteredCommands: List<BotCommand>
        get() = BotCommandCatalog.filter(commands, input.dropWhile { it == ' ' || it == '\t' })

    /// Replaces the input with the chosen command's trigger plus a trailing
    /// space, ready for arguments. Closes the pinned palette.
    fun selectCommand(command: BotCommand) {
        input = command.trigger + " "
        palettePinnedOpen = false
    }

    /// Rows the palette shows: folder suggestions in folder-completion mode,
    /// filtered commands otherwise.
    val paletteItemCount: Int
        get() = if (folderSuggestions.isEmpty()) filteredCommands.size else folderSuggestions.size

    /// Down-arrow: highlight the first row, or step down, clamping at the last.
    /// No-op during a history walk (the arrows keep walking history).
    fun paletteMoveDown() {
        val count = paletteItemCount
        if (!showPalette || _isNavigatingHistory.value || count == 0) return
        val current = _paletteSelection.value
        _paletteSelection.value = minOf(current?.plus(1) ?: 0, count - 1)
    }

    /// Up-arrow: step up, clamping at the first row; with no highlight, start at
    /// the last. No-op during a history walk.
    fun paletteMoveUp() {
        val count = paletteItemCount
        if (!showPalette || _isNavigatingHistory.value || count == 0) return
        val current = _paletteSelection.value
        _paletteSelection.value = maxOf(current?.minus(1) ?: (count - 1), 0)
    }

    /// Return-key: picks the highlighted palette row. Returns `true` when a row
    /// was picked (caller must not send), `false` when nothing is highlighted.
    fun confirmPaletteSelection(): Boolean {
        if (!showPalette) return false
        val index = _paletteSelection.value ?: return false
        _paletteSelection.value = null
        val folders = folderSuggestions
        if (folders.isNotEmpty()) {
            if (index !in folders.indices) return false
            selectFolder(folders[index])
            return true
        }
        val cmds = filteredCommands
        if (index !in cmds.indices) return false
        selectCommand(cmds[index])
        return true
    }

    /// Recent-folder suggestions for the current input (palette-friendly count).
    /// Non-empty only in folder-completion mode; a suggestion identical to what's
    /// typed is filtered out.
    val folderSuggestions: List<String>
        get() {
            if (input == folderSuggestionsSuppressedFor) return emptyList()
            val partial = folderCompletionPartial ?: return emptyList()
            return recentFolders.matches(partial)
                .filter { !it.equals(partial, ignoreCase = true) }
                .take(8)
        }

    /// Rewrites the input so the trailing partial path token is replaced by the
    /// chosen folder, keeping the command and any flags. No trailing space.
    fun selectFolder(path: String) {
        val lastWhitespace = input.indexOfLast { it.isWhitespace() }
        input = input.substring(0, lastWhitespace + 1) + path
        folderSuggestionsSuppressedFor = input
        palettePinnedOpen = false
    }

    /// The partial path token when the input is in folder-completion mode: a
    /// `/start`/`/workdir` command (`/` or `!` prefix) followed by whitespace and
    /// at most one more token with no trailing whitespace. `null` otherwise.
    private val folderCompletionPartial: String?
        get() {
            val leading = input.dropWhile { it == ' ' || it == '\t' }
            val first = leading.firstOrNull() ?: return null
            if (first != '/' && first != '!') return null
            val body = leading.drop(1)
            val commandEnd = body.indexOfFirst { it.isWhitespace() }
            if (commandEnd < 0) return null
            val command = body.substring(0, commandEnd)
            if (command != "start" && command != "workdir") return null
            val partial = body.substring(commandEnd).dropWhile { it.isWhitespace() }
            if (partial.any { it.isWhitespace() }) return null
            return partial
        }

    /// Sends the composer's contents: staged attachments (carrying the text as
    /// their caption) or, with nothing attached, the text on its own. No-op when
    /// there's neither. On failure records [sendError] and preserves whatever
    /// didn't go out.
    suspend fun send() {
        val trimmed = input.trim()
        val attachments = _stagedAttachments.value
        if (trimmed.isEmpty() && attachments.isEmpty()) return
        _isSending.value = true
        try {
            // Clear in the SAME tick as the tap, before the round-trip — a late
            // clear leaves a window where a focused field writes its cached value
            // back over it (the message sent, but the text stayed in the field).
            val pending = input
            input = ""
            _stagedAttachments.value = emptyList()
            lastRecalledValue = null
            _isNavigatingHistory.value = false
            folderSuggestionsSuppressedFor = null
            ComposerDraftMemory.forget(roomID)

            try {
                if (attachments.isEmpty()) {
                    timeline.sendText(trimmed)
                } else {
                    sendAttachments(attachments, trimmed)
                }
                if (trimmed.isNotEmpty()) history.record(trimmed, roomID)
                recentFolderArgument(trimmed)?.let { recentFolders.record(it) }
                _sendError.value = null
            } catch (failure: AttachmentSendFailure) {
                _sendError.value = failure.underlying.message ?: failure.underlying.toString()
                // Whatever didn't go out goes back in the tray, ahead of anything
                // attached while the send was in flight.
                _stagedAttachments.value = failure.unsent + _stagedAttachments.value
                // If the caption's attachment made it, the text HAS been
                // delivered — restoring it would show already-sent words.
                if (failure.captionDelivered) return
                restoreInput(pending)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _sendError.value = error.message ?: error.toString()
                restoreInput(pending)
            }
        } finally {
            _isSending.value = false
        }
    }

    private var commandInFlight = false

    /// Sends [text] as a plain message through the same timeline path as [send],
    /// bypassing the composer input and attachment tray. Used by one-tap
    /// affordances such as the compact-context header. Records [sendError] only
    /// on its own failure — a success does NOT clear a pre-existing composer
    /// error, which belongs to the [send] path. A repeated tap while a command
    /// is already in flight is ignored, so an impatient double-tap can't queue a
    /// second bare send. Never mutates [input] or the staged attachments.
    suspend fun sendCommand(text: String) {
        if (commandInFlight) return
        commandInFlight = true
        try {
            timeline.sendText(text)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _sendError.value = error.message ?: error.toString()
        } finally {
            commandInFlight = false
        }
    }

    /// Puts the user's text back after a failed send — unless they've moved on
    /// (a late failure must not overwrite a message typed in the meantime).
    private fun restoreInput(pending: String) {
        if (input.isNotEmpty()) return
        input = pending
        ComposerDraftMemory.store(roomID, pending)
    }

    /// Uploads staged attachments in order, hanging the caption on the first.
    ///
    /// The caption goes on ONE attachment because the bridge injects each
    /// media event as its own prompt (or, when the frames carry a batch tag,
    /// folds them into one prompt — either way a repeated caption would make
    /// claude read the same sentence once per photo). First rather than last
    /// matches every other chat client, and means claude has the context
    /// before it sees the pictures.
    ///
    /// Stops at the first failure instead of pressing on.
    private suspend fun sendAttachments(attachments: List<StagedAttachment>, caption: String) {
        var captionDelivered = false
        // One batch id for the whole send, but only when there IS a batch: a
        // single attachment goes untagged, so its journal frame is
        // byte-identical to what an older bridge already understands. The
        // bridge uses the tag to gather these sequential uploads back into
        // the one message the user wrote, instead of starting a turn on the
        // first image and busy-queueing the rest.
        val batchID = if (attachments.size > 1) UUID.randomUUID().toString() else null
        try {
            attachments.forEachIndexed { index, attachment ->
                val itemCaption = if (index == 0 && caption.isNotEmpty()) caption else null
                val batchIndex = index + 1
                val batch = batchID?.let { AttachmentBatchTag(id = it, index = batchIndex, total = attachments.size) }
                _uploadProgress.value = UploadProgress(
                    filename = attachment.filename, index = batchIndex,
                    count = attachments.size, fraction = 0.0,
                )
                // Fraction updates arrive on an OkHttp writer thread; drop
                // stale ones that land after this attachment (or the whole
                // batch) has moved on.
                val onProgress: (Double) -> Unit = { fraction ->
                    _uploadProgress.value = _uploadProgress.value
                        ?.takeIf { it.index == batchIndex }
                        ?.copy(fraction = fraction)
                        ?: _uploadProgress.value
                }
                try {
                    // Off-main file read: a multi-MB staged screenshot loaded
                    // synchronously on the main dispatcher froze the composer
                    // for visible fractions of a second.
                    val data = withContext(Dispatchers.IO) { attachment.file.readBytes() }
                    if (attachment.isImage) {
                        timeline.sendImage(
                            data, attachment.filename, attachment.mimeType, itemCaption, batch, onProgress,
                        )
                    } else {
                        timeline.sendFile(
                            data, attachment.filename, attachment.mimeType, itemCaption, batch, onProgress,
                        )
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    throw AttachmentSendFailure(
                        underlying = error,
                        unsent = attachments.subList(index, attachments.size).toList(),
                        captionDelivered = captionDelivered,
                    )
                }
                attachment.deleteStagedCopy()
                if (itemCaption != null) captionDelivered = true
            }
        } finally {
            _uploadProgress.value = null
        }
    }

    /// Carries context out of [sendAttachments] for [send] to restore exactly
    /// what didn't go out.
    private class AttachmentSendFailure(
        val underlying: Throwable,
        val unsent: List<StagedAttachment>,
        val captionDelivered: Boolean,
    ) : Exception()

    /// Up-arrow: recalls an older sent message into [input], terminal-style.
    /// No-op when there's no older entry. Enters navigation mode on success.
    fun recallOlder() {
        val text = history.recallOlder(roomID, input) ?: return
        applyRecalled(text)
        _isNavigatingHistory.value = true
    }

    /// Down-arrow: walks forward toward newer messages, finally restoring the
    /// stashed draft (exiting navigation) past the newest. No-op unless walking.
    fun recallNewer() {
        if (!_isNavigatingHistory.value) return
        val text = history.recallNewer(roomID) ?: return
        applyRecalled(text)
        _isNavigatingHistory.value = history.isNavigating
    }

    /// Called on every input mutation. A user edit exits history navigation and
    /// clears the palette highlight + folder suppression.
    fun handleInputChange() {
        _paletteSelection.value = null
        folderSuggestionsSuppressedFor?.let { if (input != it) folderSuggestionsSuppressedFor = null }
        if (input == lastRecalledValue) return
        lastRecalledValue = null
        if (_isNavigatingHistory.value) {
            _isNavigatingHistory.value = false
            history.endRecall()
        }
    }

    /// Exits an active history walk, restoring the stashed in-progress draft.
    /// Called on disappear BEFORE persisting the draft. No-op outside navigation.
    fun exitHistoryNavigation() {
        if (!_isNavigatingHistory.value) return
        _isNavigatingHistory.value = false
        val draft = history.cancelRecall() ?: return
        applyRecalled(draft)
    }

    private fun applyRecalled(text: String) {
        lastRecalledValue = text
        input = text
    }

    /// Extracts the folder-path argument from a sent `/start`/`/workdir` command
    /// line (`/` or `!` prefix), skipping leading `--flag` tokens. `null` when the
    /// line isn't such a command or carries no path.
    fun recentFolderArgument(text: String): String? = Companion.recentFolderArgument(text)

    /// Stages each file into the tray rather than sending it. Unreadable files
    /// are reported via [sendError] and staged nothing.
    suspend fun attachFiles(files: List<File>) {
        for (file in files) {
            try {
                _stagedAttachments.value = _stagedAttachments.value + StagedAttachment.stage(file, stagingDirectory)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _sendError.value = error.message ?: error.toString()
            }
        }
    }

    /// Drops one attachment from the tray (the tray's per-item ✕).
    fun removeAttachment(id: String) {
        val attachment = _stagedAttachments.value.firstOrNull { it.id == id } ?: return
        _stagedAttachments.value = _stagedAttachments.value.filterNot { it.id == id }
        attachment.deleteStagedCopy()
    }

    /// Drops every staged attachment and deletes their copies.
    fun discardAttachments() {
        _stagedAttachments.value.forEach { it.deleteStagedCopy() }
        _stagedAttachments.value = emptyList()
    }

    /// Sends a recorded voice note (a temp `.m4a`) as a `file` attachment with an
    /// `audio/*` content type. [duration] is informational. On success the temp
    /// file is deleted. On failure the file is deliberately left in place —
    /// deleting a recording nobody could recover is permanent data loss with
    /// nothing to show for it; the caller still has the [File] handle and can
    /// retry the same send.
    suspend fun sendVoiceNote(file: File, duration: Duration) {
        try {
            timeline.sendFile(file.readBytes(), "voice-note.m4a", "audio/mp4", null)
            _sendError.value = null
            runCatching { file.delete() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _sendError.value = error.message ?: error.toString()
        }
    }

    /// Surfaces a composer-level error that occurs outside [send]/[attachFiles]
    /// — attachment-staging failures (picker read errors), and mic/voice-note
    /// failures (permission denied, recorder start failure) reported by the UI.
    fun reportAttachmentError(message: String) {
        _sendError.value = message
    }

    /// Clears a shown [sendError] — the composer's dismissible error banner's
    /// close action.
    fun dismissError() {
        _sendError.value = null
    }

    companion object {
        /// Whether the attachment/voice-note controls are available (server
        /// whitelists `file`/`image` sends backed by `POST /media`).
        const val MEDIA_AVAILABLE = true

        /// Pure helper: the folder-path argument to record from a sent
        /// `/start`/`/workdir` command line, or `null`.
        fun recentFolderArgument(text: String): String? {
            val trimmed = text.trim()
            val first = trimmed.firstOrNull() ?: return null
            if (first != '/' && first != '!') return null
            val tokens = trimmed.drop(1).split(Regex("\\s+")).filter { it.isNotEmpty() }
            val command = tokens.firstOrNull() ?: return null
            if (command != "start" && command != "workdir") return null
            for (token in tokens.drop(1)) {
                if (!token.startsWith("--")) return token
            }
            return null
        }
    }
}
