package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.models.Memory
import chat.matron.android.models.MemoryType
import kotlinx.serialization.json.JsonObject

// The `/memories` routes' shapes (protocol.md "Memories", spec 2026-09-27
// memories). The route implementations live on [JournalApi] (its request
// plumbing is private), which implements [MemoriesProviding]; the decoders
// are pure so `MemoriesApiTest` can pin each shape without a server.

/// `PUT /memories/:name` body. A PUT is the WHOLE memory: an omitted [body]
/// clears the stored notes, an omitted [type] keeps the stored one (or
/// `feedback` on create) — so a client editing one always sends both back.
data class MemoryWrite(
    val description: String,
    val body: String? = null,
    val type: MemoryType? = null,
)

/// What a save did: the row as stored, and whether it was a create (201)
/// rather than an update (200).
data class MemorySave(val memory: Memory, val created: Boolean)

/// The read/write surface the screen needs. [JournalApi] implements it;
/// tests fake it. Every route is user-global (no conversation scope).
interface MemoriesProviding {
    suspend fun listMemories(): List<Memory>
    suspend fun saveMemory(name: String, write: MemoryWrite): MemorySave
    suspend fun deleteMemory(name: String): Memory
}

object MemoriesDecoding {
    /// Lenient per row (a row this build cannot read is dropped and logged),
    /// strict on the top-level key: a response without `memories` is
    /// malformed, not "no memories", so it throws instead of decoding empty.
    fun memories(obj: JsonObject): List<Memory> {
        val rows = obj.arrayOrNull("memories") ?: throw JournalApiError.Transport("malformed memories response")
        return rows.objects().mapNotNull { row ->
            Memory.fromJson(row).also {
                if (it == null) MatronDebug.breadcrumb("MemoriesApi: dropped malformed memory row id=${row.stringOrNull("id") ?: "?"}")
            }
        }.sortedBy { it.name }
    }

    fun memory(obj: JsonObject): Memory =
        obj.objectOrNull("memory")?.let(Memory::fromJson) ?: throw JournalApiError.Transport("malformed memory response")
}
