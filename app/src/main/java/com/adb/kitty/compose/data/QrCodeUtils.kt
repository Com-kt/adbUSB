package com.adb.kitty.compose.data

import android.graphics.Bitmap
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.set
import androidx.annotation.Keep

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
}
