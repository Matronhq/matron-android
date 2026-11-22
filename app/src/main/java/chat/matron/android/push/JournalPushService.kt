package chat.matron.android.push

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.models.MatronDebug
import kotlinx.coroutines.CancellationException

/// [PushService] backed by the journal's `/push/fcm/register` endpoint.
/// Port of matron-apple's `JournalPushService`: the token comes from
/// Firebase rather than APNs, and the public key from [PushKeyStore], so
/// the journal can seal what each notification says for this phone alone.
class JournalPushService(
    private val api: JournalApi,
    /// The public key to register, made on first use; null registers none.
    private val pushKey: () -> String?,
    /// This install's FCM token; null when push is off or Firebase has none.
    private val token: suspend () -> String?,
    private val configured: Boolean = FirebasePush.isConfigured,
) : PushService {

    override suspend fun register(): PushRegistration {
        if (!configured) return PushRegistration.NOT_CONFIGURED
        val current = token() ?: return PushRegistration.NO_TOKEN
        return register(current)
    }

    override suspend fun register(token: String): PushRegistration {
        if (!configured) return PushRegistration.NOT_CONFIGURED
        return try {
            api.registerFcmPush(token, pushKey())
            PushRegistration.REGISTERED
        } catch (e: CancellationException) {
            throw e
        } catch (e: JournalApiError.NotFound) {
            PushRegistration.SERVER_OFF
        } catch (e: Exception) {
            MatronDebug.breadcrumb("push: registration failed: ${e.javaClass.simpleName}")
            PushRegistration.FAILED
        }
    }

    override suspend fun unregister() {
        api.unregisterPush()
    }
}
