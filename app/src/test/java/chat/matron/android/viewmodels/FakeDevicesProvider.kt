package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairPreview
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/// Recording fake for the devices/pairing API surface. Rosters are served FIFO
/// from [rosters] (the last one repeats); errors are thrown per-call. Ported from
/// matron-apple's `FakeDevicesProvider`. The `holdPreview`/`holdApprove` gates
/// replace the Swift `CheckedContinuation` gates with `CompletableDeferred`.
class FakeDevicesProvider : DevicesProviding {
    var rosters: MutableList<List<DeviceDTO>> = mutableListOf(emptyList())
    var devicesError: JournalApiError? = null
    var revokeError: JournalApiError? = null
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
)
