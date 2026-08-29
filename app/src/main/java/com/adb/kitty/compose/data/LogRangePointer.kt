package com.adb.kitty.compose.data

import androidx.annotation.Keep
import kotlin.jvm.JvmInline

@Keep
@JvmInline
value class LogRangePointer(@get:Keep val packed: Long) {
    companion object {
        fun create(start: Int, end: Int): LogRangePointer {
            val packedValue = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)
            return LogRangePointer(packedValue)
        }
    }

    val start: Int get() = (packed shr 32).toInt()
    val end: Int get() = (packed and 0xFFFFFFFFL).toInt()
}
