package chat.matron.android

import android.app.Application

/**
 * Application entry point. Exposes the [AppDependencies] composition root.
 *
 * The graph is built lazily on first access rather than in [onCreate]: the root
 * touches the AndroidKeyStore (EncryptedSharedPreferences) and opens Room, neither
 * available under Robolectric, and building it in `onCreate` would crash every
 * unit test that merely instantiates this Application. Production reads
 * `dependencies` from [MainActivity], which builds it on demand.
 */
class MatronApplication : Application() {
    val dependencies: AppDependencies by lazy { AppDependencies(this) }
}
