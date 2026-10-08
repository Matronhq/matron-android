package chat.matron.android.chat

import chat.matron.android.viewmodels.KeyValueStore
import java.text.BreakIterator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/// User-chosen tag characters for agent boxes, overriding the letters
/// `SessionTag.boxLetters` derives from the box names. Stored locally in the
/// app's [KeyValueStore] (per app install, not synced through the journal —
/// the tag is a reading aid, and one person may well want different
/// characters on different devices). Keyed by the box's device id, the same
/// id the summaries pipeline already resolves names and colors by.
///
/// Ported from matron-apple's `BoxLetterOverrides` (apple #154), reshaped
/// per the platform: an injectable class over the KeyValueStore instead of
/// `UserDefaults` statics, and the change "notification" is a [flow] the
/// summaries stream combines — a settings edit writes no journal record, so
/// nothing else would wake the chat list.
/// LEGACY since apple #158: the journal owns the tag now (`agent.tag_char`,
/// synced to every client via snapshot + `device_meta`); this local store
/// was the pre-sync home, which is exactly why a letter chosen on one device
/// never showed on the others. [BoxLetterMigration] pushes these up once and
/// deletes them; nothing writes here from the UI anymore.
///
/// The legacy map is install-wide and keyed by agent device id alone — it
/// predates any notion of which account chose the letters, and device ids
/// repeat across journals. So the relics belong to exactly one account: the
/// first one to [claim] them, which on any real install is the account that
/// wrote them. Every other account ignores them.
class BoxLetterOverrides(private val store: KeyValueStore) {
    /// Whether [userID] may migrate the legacy entries. The first account to
    /// ask while entries exist becomes their owner; afterwards only that
    /// account gets `true`. The owner stamp disappears with the last entry
    /// ([remove]), so a fully migrated install carries no residue.
    fun claim(userID: String): Boolean {
        if (store.getString(STORE_KEY) == null) return false
        store.getString(OWNER_KEY)?.let { return it == userID }
        store.setString(OWNER_KEY, userID)
        return true
    }

    /// Drops one migrated entry; removes the whole key (and the owner stamp)
    /// with the last one.
    fun remove(id: Long) {
        val raw = loadRaw().toMutableMap()
        raw.remove(id.toString())
        if (raw.isEmpty()) {
            store.remove(STORE_KEY)
            store.remove(OWNER_KEY)
        } else {
            store.setString(STORE_KEY, json.encodeToString(mapSerializer, raw))
        }
        _all.value = decode(raw)
    }


    private val _all = MutableStateFlow(load())

    /// Every stored override, live: emits again after each [set] so the
    /// summaries stream can re-derive letters (the Kotlin shape of the Apple
    /// original's `didChange` NotificationCenter post).
    val flow: StateFlow<Map<Long, String>> = _all.asStateFlow()

    /// Every stored override, sanitized.
    fun all(): Map<Long, String> = _all.value

    fun letter(id: Long): String? = _all.value[id]

    /// Stores one override, or removes it when [letter] is null/blank so an
    /// emptied field means "back to automatic".
    fun set(letter: String?, id: Long) {
        val raw = loadRaw().toMutableMap()
        val sanitized = letter?.let(::sanitize)
        if (sanitized != null) {
            raw[id.toString()] = sanitized
        } else {
            raw.remove(id.toString())
        }
        if (raw.isEmpty()) {
            store.remove(STORE_KEY)
        } else {
            store.setString(STORE_KEY, json.encodeToString(mapSerializer, raw))
        }
        _all.value = decode(raw)
    }

    private fun load(): Map<Long, String> = decode(loadRaw())

    private fun loadRaw(): Map<String, String> {
        val raw = store.getString(STORE_KEY) ?: return emptyMap()
        return runCatching { json.decodeFromString(mapSerializer, raw) }.getOrElse { emptyMap() }
    }

    /// Unparseable keys are skipped rather than crashing on a hand-edited
    /// store (the Apple original's discipline for a hand-edited plist).
    private fun decode(raw: Map<String, String>): Map<Long, String> {
        val overrides = mutableMapOf<Long, String>()
        for ((key, value) in raw) {
            val id = key.toLongOrNull() ?: continue
            val letter = sanitize(value) ?: continue
            overrides[id] = letter
        }
        return overrides
    }

    companion object {
        internal const val STORE_KEY = "boxLetterOverrides"
        internal const val OWNER_KEY = "boxLetterOverridesOwner"

        private val json = Json
        private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

        /// The tag is one character by contract, but that character is the
        /// user's pick — a lowercase letter, a digit, an emoji all render
        /// fine. Trim, then keep the first grapheme (a BreakIterator, so a
        /// surrogate-pair emoji survives whole); nothing left means no
        /// override.
        internal fun sanitize(letter: String): String? {
            val trimmed = letter.trim()
            if (trimmed.isEmpty()) return null
            val boundary = BreakIterator.getCharacterInstance()
            boundary.setText(trimmed)
            val end = boundary.next()
            return if (end == BreakIterator.DONE) null else trimmed.substring(0, end)
        }
    }
}

/// One-time push of legacy local letter overrides up to the journal, run at
/// app start once a session exists. Idempotent and interruption-safe: an
/// entry leaves the store only after its push succeeds, so a network failure
/// just retries on the next launch, and an empty legacy map makes the whole
/// thing a no-op read (apple #158).
object BoxLetterMigration {
    /// [serverTags] is the journal's current view (from `GET /devices`,
    /// agent boxes only) — a box that already has a journal-held tag wins
    /// over the local relic, which is dropped without pushing. Overrides for
    /// ids the server no longer knows (revoked boxes) are dropped the same
    /// way. Returns the letters that were pushed.
    suspend fun run(
        overrides: BoxLetterOverrides,
        serverTags: Map<Long, String?>,
        push: suspend (Long, String) -> Unit,
    ): Map<Long, String> {
        val pushed = mutableMapOf<Long, String>()
        for ((id, letter) in overrides.all()) {
            if (id !in serverTags || serverTags[id] != null) {
                // Unknown id (revoked) or journal already has a tag.
                overrides.remove(id)
                continue
            }
            if (runCatching { push(id, letter) }.isSuccess) {
                pushed[id] = letter
                overrides.remove(id)
            }
            // Push failed: keep the entry; the next launch retries.
        }
        return pushed
    }
}
