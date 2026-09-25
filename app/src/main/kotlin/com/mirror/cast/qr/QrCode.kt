package com.mirror.cast.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把一段短文本（投屏链接 `mirror://…`）画成二维码。
 *
 * 纠错等级取 M（约 15% 容错）：投屏时对方往往是斜着扫的、屏幕上还有反光，
 * M 比 L 稳，又不像 H 那样把码点撑大导致尺寸变大。留白 1 个模块即可 —— 码外围
 * 还有卡片留白，不必浪费像素。
 */
object QrCode {

    fun bitmap(content: String, sizePx: Int): ImageBitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        val hints =
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val pixels = IntArray(sizePx * sizePx)
            for (y in 0 until sizePx) {
                val row = y * sizePx
                for (x in 0 until sizePx) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
        } catch (error: Exception) {
            // 内容过长或含无法编码的字符时会抛异常；此时界面退回到"手输连接码"方式。
            null
        }
    }
}
