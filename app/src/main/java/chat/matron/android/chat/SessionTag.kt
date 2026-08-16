package chat.matron.android.chat

/// The compact per-conversation tag rendered ahead of chat titles:
/// `A:bc` — one colored letter for the box, two characters of the agent's
/// session id. Replaces the trailing `BoxChip` in list rows, which put the
/// machine at the END of the eye scan and spent a full capsule on it.
/// Ported from matron-apple's `SessionTag` (apple #152).
///
/// The two halves travel differently:
/// - The session short arrives INSIDE the title text as a `[bc] ` prefix —
///   the bridge bakes it into every earned title (matron-bridge#224).
///   [splitTitle] peels it off so views can restyle it and show the clean
///   title; clients that never port just show the raw prefix, which still
///   reads.
/// - The box letter is derived client-side from the box-name registry
///   ([boxLetters]), so it can be colored with the box's `BoxChip` tint and
///   stays consistent with the chips everywhere else.
object SessionTag {

    /// The bridge's multi-agent room markers, leading every agent-chat room
    /// title (`↔️ [ab] mac ↔ dev-z`, matron-bridge#225/#228). 🔗 is the
    /// legacy marker rooms minted before #228 still carry — titles are only
    /// rewritten on rename, so both must parse indefinitely.
    internal val roomMarkers = listOf("↔️ ", "🔗 ")

    /// The bridge's markers that may lead a title ahead of the short:
    /// ↔️/🔗 = multi-agent room (#225), 🐣 = session another agent spawned
    /// (matron-bridge#227). All stay with the visible title; only a room
    /// marker is ever dropped, and only beside a rendered room tag.
    internal val titleMarkers = roomMarkers + "🐣 "

    /// Peels the bridge's `[bc] ` session-short prefix off a published
    /// title. Returns the short (without brackets) and the remaining title.
    /// Titles without the prefix come back unchanged with a null short —
    /// including bracketed text that isn't a short (wrong length, spaces,
    /// no trailing separator), which stays part of the visible title.
    /// Room and spawned-session titles carry the short BEHIND their emoji
    /// marker; the short is peeled from there and the marker stays with the
    /// title, so the meaning survives even for users who get no styled tag.
    fun splitTitle(raw: String): Pair<String?, String> {
        val marker = titleMarkers.firstOrNull { raw.startsWith(it) }
        if (marker != null) {
            val (short, rest) = splitTitle(raw.removePrefix(marker))
            if (short == null) return null to raw
            return short to marker + rest
        }
        if (!raw.startsWith("[")) return null to raw
        val close = raw.indexOf(']')
        if (close == -1) return null to raw
        val short = raw.substring(1, close)
        if (short.length != 2 || !short.all { it.isLetterOrDigit() }) return null to raw
        val rest = raw.substring(close + 1)
        if (!rest.startsWith(" ")) return null to raw
        val title = rest.drop(1)
        if (title.isEmpty()) return null to raw
        return short to title
    }

    /// The title to render NEXT TO a colored `A↔B` room tag: the tag
    /// already says "multi-agent room", so the bridge's room marker is
    /// dropped. Rows that show no room tag (single-box users, unresolved
    /// participants) keep the marker.
    fun titleBesideRoomTag(title: String): String {
        val marker = roomMarkers.firstOrNull { title.startsWith(it) } ?: return title
        return title.removePrefix(marker)
    }

    /// One display letter per box, derived from the box names: strip the
    /// prefix common to ALL names, then take the first letter/digit of what
    /// remains, uppercased. `dev-y` / `dev-z` therefore come out as `Y` and
    /// `Z`, not both `D` (the colleague-with-two-DEV-boxes problem), while
    /// unrelated names keep their initials (`mac-mini` / `dev-3` → `M` /
    /// `D`). A name that IS the common prefix (`dev` next to `dev-2`) falls
    /// back to its own initial. Deterministic — same names, same letters,
    /// every platform. Collisions are tolerated: the letter is an aid,
    /// the color and session short still disambiguate.
    ///
    /// [overrides] (Settings → Devices → Tag Character, stored by
    /// `BoxLetterOverrides`) replace the derived letter per box AFTER
    /// derivation, so one override never shifts what the other boxes get
    /// from the common-prefix strip.
    fun boxLetters(
        names: Map<Long, String>,
        overrides: Map<Long, String> = emptyMap(),
    ): Map<Long, String> {
        if (names.isEmpty()) return emptyMap()
        val values = names.values.toList()
        val prefix = if (values.size >= 2) commonPrefix(values) else ""
        return names.mapValues { (id, name) ->
            val remainder = name.drop(prefix.length)
            overrides[id]
                ?: firstAlphanumeric(remainder) ?: firstAlphanumeric(name) ?: "?"
        }
    }

    /// What TalkBack reads for a tagged chat title: the visible tag's
    /// meaning spelled out (box names, session short), then the clean
    /// title. Shared so header call sites can never drift (ports
    /// matron-apple's `SessionTag.accessibilityTitle`, apple #154).
    fun accessibilityTitle(
        chatTitle: String,
        boxName: String?,
        sessionShort: String?,
        roomBoxNames: List<String>,
    ): String {
        val parts = mutableListOf<String>()
        if (roomBoxNames.size >= 2) {
            parts.add(roomBoxNames.joinToString(" and "))
        } else if (boxName != null) {
            parts.add(boxName)
        }
        if (sessionShort != null) parts.add("session $sessionShort")
        // Marker discipline mirrors the visible title: the room marker
        // drops only when a room tag renders (≥2 named participants) —
        // a single-box user's header keeps it, so TalkBack must too.
        parts.add(if (roomBoxNames.size >= 2) titleBesideRoomTag(chatTitle) else chatTitle)
        return parts.joinToString(", ")
    }

    private fun firstAlphanumeric(s: String): String? {
        val ch = s.firstOrNull { it.isLetterOrDigit() } ?: return null
        // Uppercasing can EXPAND some letters (ß → SS); the tag is one
        // character by contract, so keep the original when it does.
        val uppercased = ch.toString().uppercase()
        return if (uppercased.length == 1) uppercased else ch.toString()
    }

    /// Case-insensitive longest common prefix, returned at the length it
    /// holds for every name. Case-insensitive so `Dev-y` / `dev-z` still
    /// strip to `Y` / `Z`.
    private fun commonPrefix(names: List<String>): String {
        var shortest = names.minByOrNull { it.length } ?: return ""
        while (shortest.isNotEmpty()) {
            val lower = shortest.lowercase()
            if (names.all { it.lowercase().startsWith(lower) }) return shortest
            shortest = shortest.dropLast(1)
        }
        return ""
    }
}
