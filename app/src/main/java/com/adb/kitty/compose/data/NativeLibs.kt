package com.adb.kitty.compose.data

import android.content.*
import android.os.*
import android.util.*

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
    external fun nativeVerify(context: Context)
}

@Keep
data class CommandUiItem(
    val command: String,
    val description: String,
    val isAdb: Boolean,
    val isApp: Boolean = false
)
