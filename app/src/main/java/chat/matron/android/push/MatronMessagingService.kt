package chat.matron.android.push

import chat.matron.android.MatronApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/// Receives the journal's FCM messages. Every message is data-only, so this
/// runs for each one whether the app is in front or not, opens the sealed
/// content with this phone's key and posts the notification itself.
class MatronMessagingService : FirebaseMessagingService() {
    private val deps get() = (application as MatronApplication).dependencies

    override fun onMessageReceived(message: RemoteMessage) {
        // Called on a background thread with a few seconds to finish.
        deps.pushMessageHandler().handle(message.data)
    }

    /// A new token (first run, reinstall, or Firebase rotating it): the
    /// signed-in account's journal learns it at once.
    override fun onNewToken(token: String) {
        runBlocking { deps.registerPushToken(token) }
    }
}
