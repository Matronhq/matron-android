package chat.matron.android.models

/// Per-file upload limit, in decimal bytes, matching the journal's default
/// `POST /media` cap. Checked when a file is picked, pasted or staged, so an
/// oversized file is refused while the user is still looking at it rather
/// than failing at Send. Ported from matron-apple's `AttachmentLimits`.
object AttachmentLimits {
    const val MAX_BYTES: Long = 100_000_000L

    /// Whether a file of [bytes] is over the limit. Exactly [MAX_BYTES] is
    /// accepted.
    fun isOversize(bytes: Long): Boolean = bytes > MAX_BYTES

    /// The refusal shown for a file over [MAX_BYTES]; every entry point
    /// (pickers, paste, both composers) says the same thing.
    fun oversizeMessage(filename: String): String =
        "$filename is larger than 100 MB and wasn't attached."
}
