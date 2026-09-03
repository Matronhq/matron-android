package chat.matron.android.designsystem

import kotlin.math.abs

/// What the fullscreen viewer steps through with a horizontal swipe: an
/// ordered list of image entries and where to start. Entries are resolved
/// lazily through [load], a closure so the design system never has to import
/// the media service — the call site decides how a URL becomes bytes (and
/// can consult its own cache first). Port of matron-apple's `ImageGallery`
/// (#175).
///
/// Order is the presenting context's display order: the media grid passes
/// its newest-first list, a chat tap passes the conversation's images
/// oldest-first, so "next" always means "the one after this on screen".
class ImageGallery(
    entries: List<Entry>,
    startIndex: Int,
    /// The tapped image, already resolved by the call site, so the viewer
    /// opens on it instantly instead of re-fetching.
    val initial: Any?,
    val load: suspend (String) -> Any?,
) {
    data class Entry(
        val id: String,
        /// `null` for a tombstone (the bytes were reaped) — shown as
        /// unavailable rather than skipped, so the counter matches the grid
        /// the user came from.
        val url: String?,
        val expired: Boolean,
    )

    /// At least one entry: an empty list is replaced by a single placeholder
    /// so the viewer never has nothing to show.
    val entries: List<Entry> = entries.ifEmpty { listOf(Entry(id = "placeholder", url = null, expired = true)) }
    val startIndex: Int = startIndex.coerceIn(0, this.entries.lastIndex)

    companion object {
        /// A one-image gallery — the pre-#175 viewer, for call sites with no
        /// list to step through.
        fun single(model: Any?): ImageGallery =
            ImageGallery(listOf(Entry(id = "single", url = null, expired = model == null)), 0, model) { null }
    }
}

/// Pure rules behind previous/next stepping in the fullscreen image viewer —
/// kept as plain functions so the ends-are-hard-stops, neighbour-preload and
/// swipe-vs-dismiss decisions are unit-tested rather than buried in gesture
/// closures. Port of matron-apple's `ImageGalleryNavigation` (#175).
object ImageGalleryNavigation {
    /// What a finished drag at fit scale means.
    enum class SwipeIntent { PREVIOUS, NEXT, DISMISS, NONE }

    /// Index reached by moving [delta] from [index], or `null` when that
    /// would leave the gallery. No wrap-around.
    fun step(index: Int, delta: Int, count: Int): Int? {
        val target = index + delta
        if (delta == 0 || target < 0 || target >= count) return null
        return target
    }

    /// The in-range immediate neighbours of [index], oldest first — the
    /// entries worth fetching ahead so a step is instant.
    fun preloadIndices(index: Int, count: Int): List<Int> =
        listOf(index - 1, index + 1).filter { it in 0 until count }

    /// The indices within [radius] of [index], clamped to the gallery — the
    /// entries whose resolved bitmaps are worth keeping in memory. Everything
    /// else is evicted on a step, so flicking through a big gallery can't pin
    /// every image at once.
    fun retainedIndices(index: Int, count: Int, radius: Int): Set<Int> {
        if (count <= 0) return emptySet()
        val lower = maxOf(0, index - radius)
        val upper = minOf(count - 1, index + radius)
        if (lower > upper) return emptySet()
        return (lower..upper).toSet()
    }

    /// Classifies a completed drag by its dominant axis: horizontal past
    /// [threshold] pages, downward past [threshold] dismisses (the
    /// pre-existing swipe-down), anything else is a no-op. The dominant axis
    /// decides, so a diagonal flick doesn't both page and dismiss.
    fun swipeIntent(dx: Float, dy: Float, threshold: Float): SwipeIntent {
        if (abs(dx) > abs(dy)) {
            if (abs(dx) < threshold) return SwipeIntent.NONE
            return if (dx < 0) SwipeIntent.NEXT else SwipeIntent.PREVIOUS
        }
        return if (dy >= threshold) SwipeIntent.DISMISS else SwipeIntent.NONE
    }

    /// "3 of 12" for the viewer chrome; `null` when there is nothing to step
    /// between, so a lone image shows no counter.
    fun counterLabel(index: Int, count: Int): String? = if (count > 1) "${index + 1} of $count" else null
}
