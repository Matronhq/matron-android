package chat.matron.android.push

/// The app's push registration with its journal. Tests inject a fake.
interface PushService {
    /// Registers this phone for push: its FCM token and the public key its
    /// notifications are sealed to. Never throws.
    suspend fun register(): PushRegistration

    /// Registers [token] (a fresh one from Firebase). Never throws.
    suspend fun register(token: String): PushRegistration

    /// Removes the push registration from the server (sign-out path). Throws
    /// on a network failure.
    suspend fun unregister()
}

/// Where this phone's push registration stands, for Settings → Notifications.
enum class PushRegistration(val stored: String) {
    /// This build has no push set up.
    NOT_CONFIGURED("not_configured"),
    /// Firebase gave no token (no Play services, offline).
    NO_TOKEN("no_token"),
    /// The journal has Android push off.
    SERVER_OFF("server_off"),
    /// The journal could not be reached; tried again next launch.
    FAILED("failed"),
    REGISTERED("registered");

    companion object {
        fun fromStored(value: String?): PushRegistration? = entries.firstOrNull { it.stored == value }
    }
}
