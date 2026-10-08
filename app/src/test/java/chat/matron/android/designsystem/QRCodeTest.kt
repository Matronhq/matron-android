package chat.matron.android.designsystem

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QRCodeTest {
    @Test
    fun bitmap_isSquareAtRequestedSize() {
        val bitmap = QRCode.bitmap("matron://link?v=1&server=https%3A%2F%2Fchat.example.com&code=KTNM-3VQ8", sizePx = 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
    }
}
