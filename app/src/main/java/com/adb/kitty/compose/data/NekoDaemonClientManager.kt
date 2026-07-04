package com.adb.kitty.compose.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

class NekoDaemonClientManager {

    companion object {
        private const val SOCKET_NAME = "com.neko.service.tools.socket"
    }
    
    private val _incomingLogs = MutableSharedFlow<String>(replay = 0)
    val incomingLogs: SharedFlow<String> = _incomingLogs

    private var socketWriter: PrintWriter? = null
    private var isConnected = false

    suspend fun connectAndListen() = withContext(Dispatchers.IO) {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(SOCKET_NAME))
            socketWriter = PrintWriter(socket.outputStream)
            val reader = BufferedReader(InputStreamReader(socket.inputStream))
            isConnected = true
            
            _incomingLogs.emit("成功连接至 Neko 守护进程套接字接口点。")

            var line: String? = null
            while (isConnected && reader.readLine().also { line = it } != null) {
                line?.let { _incomingLogs.emit(it) }
            }
        } catch (e: Exception) {
            _incomingLogs.emit("与守护进程连接发生中断，准备重连... [${e.localizedMessage}]")
        } finally {
            isConnected = false
            socketWriter = null
            runCatching { socket.close() }
        }
    }

    fun sendCommand(command: String) {
        if (isConnected) {
            Thread {
                try {
                    socketWriter?.print("$command\n")
                    socketWriter?.flush()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
    
    fun disconnect() {
        isConnected = false
    }
}
