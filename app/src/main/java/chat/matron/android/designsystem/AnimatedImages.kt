package chat.matron.android.designsystem

import android.content.Context
import android.os.Build
import android.provider.Settings
import coil.ImageLoader
import coil.decode.BitmapFactoryDecoder
import coil.decode.Decoder
import coil.decode.ImageDecoderDecoder
import coil.decode.GifDecoder
import coil.request.ImageRequest

/// Animated images (GIF, animated WebP) play wherever an image attachment
/// shows at reading size: chat rows, item threads and the full-screen viewer.
/// Small thumbnails (the media browser grid, the composer tray, item-list
/// rows) stay as stills, as a photo grid does.
///
/// Every `AsyncImage` in the app goes through the one [ImageLoader] built
/// here ([MatronApplication] is Coil's loader factory), so adding the
/// animated decoders once covers every surface.
///
/// Memory: both decoders draw frame by frame rather than holding every frame.
/// On API 28+ the platform decoder decodes at the size the image is drawn
/// (Coil passes the layout size), so an inline image costs about one frame at
/// most 280 dp square; below 28 the GIF decoder keeps one bitmap at the
/// file's own size. Playback stops when the image leaves the composition
/// (scrolled out of a list, a screen popped).
///
/// When the user has turned animations off (Developer options or the
/// accessibility "Remove animations" setting, both of which zero the animator
/// duration scale), no animated decoder is installed and images show their
/// first frame, as before.
object AnimatedImages {
    /// The decoders that animate, for an OS at [sdkInt]. Empty when
    /// [animationsEnabled] is false.
    fun decoderFactories(sdkInt: Int, animationsEnabled: Boolean): List<Decoder.Factory> = when {
        !animationsEnabled -> emptyList()
        // The platform `ImageDecoder`: GIF, animated WebP and animated HEIF,
        // decoded on a render thread at the drawn size.
        sdkInt >= Build.VERSION_CODES.P -> listOf(ImageDecoderDecoder.Factory())
        else -> listOf(GifDecoder.Factory())
    }

    /// Whether the system wants animations: the animator duration scale is
    /// not zero.
    fun animationsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }.getOrDefault(true)

    /// The app's image loader: Coil's defaults plus the animated decoders.
    fun imageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                decoderFactories(Build.VERSION.SDK_INT, animationsEnabled(context)).forEach { add(it) }
            }
            .build()

    /// Request parameter marking a still-only request. It is part of the
    /// memory-cache key, so a still thumbnail is never served to a surface
    /// that should animate (or the other way round).
    const val STILL_PARAMETER = "matron.still"

    // One instance, so two still requests for the same data compare equal
    // and a recomposition does not restart the load.
    private val stillDecoder = BitmapFactoryDecoder.Factory()

    /// A request for [data] decoded as a still (its first frame): for
    /// thumbnails.
    fun still(context: Context, data: Any?): ImageRequest =
        ImageRequest.Builder(context)
            .data(data)
            .decoderFactory(stillDecoder)
            .setParameter(STILL_PARAMETER, true, memoryCacheKey = "1")
            .build()
}
