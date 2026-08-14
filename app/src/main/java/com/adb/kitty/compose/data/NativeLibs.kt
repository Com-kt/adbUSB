package com.adb.kitty.compose.data

import android.content.*
import android.os.*
import android.util.*
import android.graphics.Bitmap

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*

import javax.crypto.*
import javax.net.ssl.*

import okio.*
import org.json.*

import kotlin.*
import kotlin.jvm.*

import androidx.annotation.Keep

@Keep
data class AdbCommand(val description: String, val command: String)

@Keep
data class FbCommand(val description: String, val command: String)

@Keep
data class AppCommand(val description: String, val command: String)

@Keep
data class AdbDevice(val ip: String, val port: Int, val wifiSsid: String, val lastConnectedTime: Long)

@Keep
object NativeLibs {
    init {
        System.loadLibrary("native-lib")
    }
    external fun UserString(): String
    external fun VerifyAllSignatures(apkPath: String): Boolean
    
}

@Keep
data class CommandUiItem(
    val command: String,
    val description: String,
    val isAdb: Boolean,
    val isApp: Boolean = false
)

@Keep
enum class DeviceType { USB, WIFI }

@Keep
data class DeviceUiState(
    val id: String,
    val displayName: String,
    val type: DeviceType,
    val isActive: Boolean
)

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
