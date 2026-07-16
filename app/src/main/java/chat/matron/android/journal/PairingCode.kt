package chat.matron.android.journal

/// Pairing-code input helpers. Codes are 8 characters from a no-lookalike
/// alphabet, displayed by the box as `XXXX-XXXX`. The server normalizes before
/// lookup exactly like [normalize] below, so the app accepts sloppy input
/// (lowercase, spaces, missing hyphen) and never blocks submission on format.
object PairingCode {
    const val LENGTH = 8

    /// Server-equivalent normalization: uppercase, strip every non-alphanumeric
    /// character.
    fun normalize(raw: String): String =
        raw.uppercase().filter { it.isLetterOrDigit() }

    /// Normalized code formatted for display as it's typed: a hyphen after the
    /// fourth character once a fifth exists (partial input stays unhyphenated).
    fun display(raw: String): String {
        val normalized = normalize(raw)
        if (normalized.length <= 4) return normalized
        return "${normalized.take(4)}-${normalized.drop(4)}"
    }

    /// Whether the input is worth previewing: exactly 8 normalized chars.
    fun isPlausible(raw: String): Boolean = normalize(raw).length == LENGTH
}
