package chat.matron.android.viewmodels

import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.StagedAttachment
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// What a reply box holds but hasn't sent: the typed text and the staged
/// attachments (voice notes put back after a failed send included).
data class UnsentReply(
    val text: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank() && attachments.isEmpty()
}

/// A send recorded on disk by an earlier run of the app that never reported
/// back: the app stopped while it was uploading or being queued. [queuing]
/// says it had reached the outbox insert, so it may have been queued (and
/// even delivered) after all; the owner checks before putting it back.
/// [startedAt] (epoch ms) is when the send began, so the owner only matches
/// it against comments posted since.
data class StrandedSend(val reply: UnsentReply, val queuing: Boolean, val startedAt: Long = 0)

/// Keeps every reply box's unsent contents on disk, one small JSON file per
/// item or conversation, so a reply survives leaving its page, its view model
/// being evicted, and the app restarting. Only a successful send, or the user
/// clearing the box, removes it. The files the reply holds stay where they
/// were staged ([stagingDirectory]); this records which of them belong to
/// which box. Ported from matron-apple's `UnsentReplyStore`.
///
/// A box also records the replies it is sending ([beginSend]/[endSend]) until
/// each is queued or comes back, under the outbox local id the send uses. If
/// the app dies in between, the next run finds them as [strandedSends]; the
/// owner puts back the ones that never reached the outbox and drops the rest
/// ([dropStranded]), so a queued reply is never offered for sending twice.
///
/// Sign-out wipes it all ([wipeAll]) and bumps [epoch]: view models pass the
/// epoch they were made under with every write, so a send that finishes after
/// sign-out can never save the old account's reply again.
///
/// Both directories should be app-private and excluded from backups
/// (`noBackupFilesDir`).
class UnsentReplyStore(
    val directory: File,
    val stagingDirectory: File,
) {
    sealed interface Place {
        data class Item(val id: String) : Place
        data class Conversation(val id: String) : Place

        /// The file's name: the kind, then the id with anything unsafe in a
        /// file name percent-escaped.
        val fileName: String
            get() {
                val (kind, id) = when (this) {
                    is Item -> "item" to id
                    is Conversation -> "conversation" to id
                }
                return "$kind-${escape(id)}.json"
            }
    }

    private data class Record(
        val box: UnsentReply = UnsentReply(),
        /// Replies between Send and queued, by send id — this run's and,
        /// until resolved, earlier runs' ([stranded] names those).
        val sending: Map<String, StrandedSend> = emptyMap(),
        val stranded: Set<String> = emptySet(),
    ) {
        val isEmpty: Boolean get() = box.isEmpty && sending.isEmpty()
    }

    private val cache = mutableMapOf<Place, Record>()

    /// Bumped by [wipeAll].
    @Volatile var epoch: Int = 0
        private set

    init {
        directory.mkdirs()
        stagingDirectory.mkdirs()
    }

    /// What [place]'s box holds.
    @Synchronized
    fun reply(place: Place): UnsentReply = record(place).box

    /// Replaces [place]'s box. An empty box with nothing sending deletes the
    /// file. Ignored for a stale [epoch].
    @Synchronized
    fun save(place: Place, reply: UnsentReply, epoch: Int) {
        if (epoch != this.epoch) return
        write(place, record(place).copy(box = reply))
    }

    /// Records [reply] as sending under [id] until [endSend].
    @Synchronized
    fun beginSend(place: Place, id: String, reply: UnsentReply, epoch: Int) {
        if (epoch != this.epoch || reply.isEmpty) return
        val record = record(place)
        val send = StrandedSend(reply, queuing = false, startedAt = System.currentTimeMillis())
        write(place, record.copy(sending = record.sending + (id to send)))
    }

    /// The send [id] is about to be written to the outbox: from here on, a
    /// stranded copy may already be queued.
    @Synchronized
    fun markQueuing(place: Place, id: String, epoch: Int) {
        if (epoch != this.epoch) return
        val record = record(place)
        val send = record.sending[id] ?: return
        write(place, record.copy(sending = record.sending + (id to send.copy(queuing = true))))
    }

    /// Sends an earlier run of the app recorded and never finished, by id.
    @Synchronized
    fun strandedSends(place: Place): Map<String, StrandedSend> {
        val record = record(place)
        return record.sending.filterKeys { it in record.stranded }
    }

    /// A stranded send has been dealt with: put back by its owner, or found
    /// queued.
    @Synchronized
    fun dropStranded(place: Place, id: String, epoch: Int) {
        if (epoch != this.epoch) return
        val record = record(place)
        if (id !in record.stranded) return
        write(place, record.copy(sending = record.sending - id, stranded = record.stranded - id))
    }

    /// The send [id] was queued, or has been put back in its box.
    @Synchronized
    fun endSend(place: Place, id: String, epoch: Int) {
        if (epoch != this.epoch) return
        val record = record(place)
        if (id !in record.sending) return
        write(place, record.copy(sending = record.sending - id))
    }

    /// Sign-out: every saved box and every staged file goes.
    @Synchronized
    fun wipeAll() {
        epoch += 1
        cache.clear()
        directory.listFiles()?.forEach { it.deleteRecursively() }
        stagingDirectory.listFiles()?.forEach { it.deleteRecursively() }
    }

    /// Deletes staged files no saved box or send refers to — copies left by a
    /// crash between staging and saving. Call at launch, before any reply box
    /// exists. Nothing is deleted unless every saved box could be read: one
    /// that can't may name any of the files.
    @Synchronized
    fun sweepOrphans() {
        val saved = directory.listFiles() ?: return
        val kept = mutableSetOf<String>()
        for (file in saved.filter { it.extension == "json" }) {
            val record = runCatching { json.decodeFromString(SavedRecord.serializer(), file.readText()) }
                .getOrNull() ?: return
            (record.box.attachments + record.sending.values.flatMap { it.attachments }).forEach { kept += it.id }
        }
        stagingDirectory.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
    }

    // MARK: Disk

    private fun record(place: Place): Record {
        cache[place]?.let { return it }
        // Sends on disk were started by an earlier run: stranded.
        val loaded = load(place)
        val record = loaded.copy(stranded = loaded.sending.keys)
        cache[place] = record
        return record
    }

    private fun load(place: Place): Record {
        val file = File(directory, place.fileName)
        val saved = runCatching { json.decodeFromString(SavedRecord.serializer(), file.readText()) }
            .getOrNull() ?: return Record()
        // A send whose files have all gone was queued (they are deleted once
        // it is) just before the app stopped: not put back.
        val sending = saved.sending.mapNotNull { (id, send) ->
            val reply = send.toReply()
            if (send.attachments.isNotEmpty() && reply.attachments.isEmpty()) null
            else id to StrandedSend(reply, send.queuing, send.startedAt)
        }.toMap()
        return Record(box = saved.box.toReply(), sending = sending)
    }

    private fun write(place: Place, record: Record) {
        cache[place] = record
        val file = File(directory, place.fileName)
        if (record.isEmpty) {
            file.delete()
            return
        }
        val saved = SavedRecord(
            box = SavedReply.of(record.box),
            sending = record.sending.mapValues { SavedReply.of(it.value.reply, it.value.queuing, it.value.startedAt) },
        )
        runCatching {
            directory.mkdirs()
            val tmp = File(directory, "${place.fileName}.tmp")
            tmp.writeText(json.encodeToString(SavedRecord.serializer(), saved))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun SavedReply.toReply() = UnsentReply(
        text = text,
        attachments = attachments.mapNotNull { a ->
            StagedAttachment.restore(
                id = a.id,
                file = File(File(stagingDirectory, a.id), a.filename),
                filename = a.filename,
                mimeType = a.mimeType,
                sizeBytes = a.sizeBytes,
                batchTag = if (a.batchID != null && a.batchIndex != null && a.batchTotal != null) {
                    AttachmentBatchTag(id = a.batchID, index = a.batchIndex, total = a.batchTotal)
                } else {
                    null
                },
                stagingDirectory = stagingDirectory,
            )
        },
    )

    @Serializable
    private data class SavedRecord(
        val box: SavedReply,
        val sending: Map<String, SavedReply> = emptyMap(),
    )

    @Serializable
    private data class SavedReply(
        val text: String,
        val attachments: List<SavedAttachment> = emptyList(),
        val queuing: Boolean = false,
        val startedAt: Long = 0,
    ) {
        companion object {
            fun of(reply: UnsentReply, queuing: Boolean = false, startedAt: Long = 0) = SavedReply(
                text = reply.text,
                queuing = queuing,
                startedAt = startedAt,
                attachments = reply.attachments.map {
                    SavedAttachment(
                        id = it.id, filename = it.file.name, mimeType = it.mimeType, sizeBytes = it.sizeBytes,
                        batchID = it.batchTag?.id, batchIndex = it.batchTag?.index, batchTotal = it.batchTag?.total,
                    )
                },
            )
        }
    }

    @Serializable
    private data class SavedAttachment(
        val id: String,
        val filename: String,
        val mimeType: String,
        val sizeBytes: Long,
        val batchID: String? = null,
        val batchIndex: Int? = null,
        val batchTotal: Int? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun escape(id: String): String = buildString {
            for (byte in id.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_') append(ch)
                else append('%').append(String.format(java.util.Locale.US, "%02X", c))
            }
        }

        /// [reply] ahead of [box]: its attachments first, its text after
        /// whatever [box] already says. An attachment already there isn't
        /// added twice.
        fun merge(reply: UnsentReply, box: UnsentReply): UnsentReply {
            val known = box.attachments.map { it.id }.toSet()
            val attachments = reply.attachments.filter { it.id !in known } + box.attachments
            val typed = box.text.trim()
            val back = reply.text.trim()
            val text = when {
                back.isEmpty() -> box.text
                typed.isEmpty() -> reply.text
                else -> "$typed\n\n$back"
            }
            return UnsentReply(text, attachments)
        }
    }
}
