package chat.matron.android.push

import android.content.Context
import chat.matron.android.BuildConfig
import chat.matron.android.models.MatronDebug
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/// Firebase Cloud Messaging, set up by hand from the build's `MATRON_FCM_*`
/// values rather than a google-services.json. A build that names none has
/// push off: nothing here touches Firebase, no token is asked for, and the
/// app behaves as it did before push existed.
object FirebasePush {
    data class Config(val projectID: String, val appID: String, val apiKey: String, val senderID: String) {
        companion object {
            /// All four, or none.
            fun of(projectID: String?, appID: String?, apiKey: String?, senderID: String?): Config? {
                if (projectID.isNullOrBlank() || appID.isNullOrBlank() || apiKey.isNullOrBlank() || senderID.isNullOrBlank()) return null
                return Config(projectID, appID, apiKey, senderID)
            }
        }
    }

    /// This build's configuration, or null when push is off.
    val config: Config? = Config.of(BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_APP_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID)

    val isConfigured: Boolean get() = config != null

    /// Sets up the default FirebaseApp once. False when push is off or
    /// Firebase refused the configuration.
    @Synchronized
    fun initialize(context: Context): Boolean {
        val config = config ?: return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectID)
            .setApplicationId(config.appID)
            .setApiKey(config.apiKey)
            .setGcmSenderId(config.senderID)
            .build()
        return runCatching { FirebaseApp.initializeApp(context.applicationContext, options) }
            .onFailure { MatronDebug.breadcrumb("push: Firebase setup failed: ${it.javaClass.simpleName}") }
            .isSuccess
    }

    /// This install's FCM token, or null when push is off or Firebase could
    /// not get one (no Play services, offline).
    suspend fun currentToken(context: Context): String? {
        if (!initialize(context)) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result?.takeIf { it.isNotEmpty() } else null
                    if (token == null) MatronDebug.breadcrumb("push: no FCM token: ${task.exception?.javaClass?.simpleName}")
                    if (cont.isActive) cont.resume(token)
                }
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}
