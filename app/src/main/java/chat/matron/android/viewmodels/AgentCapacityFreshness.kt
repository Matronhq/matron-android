package chat.matron.android.viewmodels

/// How current a chooser row's capacity numbers are.
///
/// Deliberately separate from the per-line reset rule ([BoxCapacity.hasReset]):
/// a line can be expired on a box that is answering right now, and a box that
/// has been asleep for an hour can hold lines well inside their window. The
/// two de-emphasise for different reasons, so they caption at different
/// levels — this one once per block, `resetText` once per line — and never
/// restate each other. Port of matron-apple's `AgentCapacityFreshness` (#164).
sealed interface AgentCapacityFreshness {
    /// Fetched from the box during this roster visit.
    data object Live : AgentCapacityFreshness

    /// Last-known numbers for a box that is offline now, captured at the given
    /// moment (epoch ms). The host suspends idle boxes, so this is the only way
    /// their quota is visible at all.
    data class Offline(val capturedAtMs: Long) : AgentCapacityFreshness

    /// True when the numbers predate this visit: every percent renders
    /// de-emphasised rather than in the usual green/orange/red, which would
    /// vouch for them as current.
    val isStale: Boolean get() = this is Offline

    /// Block-level age caption ("offline · as of 2h ago"), or null for live
    /// numbers. Abbreviated units, the same style as the other relative
    /// captions in the chooser — this sits under a name line, not on its own.
    fun ageText(nowMs: Long = System.currentTimeMillis()): String? {
        val capturedAt = (this as? Offline)?.capturedAtMs ?: return null
        // Clock skew between the box's journal and this device can stamp a
        // capture at or ahead of now. Spelled out rather than "in 3h": that
        // reads as a promise about the future, and this caption exists only
        // to disclaim the past.
        if (capturedAt >= nowMs) return "offline · as of just now"
        val seconds = (nowMs - capturedAt) / 1000
        val age = when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            else -> "${seconds / 86_400}d ago"
        }
        return "offline · as of $age"
    }
}
