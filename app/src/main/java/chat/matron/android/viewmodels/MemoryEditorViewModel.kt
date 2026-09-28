package chat.matron.android.viewmodels

import chat.matron.android.journal.MemoriesSyncing
import chat.matron.android.journal.MemoryWrite
import chat.matron.android.models.Memory
import chat.matron.android.models.MemoryType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs the memory editor (spec 2026-09-27 memories, "Apps"): one memory
/// by [name], or the new-memory form when [name] is `null`. The form's
/// draft lives in the screen (`remember`); this holds the stored row, the
/// busy flag and the last error, and does the two writes. Validation is the
/// journal's own rules ([Memory.formError]) so a bad value is refused with a
/// reason before a request goes out; a NEW memory whose name is already
/// taken is refused too — `PUT` is an upsert and would silently replace an
/// agent's memory otherwise.
class MemoryEditorViewModel(
    val name: String?,
    private val sync: MemoriesSyncing,
    private val scope: CoroutineScope,
) {
    val isNew: Boolean get() = name == null

    private val _existing = MutableStateFlow<Memory?>(null)
    /// The stored row for [name], live from the list; `null` for a new
    /// memory or one deleted elsewhere while the editor was open.
    val existing: StateFlow<Memory?> = _existing.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var listJob: Job? = null

    fun start() {
        stop()
        listJob = scope.launch {
            sync.memories.collect { list -> _existing.value = name?.let { n -> list.firstOrNull { it.name == n } } }
        }
    }

    fun stop() {
        listJob?.cancel(); listJob = null
    }

    /// `true` when the save landed. The whole memory is sent (notes
    /// included), since an omitted body would clear the stored one.
    suspend fun save(name: String, description: String, body: String, type: MemoryType): Boolean {
        val trimmedName = name.trim()
        Memory.formError(trimmedName, description, body)?.let { _error.value = it; return false }
        if (isNew && sync.memories.value.any { it.name == trimmedName }) {
            _error.value = "A memory named \"$trimmedName\" already exists — open it from the list to change it."
            return false
        }
        _isBusy.value = true
        return try {
            sync.save(trimmedName, MemoryWrite(description = description.trim(), body = body, type = type))
            _error.value = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
            false
        } finally {
            _isBusy.value = false
        }
    }

    suspend fun delete(): Boolean {
        val target = name ?: return false
        _isBusy.value = true
        return try {
            sync.delete(target)
            _error.value = null
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
            false
        } finally {
            _isBusy.value = false
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
