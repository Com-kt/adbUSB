package com.adb.kitty.compose.data

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import androidx.annotation.Keep
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.*

@Keep
class UsbPortForwarder(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    
    @Volatile 
    private var isRunning = false

    fun startBridge(): Int {
        serverSocket = ServerSocket(0)
        val localPort = serverSocket!!.localPort
        isRunning = true

        thread(name = "UsbBridge-Server") {
            try {
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    
                    closeCurrentClient()
                    clientSocket = socket
                    
                    handleClient(socket)
                }
            } catch (e: Exception) {
                // ServerSocket 关闭引发的异常，属于正常退出
            }
        }
        return localPort
    }

    private fun handleClient(socket: Socket) {
        val networkIn = socket.getInputStream()
        val networkOut = socket.getOutputStream()

        // 线程 1：Kadb 网络下发数据 -> 注入物理 USB epOut 端点
        thread(name = "NetToUsb") {
            val buffer = ByteArray(64 * 1024)
            try {
                while (isRunning && !socket.isClosed) {
                    val read = networkIn.read(buffer)
                    if (read == -1) break
                    
                    var offset = 0
                    while (offset < read && isRunning) {
                        // 使用 0 作为超时时间（无限等待），配合 isRunning 状态控制
                        // 这样可以防止在写入大文件或大缓冲区爆满时，因 2000ms 超时导致链路雪崩
                        val sent = conn.bulkTransfer(epOut, buffer, offset, read - offset, 0)
                        if (sent < 0) throw IOException("USB 物理总线写入失败")
                        if (sent == 0) continue // 规避极其罕见的 0 字节传输死循环
                        offset += sent
                    }
                }
            } catch (e: Exception) {
                // 打印或记录日志
            } finally {
                closeSocket(socket) // 一路死，路路死，触发双向解除阻止
            }
        }

        // 线程 2：物理 USB epIn 捕获数据 -> 灌回 Kadb 网络输入流
        thread(name = "UsbToNet") {
            val buffer = ByteArray(64 * 1024)
            try {
                while (isRunning && !socket.isClosed) {
                    // 【致命伤修复】将超时改成 0 (无限期阻塞等待)。
                    // 只有当手机拔出、物理断开、或者 closeConnection 时才会返回 -1
                    // 这样手机在静默、无输出状态下，此线程绝不会因为满 2 秒无数据而自杀！
                    val read = conn.bulkTransfer(epIn, buffer, buffer.size, 0)
                    if (read > 0) {
                        networkOut.write(buffer, 0, read)
                        networkOut.flush()
                    } else if (read < 0) {
                        // 真正的底层物理断开或硬件错误
                        throw IOException("USB 物理总线读取失败或设备已断开")
                    }
                }
            } catch (e: Exception) {
                // 打印或记录日志
            } finally {
                closeSocket(socket)
            }
        }
    }

    private fun closeCurrentClient() {
        clientSocket?.let { closeSocket(it) }
        clientSocket = null
    }

    private fun closeSocket(socket: Socket) {
        runCatching {
            if (!socket.isClosed) {
                socket.shutdownInput()
                socket.shutdownOutput()
                socket.close()
            }
        }
    }

    fun stop() {
        isRunning = false
        closeCurrentClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
