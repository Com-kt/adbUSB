package com.adb.kitty.compose

import com.adb.kitty.compose.R

import android.*
import android.util.*
import android.content.pm.*
import android.app.*
import android.graphics.*
import android.animation.*

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.app.*
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.util.concurrent.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import okio.*
import com.flyfishxu.kadb.Kadb
import org.json.*
import androidx.annotation.Keep

@Keep
object AdbController {

    var kadbInstance: Kadb? = null
    var logListener: ((String) -> Unit)? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentShellJob: Job? = null
    private var usbForwarder: UsbPortForwarder? = null
    private var usbConn: UsbDeviceConnection? = null

    private fun appendLog(msg: String) {
        logListener?.invoke(msg)
    }

    fun connectWired(
        usbManager: UsbManager,
        device: UsbDevice,
        onResult: (Boolean, String) -> Unit
    ) {
        scope.launch {
            try {
                // 1. 查找接口
                val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
                    .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == 1 }
                
                if (intf == null) {
                    onResult(false, "未找到 ADB 接口")
                    return@launch
                }

                // 2. 打开设备与占线
                val conn = usbManager.openDevice(device)
                if (conn == null) {
                    onResult(false, "无法打开 USB 设备")
                    return@launch
                }
                
                conn.claimInterface(intf, true)
                usbConn = conn

                // 3. 查找端点
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
                }

                if (epIn == null || epOut == null) {
                    onResult(false, "无法分配 USB 端点")
                    return@launch
                }

                // 4. 启动转发器并初始化 Kadb
                appendLog("[系统] 正在建立虚拟本地有线网络转发桥接...")
                usbForwarder?.stop()
                usbForwarder = UsbPortForwarder(conn, epIn, epOut)
                val localVirtualPort = usbForwarder!!.startBridge()

                appendLog("[Auth] 正在向环回端口 [$localVirtualPort] 发起握手...")
                
                val instance = Kadb.create(host = "127.0.0.1", port = localVirtualPort)
                val isConnected = instance.connectionCheck()

                if (isConnected) {
                    kadbInstance = instance
                    onResult(true, "有线授权成功")
                } else {
                    onResult(false, "Kadb 握手失败")
                }
            } catch (e: Exception) {
                appendLog("[Error] 有线连接崩溃: ${e.message}")
                onResult(false, e.message ?: "Unknown Error")
            }
        }
    }

    fun pair(host: String, port: Int, pairingCode: String) {
        scope.launch {
            appendLog("[配对] 正在向远端电视注入 TLS 配对验证...")
            try {
                Kadb.pair(host = host, port = port, pairingCode = pairingCode)
                appendLog("[成功] 🎉 配对凭证握手存盘成功！")
            } catch (e: Exception) {
                appendLog("[配对失败] 异常: ${e.message}")
            }
        }
    }

    fun connectWireless(host: String, port: Int, onComplete: (Boolean) -> Unit) {
        scope.launch {
            appendLog("[无线] 正在唤醒远端网络数据通道...")
            try {
                kadbInstance?.close()
                val instance = Kadb.create(host = host, port = port)
                val response = instance.shell("echo 1")
                
                if (response.exitCode == 0 && response.allOutput.trim() == "1") {
                    kadbInstance = instance
                    isAdbAuthorized = true
                    appendLog(">>> 👍 无线调试通道连通成功！ <<<")
                    onComplete(true)
                } else {
                    instance.close()
                    appendLog("[警告] 远端响应握手信号失败")
                    onComplete(false)
                }
            } catch (e: Exception) {
                appendLog("[连接失败] 远端网络拒绝建立链路: ${e.message}")
                onComplete(false)
            }
        }
    }
    
    fun executeShell(command: String) {
        currentShellJob?.cancel()
        currentShellJob = scope.launch {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("通道未建立")
                val cleanCmd = command.removePrefix("adb shell ").trim()
                
                if (cleanCmd.contains("logcat") || cleanCmd.contains("top")) {
                    handleStreamingCommand(kadb, cleanCmd)
                } else {
                    val response = kadb.shell(cleanCmd)
                    appendLog(response.allOutput.trim())
                }
            } catch (e: Exception) {
                if (e !is CancellationException) appendLog("[Shell 异常] ${e.message}")
            }
        }
    }

    fun install(file: File, isMultiple: Boolean) {
        scope.launch {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("未连接")
                if (isMultiple) {
                    val apks = if (file.isDirectory) file.listFiles { _, name -> name.endsWith(".apk") }?.toList() ?: emptyList()
                    kadb.installMultiple(apks)
                } else {
                    kadb.install(file)
                }
                appendLog("[成功] 安装完成: ${file.name}")
            } catch (e: Exception) {
                appendLog("[安装失败] ${e.message}")
            }
        }
    }

    fun uninstall(packageName: String) {
        scope.launch {
            try {
                kadbInstance?.uninstall(packageName)
                appendLog("[成功] 已卸载: $packageName")
            } catch (e: Exception) {
                appendLog("[卸载失败] ${e.message}")
            }
        }
    }

    fun push(localFile: File, remotePath: String) {
        scope.launch {
            try {
                kadbInstance?.push(src = localFile, remotePath = remotePath)
                appendLog("[成功] 文件已推送: $remotePath")
            } catch (e: Exception) {
                appendLog("[Push 失败] ${e.message}")
            }
        }
    }

    fun pull(remotePath: String, localFile: File) {
        scope.launch {
            try {
                kadbInstance?.pull(dst = localFile, remotePath = remotePath)
                appendLog("[成功] 数据已沉淀: ${localFile.absolutePath}")
            } catch (e: Exception) {
                appendLog("[Pull 失败] ${e.message}")
            }
        }
    }

    private suspend fun handleStreamingCommand(kadb: Kadb, command: String) {
        kadb.openShell(command).use { stream ->
            while (true) {
                val packet = stream.read()
                if (packet is AdbShellPacket.Exit) break
                val content = if (packet is AdbShellPacket.StdOut) String(packet.payload) else ""
                if (content.isNotEmpty()) appendLog(content)
            }
        }
    }

    fun cleanup() {
        usbForwarder?.stop()
        usbConn?.close()
        kadbInstance?.close()
        kadbInstance = null
        currentShellJob?.cancel()
    }
}
