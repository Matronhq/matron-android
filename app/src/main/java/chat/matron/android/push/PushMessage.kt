package chat.matron.android.push

/// One FCM data message from the journal (matron-journal `docs/protocol.md`,
/// "Android push (FCM)"). Every value arrives as a string.
sealed interface PushMessage {
    val convoID: String
    /// The unread count when the journal sent it; null if absent.
    val badge: Int?

    /// Something to show. With [box], what it says is sealed to this
    /// device's key; without, [title] and [body] are in the clear (a device
    /// that registered no key) or absent (the box could not be made).
    data class Alert(
        override val convoID: String,
        val seq: Long?,
        val kind: String,
        val itemID: String?,
        val box: String?,
        val title: String?,
        val body: String?,
        override val badge: Int?,
    ) : PushMessage

    /// The conversation was read on another device: its notifications go.
    data class Read(override val convoID: String, override val badge: Int?) : PushMessage

    companion object {
        /// Null for a message that is not one of the journal's.
        fun parse(data: Map<String, String>): PushMessage? {
            val convoID = data["convo_id"]?.takeIf { it.isNotEmpty() } ?: return null
            val badge = data["badge"]?.toIntOrNull()?.takeIf { it >= 0 }
            return when (data["type"]) {
                "alert" -> Alert(
                    convoID = convoID,
                    seq = data["seq"]?.toLongOrNull()?.takeIf { it > 0 },
                    kind = data["kind"].orEmpty(),
                    itemID = data["item_id"]?.takeIf { it.isNotEmpty() },
                    box = data["box"]?.takeIf { it.isNotEmpty() },
                    title = data["title"],
                    body = data["body"],
                    badge = badge,
                )
                "read" -> Read(convoID, badge)
                else -> null
            }
        }
    }
}
