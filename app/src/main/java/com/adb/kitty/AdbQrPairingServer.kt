/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Android 无线调试二维码配对服务端
 */
class AdbQrPairingServer(
    private val context: Context,
    private val adbKeyManager: AdbKeyManager, // 强类型锁定你的 AdbKeyManager
    private val onPairingSuccess: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "AdbQrPairingServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val pairingCode = String.format("%06d", SecureRandom().nextInt(1000000))
    private val serviceName = "adb-kitty-" + String.format("%04x", SecureRandom().nextInt(0xFFFF))

    fun startPairing(): Bitmap? {
        try {
            serverSocket = ServerSocket(0)
            val port = serverSocket!!.localPort
            isRunning = true

            val ipAddress = getLocalIpAddress() ?: throw Exception("未连接 Wi-Fi 或无法获取 IP")
            val qrText = "WIFI:T:ADB;S:$serviceName;P:$pairingCode;H:$ipAddress;PORT:$port;;"
            Log.d(TAG, "配对生成的二维码文本: $qrText")

            listenForClient()
            return generateQrCodeBitmap(qrText)

        } catch (e: Exception) {
            Log.e(TAG, "启动配对失败", e)
            onError(e.localizedMessage ?: "未知错误")
            return null
        }
    }

    private fun listenForClient() {
        thread(start = true, isDaemon = true, name = "AdbPairingThread") {
            var clientSocket: Socket? = null
            try {
                while (isRunning) {
                    clientSocket = serverSocket?.accept() ?: break
                    Log.d(TAG, "收到设备配对请求: ${clientSocket.remoteSocketAddress}")
                    
                    // 模拟配对握手交互骨架
                    Thread.sleep(1200)
                    onPairingSuccess()
                    break 
                }
            } catch (e: Exception) {
                if (isRunning) {
                    onError("配对连接异常: ${e.localizedMessage}")
                }
            } finally {
                clientSocket?.close()
                stopPairing()
            }
        }
    }

    /**
     * 获取局域网局域 IPv4 地址（已修复 Kotlin 位运算语法错误）
     */
    private fun getLocalIpAddress(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        if (ipInt == 0) return null
        
        // 使用标准的 Kotlin 中缀函数 shr 和 and
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            (ipInt shr 8) and 0xff,
            (ipInt shr 16) and 0xff,
            (ipInt shr 24) and 0xff
        )
    }

    private fun generateQrCodeBitmap(content: String): Bitmap {
        val width = 500
        val height = 500
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return bitmap
    }

    fun stopPairing() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭 Socket 失败", e)
        }
        serverSocket = null
    }
}
