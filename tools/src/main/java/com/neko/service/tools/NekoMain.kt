package com.neko.service.tools

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.system.Os
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.exitProcess

object NekoMain {

    private const val TAG = "NekoDaemon"
    // Unique identifier for the Unix Domain Socket file path inside the abstract namespace
    private const val SOCKET_NAME = "com.neko.service.tools.socket"

    private val daemonScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("NekoCoreScope"))
    
    // Thread-safe list to hold active connected Compose App client sockets
    private val connectedClients = CopyOnWriteArrayList<PrintWriter>()

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "=========================================")
        logToClient("Neko 守护进程正在静默启动...")

        val uid = Os.getuid()
        val gid = Os.getgid()
        logToClient("当前执行账号的 UID: $uid | GID: $gid")
        
        if (uid != 0 || gid != 0) {
            Log.e(TAG, "严重错误：当前未运行在绝对 ROOT 环境下！")
            exitProcess(1)
        }
        logToClient("ROOT 安全沙箱穿透成功，进入绝对静默守护状态。")

        Looper.prepare()

        // 1. Launch the Core Business Loop (Heartbeats, etc.)
        daemonScope.launch {
            try {
                startCoreBusinessLoop()
            } catch (e: Exception) {
                logToClient("核心任务循环异常: ${e.message}", e)
            }
        }

        // 2. Launch the IPC Socket Server to listen for your Compose App
        daemonScope.launch {
            try {
                startIpcServer()
            } catch (e: Exception) {
                logToClient("IPC 服务器崩溃: ${e.message}", e)
            }
        }

        Looper.loop()
        logToClient("警告：Looper 循环意外终止，守护进程退出！")
        exitProcess(0)
    }

    /**
     * Dual-purpose logging API. 
     * Prints to native system logcat AND pushes live events to the Compose App interface.
     */
    private fun logToClient(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.i(TAG, message)
        }
        
        // Broadcast formatting to the connected app interface
        val formattedLog = if (throwable != null) {
            "[DAEMON] $message | Error: ${throwable.localizedMessage}\n"
        } else {
            "[DAEMON] $message\n"
        }

        // Send out to all active clients safely
        connectedClients.forEach { writer ->
            daemonScope.launch(Dispatchers.IO) {
                try {
                    writer.print(formattedLog)
                    writer.flush()
                } catch (e: Exception) {
                    // Clean up broken connection pipes automatically
                    connectedClients.remove(writer)
                }
            }
        }
    }

    private suspend fun startIpcServer() = withContext(Dispatchers.IO) {
        logToClient("正在创建本地 IPC Unix Domain Socket 监听服务器...")
        val serverSocket = LocalServerSocket(SOCKET_NAME)
        
        while (isActive) {
            try {
                val clientSocket = serverSocket.accept()
                logToClient("检测到 Jetpack Compose App 客户端接入成功！")
                
                daemonScope.launch(Dispatchers.IO) {
                    handleClientSession(clientSocket)
                }
            } catch (e: Exception) {
                delay(1000)
            }
        }
    }

    private suspend fun handleClientSession(socket: LocalSocket) = withContext(Dispatchers.IO) {
        val writer = PrintWriter(socket.outputStream)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        
        // Register client to start receiving the live API output logs
        connectedClients.add(writer)
        
        try {
            // Welcome handshake
            writer.print("[SERVER_READY] Connection Established\n")
            writer.flush()

            var command: String?
            // Keep reading incoming commands sent by your Compose App
            while (reader.readLine().also { command = it } != null) {
                val currentCmd = command ?: break
                logToClient("收到来自 App 的指令: $currentCmd")
                
                // Example router: Handle commands sent by the UI layer
                when (currentCmd.trim()) {
                    "PING" -> {
                        writer.print("[RESPONSE] PONG\n")
                        writer.flush()
                    }
                    "GET_STATUS" -> {
                        writer.print("[RESPONSE] STATUS_OK\n")
                        writer.flush()
                    }
                    else -> {
                        writer.print("[RESPONSE] UNKNOWN_COMMAND\n")
                        writer.flush()
                    }
                }
            }
        } catch (e: Exception) {
            logToClient("客户端连接会话异常断开: ${e.message}")
        } finally {
            connectedClients.remove(writer)
            runCatching { socket.close() }
        }
    }

    private suspend fun startCoreBusinessLoop() {
        var heartbeatCount = 0
        while (true) {
            heartbeatCount++
            logToClient("【心跳状态反馈】#$heartbeatCount | 活跃并发线数: ${Thread.activeCount()}")
            delay(5000)
        }
    }
}
