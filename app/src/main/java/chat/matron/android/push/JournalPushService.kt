package chat.matron.android.push

import chat.matron.android.journal.JournalApi
import okhttp3.HttpUrl

/// [PushService] backed by the journal server's `/push/register` endpoint.
///
/// DORMANT on Android: there is no Firebase project yet, so there is no FCM
/// wiring and [currentPushToken] is a documented stub returning `null`. The
/// `registerToken` / `unregister` API calls are wired and correct — they light
/// up the moment a token source exists. [requestPermission] can't run headlessly
/// (the Android 13+ `POST_NOTIFICATIONS` runtime grant needs an Activity), so it
/// returns `false` until the notification-permission flow is built.
class JournalPushService(
    private val api: JournalApi,
    private val environment: JournalApi.PushEnvironment,
) : PushService {

    override suspend fun requestPermission(): Boolean = false

    /// `pusherBaseURL` is ignored — the journal server keys registration on the
    /// authenticated device alone (parity with the Apple service).
    override suspend fun registerToken(deviceToken: ByteArray, pusherBaseURL: HttpUrl?) {
        api.registerPush(hexString(deviceToken), environment)
    }

    override suspend fun unregister(deviceToken: ByteArray, pusherBaseURL: HttpUrl?) {
        api.unregisterPush()
    }

    companion object {
        /// FCM token acquisition — a stub returning `null` until a Firebase
        /// project and messaging SDK are wired in.
        fun currentPushToken(): String? = null

        /// Hex-encodes device tokens for the journal server's `apns_token`
        /// field. Pinned by unit test without any push runtime.
        fun hexString(data: ByteArray): String =
            data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
