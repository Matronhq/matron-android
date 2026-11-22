package chat.matron.android

import android.app.Application
import chat.matron.android.designsystem.AnimatedImages
import coil.ImageLoader
import coil.ImageLoaderFactory

/**
 * Application entry point. Exposes the [AppDependencies] composition root.
 *
 * The graph is built lazily on first access rather than in [onCreate]: the root
 * touches the AndroidKeyStore (EncryptedSharedPreferences) and opens Room, neither
 * available under Robolectric, and building it in `onCreate` would crash every
 * unit test that merely instantiates this Application. Production reads
 * `dependencies` from [MainActivity], which builds it on demand.
 *
 * Also Coil's loader factory: every `AsyncImage` in the app uses the loader
 * built by [AnimatedImages], so animated GIF and WebP attachments play.
 */
class MatronApplication : Application(), ImageLoaderFactory {
    val dependencies: AppDependencies by lazy { AppDependencies(this) }

    override fun onCreate() {
        super.onCreate()
        // Push: Firebase from the build's values, when it names any (a no-op
        // otherwise, and in unit tests). Cheap: it touches no keystore.
        chat.matron.android.push.FirebasePush.initialize(this)
    }

    override fun newImageLoader(): ImageLoader = AnimatedImages.imageLoader(this)
}
