package chat.matron.android.designsystem

import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TimelineSendState

/// Single source of truth for service-layer-state → design-system-state
/// mappings. Hoisted here so every consumer constructs through the SAME
/// mapping; adding a new case to a source enum fails to compile against the
/// bridge instead of silently falling through in one of N copies.
///
/// - [syncBannerStateFrom] — `SyncConnectionState` → [SyncBannerState].
/// - [sendStateGlyphFrom] — `TimelineSendState` → [SendStateGlyph].

/// Translate the service-layer connection state to the design-system banner
/// state. Identity mapping today; kept distinct so future banner UX can diverge
/// without changing every caller.
fun syncBannerStateFrom(state: SyncConnectionState): SyncBannerState = when (state) {
    is SyncConnectionState.Connecting -> SyncBannerState.Connecting
    is SyncConnectionState.Running -> SyncBannerState.Running
    is SyncConnectionState.Offline -> SyncBannerState.Offline(state.reason)
}

/// Translate the model-layer send state to the design-system glyph. Identity
/// mapping today; the indirection lets glyph UX diverge from the model enum.
fun sendStateGlyphFrom(state: TimelineSendState): SendStateGlyph = when (state) {
    is TimelineSendState.Sent -> SendStateGlyph.Sent
    is TimelineSendState.Sending -> SendStateGlyph.Sending
    is TimelineSendState.Failed -> SendStateGlyph.Failed(state.reason)
}
