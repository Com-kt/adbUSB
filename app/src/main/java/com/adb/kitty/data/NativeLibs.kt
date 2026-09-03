package com.adb.kitty.data

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
        runCatching {
            System.loadLibrary("native-lib")
        }
    }
    /**
     * 初始化 C++17 堆外内存引擎
     * @param capacity 堆外内存容量（字节），例如 16 * 1024 * 1024 (16MB)
     */
    external fun initNativeEngine(capacity: Int)

    /**
     * 获取 C++ 堆外内存映射的 DirectByteBuffer（零拷贝通道）
     */
    external fun getDirectBuffer(): ByteBuffer?

    /**
     * 高速追加日志到 Native 堆外缓冲区
     */
    external fun appendNativeLog(log: String)

    /**
     * 获取当前 Native 堆外缓冲区的写偏移指针
     */
    external fun getWriteOffset(): Long

    /**
     * 清空 Native 堆外内存
     */
    external fun clearNativeBuffer()
    
    external fun ApkSignature(apkPath: String): String
    
    external fun hasV1Scheme(apkPath: String): Boolean
    external fun hasV2Scheme(apkPath: String): Boolean
    external fun hasV3Scheme(apkPath: String): Boolean
    external fun hasV31Scheme(apkPath: String): Boolean
    external fun hasV32Scheme(apkPath: String): Boolean
    
    fun getSupportedSchemesText(apkPath: String): String {
        val schemes = mutableListOf<String>()
        if (hasV1Scheme(apkPath)) schemes.add("V1")
        if (hasV2Scheme(apkPath)) schemes.add("V2")
        if (hasV3Scheme(apkPath)) schemes.add("V3")
        if (hasV31Scheme(apkPath)) schemes.add("V3.1")
        if (hasV32Scheme(apkPath)) schemes.add("V3.2")

        return if (schemes.isNotEmpty()) {
            "签名方案: " + schemes.joinToString(" + ")
        } else {
            "未检测到已知签名方案"
        }
    }
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
