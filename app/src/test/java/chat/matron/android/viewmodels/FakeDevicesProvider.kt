package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults
import chat.matron.android.journal.BoxDefaultsUpdate
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairPreview
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/// Recording fake for the devices/pairing API surface. Rosters are served FIFO
/// from [rosters] (the last one repeats); errors are thrown per-call. Ported from
/// matron-apple's `FakeDevicesProvider`. The `holdPreview`/`holdApprove` gates
/// replace the Swift `CheckedContinuation` gates with `CompletableDeferred`.
class FakeDevicesProvider : DevicesProviding {
    val tags = mutableListOf<Pair<Long, String?>>()
    val approvalTags = mutableListOf<String?>()
    var tagError: JournalApiError? = null
    var rosters: MutableList<List<DeviceDTO>> = mutableListOf(emptyList())
    var devicesError: JournalApiError? = null
    var revokeError: JournalApiError? = null
    var renameError: JournalApiError? = null
    var previewResult: Result<PairPreview> = Result.failure(JournalApiError.NotFound)
    var approveError: JournalApiError? = null
    var previewDelay: Duration = Duration.ZERO
    var approveDelay: Duration = Duration.ZERO
    var holdPreview = false
    var holdApprove = false

    private val previewGates = mutableListOf<CompletableDeferred<Unit>>()
    private val approveGates = mutableListOf<CompletableDeferred<Unit>>()

    fun releasePreview() {
        previewGates.forEach { it.complete(Unit) }
        previewGates.clear()
    }

    fun releaseApprove() {
        approveGates.forEach { it.complete(Unit) }
        approveGates.clear()
    }

    var devicesCalls = 0
        private set
    val revokedIDs = mutableListOf<Long>()
    val renamed = mutableListOf<Pair<Long, String>>()
    val previewedCodes = mutableListOf<String>()
    val approvals = mutableListOf<Pair<String, String>>()

    override suspend fun devices(): List<DeviceDTO> {
        devicesCalls++
        devicesError?.let { throw it }
        return if (rosters.size > 1) rosters.removeAt(0) else rosters[0]
    }

    override suspend fun revokeDevice(id: Long) {
        revokedIDs.add(id)
        revokeError?.let { throw it }
    }

    override suspend fun renameDevice(id: Long, name: String): DeviceDTO {
        renamed.add(id to name)
        renameError?.let { throw it }
        // Echo the roster forward with the new name, so the view model's
        // post-rename refresh sees what a real server would return.
        rosters = rosters.map { roster ->
            roster.map { d -> if (d.id == id) d.copy(name = name) else d }
        }.toMutableList()
        return rosters.first().firstOrNull { it.id == id }
            ?: device(id, kind = "", name = name)
    }

    override suspend fun pairPreview(code: String): PairPreview {
        previewedCodes.add(code)
        if (holdPreview) {
            val gate = CompletableDeferred<Unit>()
            previewGates.add(gate)
            gate.await()
        }
        if (previewDelay > Duration.ZERO) delay(previewDelay)
        return previewResult.getOrThrow()
    }

    override suspend fun pairApprove(code: String, agentName: String) {
        approvals.add(code to agentName)
        if (holdApprove) {
            val gate = CompletableDeferred<Unit>()
            approveGates.add(gate)
            gate.await()
        }
        if (approveDelay > Duration.ZERO) delay(approveDelay)
        approveError?.let { throw it }
    }

    override suspend fun pairApprove(code: String, agentName: String, tagChar: String?) {
        approvalTags.add(tagChar)
        pairApprove(code, agentName)
    }

    /// Box defaults: every `PUT` recorded; [defaultsResult] answers it (by
    /// default the journal's own rule applied to the roster's row, and the
    /// default agent-change model clear); [holdDefaults] parks it until
    /// [releaseDefaults].
    val defaultsPuts = mutableListOf<Pair<Long, Map<BoxDefaults.Key, String?>>>()
    var defaultsError: JournalApiError? = null
    var defaultsResult: BoxDefaults? = null
    var holdDefaults = false
    private val defaultsGates = mutableListOf<CompletableDeferred<Unit>>()
    val boxDefaultsFrames = MutableSharedFlow<BoxDefaultsUpdate>(extraBufferCapacity = 16)

    fun releaseDefaults() {
        defaultsGates.forEach { it.complete(Unit) }
        defaultsGates.clear()
    }

    override suspend fun putDeviceDefaults(id: Long, changes: Map<BoxDefaults.Key, String?>): BoxDefaults {
        defaultsPuts.add(id to changes)
        if (holdDefaults) {
            val gate = CompletableDeferred<Unit>()
            defaultsGates.add(gate)
            gate.await()
        }
        defaultsError?.let { throw it }
        defaultsResult?.let { return it }
        val current = rosters.first().firstOrNull { it.id == id }?.defaults ?: BoxDefaults()
        // The journal's rule per key in body order, and kept, as it would be.
        val stored = changes.entries.fold(current) { acc, (key, value) -> acc.applying(key, value) }
        rosters = rosters.map { roster -> roster.map { if (it.id == id) it.copy(defaults = stored) else it } }.toMutableList()
        return stored
    }

    override fun boxDefaultsUpdates(): Flow<BoxDefaultsUpdate> = boxDefaultsFrames

    override suspend fun setDeviceTag(id: Long, tagChar: String?) {
        tags.add(id to tagChar)
        tagError?.let { throw it }
        rosters = rosters.map { roster -> roster.map { if (it.id == id) it.copy(tagChar = tagChar) else it } }.toMutableList()
    }
}

/// Builds a [DeviceDTO] with test defaults, mirroring the Swift `device(...)`
/// helper.
fun device(
    id: Long,
    kind: String = "client",
    name: String = "d$id",
    createdAt: Long = 0,
    lag: Long = 0,
    lastSeenAt: Long? = null,
    isSelf: Boolean = false,
    connected: Boolean = false,
    defaults: BoxDefaults? = null,
): DeviceDTO = DeviceDTO(
    id = id,
    kind = kind,
    name = name,
    createdAt = createdAt,
    cursor = 0,
    lag = lag,
    lastSeenAt = lastSeenAt,
    isSelf = isSelf,
    connected = connected,
    defaults = defaults,
)
