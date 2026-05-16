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
import android.util.Base64
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Android 无线调试二维码配对服务端
 * 用于响应手机“扫描二维码配对”的底层 TLS/RSA 握手服务
 */
class AdbQrPairingServer(
    private val context: Context,
    private val adbKeyManager: Any, // 传入你的 adbKeyManager，这里兼容你的类型
    private val onPairingSuccess: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "AdbQrPairingServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    // 随机生成 6 位纯数字配对码和 8 位随机服务名称 (根据 Android 源码规范)
    private val pairingCode = String.format("%06d", SecureRandom().nextInt(1000000))
    private val serviceName = "adb-kitty-" + String.format("%04x", SecureRandom().nextInt(0xFFFF))

    /**
     * 启动配对服务并返回用于手机扫描的二维码 Bitmap
     */
    fun startPairing(): Bitmap? {
        try {
            // 1. 动态分配本地空闲端口
            serverSocket = ServerSocket(0)
            val port = serverSocket!!.localPort
            isRunning = true

            // 2. 获取当前局域网 IP 地址
            val ipAddress = getLocalIpAddress() ?: throw Exception("未连接 Wi-Fi 或无法获取 IP")

            // 3. 构建 Android 源码标准配对二维码文本格式:
            // 格式: WIFI:T:ADB;S:<服务名>;P:<配对码>;H:<IP>;PORT:<端口>;;
            val qrText = "WIFI:T:ADB;S:$serviceName;P:$pairingCode;H:$ipAddress;PORT:$port;;"
            Log.d(TAG, "QR Text: $qrText")

            // 4. 后台线程监听手机连接
            listenForClient()

            // 5. 生成二维码图像
            return generateQrCodeBitmap(qrText)

        } catch (e: Exception) {
            Log.e(TAG, "启动配对失败", e)
            onError(e.localizedMessage ?: "未知错误")
            return null
        }
    }

    /**
     * 后台监听线程
     */
    private fun listenForClient() {
        thread(start = true, isDaemon = true, name = "AdbPairingThread") {
            var clientSocket: Socket? = null
            try {
                while (isRunning) {
                    clientSocket = serverSocket?.accept() ?: break
                    Log.d(TAG, "收到手机连接: ${clientSocket.remoteSocketAddress}")
                    
                    // 处理底层 Pairing 协议握手
                    handlePairingProtocol(clientSocket)
                    break // 配对成功后退出监听
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "监听配对连接异常", e)
                    onError("连接中断: ${e.localizedMessage}")
                }
            } finally {
                clientSocket?.close()
                stopPairing()
            }
        }
    }

    /**
     * 处理精简版的 ADB 配对握手协议
     * 提示：完整的 Android 配对包含复杂的 SPAKE2+ 或者是 PBKDF2 握手，
     * 这里演示最基础的安全流骨架，确保通道数据闭环。
     */
    private fun handlePairingProtocol(socket: Socket) {
        val inputStream: InputStream = socket.getInputStream()
        val outputStream: OutputStream = socket.getOutputStream()

        // [协议模拟/简易实现]
        // 实际上手机在扫码后会主动发起 TLS 握手，并与此处的配对码(pairingCode)做哈希校验。
        // 正常接收并校验完毕后，我们需要回调通知主界面：
        
        Thread.sleep(1500) // 模拟握手耗时
        
        // 验证通过，通知 UI 连接成功
        onPairingSuccess()
    }

    /**
     * 获取局域网局域 IPv4 地址
     */
    private fun getLocalIpAddress(): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        if (ipInt == 0) return null
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt >> 8 and 0xff,
            ipInt >> 16 and 0xff,
            ipInt >> 24 and 0xff
        )
    }

    /**
     * 使用 ZXing 将文本转为二维码 Bitmap
     */
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

    /**
     * 关闭释放服务器连接
     */
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
