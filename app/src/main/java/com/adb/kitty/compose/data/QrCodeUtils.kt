package com.adb.kitty.compose.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.annotation.Keep
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.io.File

@Keep
object QrCodeUtils {
    fun createQrCode(text: String, targetSize: Int = 512): Bitmap? {
        if (text.isEmpty()) return null
        
        try {
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val qrSize = qr.size 
            val smallBitmap = createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888)

            for (y in 0 until qrSize) {
                for (x in 0 until qrSize) {
                    val color = if (qr.getModule(x, y)) Color.BLACK else Color.WHITE
                    smallBitmap[x, y] = color
                }
            }
            val resultBitmap = smallBitmap.scale(targetSize, targetSize, filter = false)
            
            if (smallBitmap != resultBitmap) {
                smallBitmap.recycle()
            }

            return resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    fun decodeQrCodeFromFile(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        
        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val source = RGBLuminanceSource(width, height, pixels)
            val binarizer = HybridBinarizer(source)
            val binaryBitmap = BinaryBitmap(binarizer)

            val hints = mapOf(
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true
            )
            
            val result = MultiFormatReader().decode(binaryBitmap, hints)
            
            bitmap.recycle()
            
            result.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
