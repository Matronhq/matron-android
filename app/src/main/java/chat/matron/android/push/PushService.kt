package chat.matron.android.push

import okhttp3.HttpUrl

/// Cross-platform surface for managing the push pipeline, ported from
/// matron-apple's `PushService` protocol. Tests inject a fake so call-site code
/// stays platform-neutral and suspend-pure.
interface PushService {
    /// Requests notification permission. Returns `true` on grant, `false` on
    /// decline OR error — the OS-level error here isn't actionable from the app.
    suspend fun requestPermission(): Boolean

    /// Registers a device token with the user's server. Idempotent server-side.
    /// Throws on network / SDK failure. `pusherBaseURL` is part of the legacy
    /// signature; the journal server doesn't need it (registration is keyed on
    /// the authenticated device alone).
    suspend fun registerToken(deviceToken: ByteArray, pusherBaseURL: HttpUrl? = null)

    /// Removes the push registration from the server (sign-out path).
    suspend fun unregister(deviceToken: ByteArray, pusherBaseURL: HttpUrl? = null)
}
