package com.adb.kitty.compose.data

import android.graphics.Bitmap
import android.graphics.Color
import io.nayuki.qrcodegen.QrCode
import androidx.annotation.Keep

@Keep
object QrCodeUtils {

    /**
     * 将字符串文本转换为标准的 Android Bitmap 二维码
     *
     * @param text 要编码的内容（可以是纯文本、URL、或者是文件的 Uri 字符串）
     * @param targetSize 最终生成的图片宽高（单位：像素，例如 512）
     * @return 渲染完成的 QR 码 Bitmap
     */
    fun createQrCode(text: String, targetSize: Int = 512): Bitmap? {
        if (text.isEmpty()) return null
        
        try {
            // 1. 使用 Nayuki 库将文本编码为二维码矩阵（使用中等纠错率 Meduim，兼顾容错与容量）
            val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
            val qrSize = qr.size // 二维码原始矩阵大小（比如 25x25 或 29x29）

            // 2. 创建一个等大的小 Bitmap
            val smallBitmap = Bitmap.createBitmap(qrSize, qrSize, Bitmap.Config.ARGB_8888)

            // 3. 循环遍历矩阵，将黑白点填入小 Bitmap
            for (y in 0 until qrSize) {
                for (x in 0 until qrSize) {
                    // qr.getModule(x, y) 返回 true 代表黑色，false 代表白色
                    val color = if (qr.getModule(x, y)) Color.BLACK else Color.WHITE
                    smallBitmap.setPixel(x, y, color)
                }
            }

            // 4. 【核心性能优化】将微型 Bitmap 放大到目标尺寸。
            // 最后一个参数 filter 必须设为 false（即关闭双线性过滤），
            // 这样系统会使用邻近像素插值法放大，保证二维码方块绝对锐利、不模糊，且速度极快！
            val resultBitmap = Bitmap.createScaledBitmap(smallBitmap, targetSize, targetSize, false)
            
            // 回收临时的小 Bitmap 内存
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
