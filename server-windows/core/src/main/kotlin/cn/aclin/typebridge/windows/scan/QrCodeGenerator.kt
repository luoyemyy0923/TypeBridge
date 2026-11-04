package cn.aclin.typebridge.windows.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.Color
import java.awt.image.BufferedImage

/** 二维码生成（ZXing），纠错级别 M，与 macOS 端 `CIFilter.qrCodeGenerator` 对齐。 */
object QrCodeGenerator {
    fun image(content: String, size: Int = 260): BufferedImage? {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        }.getOrNull() ?: return null

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, size, size)
        g.color = Color.BLACK
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (matrix.get(x, y)) g.fillRect(x, y, 1, 1)
            }
        }
        g.dispose()
        return image
    }
}
