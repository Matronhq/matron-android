package chat.matron.android.designsystem

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.provider.Settings
import coil.decode.BitmapFactoryDecoder
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimatedImagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun usesThePlatformDecoderFromApi28() {
        val factories = AnimatedImages.decoderFactories(sdkInt = 28, animationsEnabled = true)
        assertEquals(1, factories.size)
        assertTrue(factories.single() is ImageDecoderDecoder.Factory)
    }

    @Test
    fun usesTheGifDecoderBelowApi28() {
        val factories = AnimatedImages.decoderFactories(sdkInt = 27, animationsEnabled = true)
        assertEquals(1, factories.size)
        assertTrue(factories.single() is GifDecoder.Factory)
    }

    @Test
    fun noAnimatedDecoderWhenAnimationsAreOff() {
        assertTrue(AnimatedImages.decoderFactories(sdkInt = 34, animationsEnabled = false).isEmpty())
    }

    @Test
    fun readsTheAnimatorDurationScale() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(AnimatedImages.animationsEnabled(context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(AnimatedImages.animationsEnabled(context))
    }

    @Test
    fun stillRequestsDecodeAFrameAndKeyTheCacheApart() {
        val bytes = byteArrayOf(1, 2, 3)
        val a = AnimatedImages.still(context, bytes)
        val b = AnimatedImages.still(context, bytes)
        assertTrue(a.decoderFactory is BitmapFactoryDecoder.Factory)
        assertEquals("1", a.parameters.memoryCacheKeys()[AnimatedImages.STILL_PARAMETER])
        assertEquals("the same data makes an equal request, so a recomposition keeps the load", a, b)
    }
}
