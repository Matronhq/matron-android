package chat.matron.android.designsystem

import chat.matron.android.models.SessionStatus

/// Absolute context size (in tokens) past which the compact-context header
/// appears. Absolute, not a fraction of the model's window: the concern is
/// cost/latency/recall at large sizes, which a 1M-window model shares.
const val COMPACT_HEADER_TOKEN_THRESHOLD = 200_000

/// Whether the compact-context header should show for [context]. Null (no status
/// yet) and exactly-at-threshold do not show; strictly above does.
fun shouldShowCompactHeader(context: SessionStatus.Context?): Boolean =
    context != null && context.tokens > COMPACT_HEADER_TOKEN_THRESHOLD
