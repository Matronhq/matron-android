package chat.matron.android.push

/// Turns one FCM data message into what the shade shows. Separate from the
/// FirebaseMessagingService so tests (and the screenshot test) can drive it
/// with a plain map.
class PushMessageHandler(
    private val notifier: PushNotifier,
    /// This device's private key, or null when it has none.
    private val privateKey: () -> ByteArray?,
    /// Whether an account is signed in: a message that lands after sign-out
    /// (sent before the journal heard of it) is dropped.
    private val signedIn: () -> Boolean,
    /// Changes at every sign-out. A post is dropped if it changed since the
    /// message arrived (checked under the notifier's lock: see endSession).
    private val generation: () -> Long = { 0L },
) {
    /// What was done with [data]; for tests and logs.
    enum class Outcome { SHOWN, NOT_SHOWN, CLEARED, IGNORED }

    fun handle(data: Map<String, String>): Outcome {
        val message = PushMessage.parse(data) ?: return Outcome.IGNORED
        val started = generation()
        if (!signedIn()) return Outcome.IGNORED
        return when (message) {
            is PushMessage.Read -> {
                notifier.cancelConversation(message.convoID)
                Outcome.CLEARED
            }
            is PushMessage.Alert -> {
                // Anything that goes wrong opening the box leaves the fixed
                // text: a notification is never dropped for want of content.
                val content = message.box?.let { box -> privateKey()?.let { PushBox.content(box, it) } }
                val posted = notifier.show(PushDisplay.of(message, content)) { generation() == started }
                if (posted) Outcome.SHOWN else Outcome.NOT_SHOWN
            }
        }
    }
}
