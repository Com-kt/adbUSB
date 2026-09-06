package com.adb.kitty.data.ipc

import androidx.annotation.Keep

@Keep
data class Root(
    val Root: Boolean,
    val Ipc: Boolean = false
)