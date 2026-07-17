package chat.matron.android.designsystem

import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TimelineSendState
import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins the service-/model-layer → design-system enum bridges in
/// `StateBridges.kt`. Both are identity mappings today; the indirection lets
/// design-system UX diverge from the source enums, and the exhaustive `when`
/// fails compile-loudly if a new case is added without a mapping. Ported from
/// the Swift `StateBridgesTests`.
class StateBridgesTest {
    @Test
    fun syncBannerStateMapsConnecting() {
        assertEquals(SyncBannerState.Connecting, syncBannerStateFrom(SyncConnectionState.Connecting))
    }

    @Test
    fun syncBannerStateMapsRunning() {
        assertEquals(SyncBannerState.Running, syncBannerStateFrom(SyncConnectionState.Running))
    }

    @Test
    fun syncBannerStateMapsOfflineWithReason() {
        assertEquals(
            SyncBannerState.Offline("network down"),
            syncBannerStateFrom(SyncConnectionState.Offline("network down")),
        )
    }

    @Test
    fun sendStateGlyphMapsSent() {
        assertEquals(SendStateGlyph.Sent, sendStateGlyphFrom(TimelineSendState.Sent))
    }

    @Test
    fun sendStateGlyphMapsSending() {
        assertEquals(SendStateGlyph.Sending, sendStateGlyphFrom(TimelineSendState.Sending))
    }

    @Test
    fun sendStateGlyphMapsFailedWithReason() {
        assertEquals(
            SendStateGlyph.Failed("boom"),
            sendStateGlyphFrom(TimelineSendState.Failed("boom")),
        )
    }
}
