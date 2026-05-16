/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import kotlin.concurrent.thread

class AdbWifiClient(
    private val host: String,
    private val port: Int,
    private val keyManager: AdbKeyManager,
    private val privateKey: PrivateKey,
    private val onLogReceived: (String) -> Unit,
    private val onAuthSuccess: () -> Unit,
    private val onConnectionClosed: () -> Unit
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    
    @Volatile private var isConnected = false
    private var authFailureCount = 0

    fun connect() {
        thread(name = "AdbWifi-Receiver") {
            try {
                socket = Socket()
                socket?.connect(InetSocketAddress(host, port), 5000)
                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()
                isConnected = true
                
                sendPacket(0x4e584e43, 0x01000001, 262144, "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,openscreen_mdns,compression_zstd\u0000".toByteArray())

                val headerBytes = ByteArray(24)
                while (isConnected) {
                    readFully(inputStream!!, headerBytes)
                    val message = AdbMessage.parseHeader(headerBytes)

                    var payload: ByteArray? = null
                    if (message.dataLength > 0) {
                        payload = ByteArray(message.dataLength)
                        readFully(inputStream!!, payload)
                    }

                    dispatchMessage(message, payload)
                }
            } catch (e: Exception) {
                onLogReceived("[断开] 与远程无线设备的连接已切断。")
                disconnect()
            }
        }
    }

    private fun dispatchMessage(msg: AdbMessage, payload: ByteArray?) {
        when (msg.command) {
            0x48545541 -> { // AUTH
                if (msg.arg0 == 1) {
                    if (authFailureCount < 1) {
                        payload?.let {
                            val signature = keyManager.signAdbToken(it, privateKey)
                            sendPacket(0x48545541, 2, 0, signature)
                            authFailureCount++
                        }
                    } else {
                        val pubPayload = keyManager.getAdbAuthPayload()
                        sendPacket(0x48545541, 3, 0, pubPayload)
                    }
                }
            }
            0x4e584e43 -> { // CNXN
                authFailureCount = 0
                onAuthSuccess()
            }
            0x45545257 -> { // WRTE
                payload?.let {
                    val resultChunk = String(it, Charsets.UTF_8)
                    onLogReceived(resultChunk)
                }
                sendPacket(0x59414b4f, msg.arg0, msg.arg1, null) // 回应 OKAY
            }
            0x45534c43 -> { // CLSE
                sendPacket(0x59414b4f, msg.arg0, msg.arg1, null)
            }
        }
    }

    fun sendPacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val dataLength = payload?.size ?: 0
        val checksum = if (payload != null) calculateChecksum(payload) else 0
        val magic = command xor -0x1
        val header = AdbMessage(command, arg0, arg1, dataLength, checksum, magic)
        
        synchronized(this) {
            try {
                outputStream?.write(header.toByteArray())
                payload?.let { outputStream?.write(it) }
                outputStream?.flush()
            } catch (e: IOException) {
                disconnect()
                throw e
            }
        }
    }

    private fun calculateChecksum(payload: ByteArray): Int {
        var sum = 0
        for (b in payload) { sum += b.toInt() and 0xFF }
        return sum
    }

    private fun readFully(source: InputStream, target: ByteArray) {
        var bytesRead = 0
        while (bytesRead < target.size) {
            val result = source.read(target, bytesRead, target.size - bytesRead)
            if (result == -1) throw IOException("End of stream")
            bytesRead += result
        }
    }

    fun disconnect() {
        if (!isConnected) return // 避免重复触发
        isConnected = false
        onConnectionClosed()
        try { socket?.close() } catch (_: Exception) {}
    }
}
