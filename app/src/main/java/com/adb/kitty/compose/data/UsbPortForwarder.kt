package com.adb.kitty.compose.data

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import androidx.annotation.Keep
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.*

@Keep
class UsbPortForwarder(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun startBridge(): Int {
        serverSocket = ServerSocket(0) // 随机拉起一个空闲环回端口
        val localPort = serverSocket!!.localPort
        isRunning = true

        thread(name = "UsbBridge-Server") {
            try {
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    handleClient(clientSocket)
                }
            } catch (e: Exception) { e.printStackTrace() }
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
                while (isRunning) {
                    val read = networkIn.read(buffer)
                    if (read == -1) break
                    var offset = 0
                    while (offset < read) {
                        val sent = conn.bulkTransfer(epOut, buffer, offset, read - offset, 2000)
                        if (sent <= 0) break
                        offset += sent
                    }
                }
            } catch (e: Exception) { } finally { runCatching { socket.close() } }
        }

        // 线程 2：物理 USB epIn 捕获数据 -> 灌回 Kadb 网络输入流
        thread(name = "UsbToNet") {
            val buffer = ByteArray(64 * 1024)
            try {
                while (isRunning) {
                    val read = conn.bulkTransfer(epIn, buffer, buffer.size, 2000)
                    if (read > 0) {
                        networkOut.write(buffer, 0, read)
                        networkOut.flush()
                    } else if (read < 0) {
                        break 
                    }
                }
            } catch (e: Exception) { } finally { runCatching { socket.close() } }
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
    }
}
