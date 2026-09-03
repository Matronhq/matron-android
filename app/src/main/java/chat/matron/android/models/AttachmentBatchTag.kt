package chat.matron.android.models

/// Marks one attachment's place in a multi-attachment composer send. Ported
/// from matron-apple's `AttachmentBatchTag`.
///
/// The composer stamps every attachment of a >1 send with the same [id]
/// (a fresh UUID per send) so the bridge can gather the resulting journal
/// frames back into the single message the user actually wrote, instead
/// of injecting the first image as its own turn and busy-queueing the
/// rest. [index] is 1-based (matching the "2 of 3" upload progress the
/// user watches); [total] is how many frames complete the batch.
///
/// Lives in `models` rather than the journal wire layer because both ends
/// of the seam need it: `TimelineService` (chat) declares it on the send
/// surface without depending on the journal package, and `ClientOp`
/// (journal) folds it into the media payload as `batch_id` /
/// `batch_index` / `batch_total`.
data class AttachmentBatchTag(
    val id: String,
    val index: Int,
    val total: Int,
)
