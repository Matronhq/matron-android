package chat.matron.android.designsystem

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/// ZXing QR *generation* for the Link-a-Device screen (`QRCodeWriter` →
/// `BitMatrix` → `Bitmap`). Scanning deliberately uses the Play-services
/// code scanner instead — no ZXing camera machinery.
object QRCode {
    fun bitmap(content: String, sizePx: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val pixels = IntArray(sizePx * sizePx) { i ->
            if (matrix.get(i % sizePx, i / sizePx)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.RGB_565)
    }
}
