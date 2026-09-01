package com.adb.kitty.service

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R
import com.adb.kitty.*

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Field
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi

class ShellService : Service() {

    private var currentWorkingDirectory = Environment.getExternalStorageDirectory()

    @Volatile
    private var currentProcess: java.lang.Process? = null

    @Volatile
    private var currentTaskKey: String? = null

    private val binder = object : IShellService.Stub() {
        override fun executeCommandStream(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
            return performLocalShellPipeline(cmd.trim(), useRoot)
        }

        override fun terminateCurrentCommand() {
            terminateCurrentCommandInternal()
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        terminateCurrentCommandInternal()
        return false
    }

    override fun onDestroy() {
        terminateCurrentCommandInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "com.adb.kitty.core_service_channel_v2"
        const val GROUP_ID = "com.adb.kitty.core_service_group"
        const val NOTIFICATION_ID = 102
    }

    private fun promoteToForeground() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val groupName = getString(R.string.action_service_aaf)
            val channelGroup = NotificationChannelGroup(GROUP_ID, groupName)
            notificationManager.createNotificationChannelGroup(channelGroup)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shell 终端执行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台 Shell 命令与日志流的稳定传输"
                setShowBadge(false)
                group = GROUP_ID
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shell 服务运行中")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }.onFailure {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun performLocalShellPipeline(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
        terminateCurrentCommandInternal()

        val taskKey = "TASK_${System.currentTimeMillis()}_${(1000..9999).random()}"

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val cwdSnapshot = synchronized(this) {
            currentTaskKey = taskKey
            if (cmd == "cd" || cmd.startsWith("cd ")) {
                val cdResult = handleCdCommand(cmd)
                writeDirectMessageToPipe(writeSide, cdResult)
                return readSide
            }
            currentWorkingDirectory
        }

        thread(start = true, name = "ShellStreamController") {
            var process: java.lang.Process? = null
            var os: DataOutputStream? = null

            try {
                val safeCmd = sanitizeCommand(cmd)

                val builder = if (useRoot || safeCmd.startsWith("su")) {
                    val baseSuCmd = if (safeCmd.contains(" -c ")) safeCmd.substringBefore(" -c ") else safeCmd
                    val args = parseCommandLine(baseSuCmd.ifBlank { "su" })

                    if (useRoot && !args.contains("su")) {
                        ProcessBuilder("su")
                    } else {
                        ProcessBuilder(args)
                    }
                } else {
                    ProcessBuilder("sh")
                }

                builder.redirectErrorStream(true)

                val envMap = builder.environment()
                envMap.putAll(System.getenv())
                envMap["SHELL_TASK_KEY"] = taskKey

                builder.directory(cwdSnapshot)

                process = builder.start()
                synchronized(this@ShellService) {
                    currentProcess = process
                }

                os = DataOutputStream(process.outputStream)

                val realExecutionCmd = when {
                    safeCmd.contains(" -c ") -> {
                        safeCmd.substringAfter(" -c ").trim {
                            it == '\'' || it == '"' || it.isWhitespace() || it.code == 160
                        }
                    }
                    safeCmd == "su" || safeCmd.startsWith("su ") -> {
                        "id"
                    }
                    else -> safeCmd
                }

                val taggedCmd = "export SHELL_TASK_KEY='$taskKey'; $realExecutionCmd"

                os.writeBytes("$taggedCmd\n")
                os.writeBytes("exit\n")
                os.flush()

                val activeProcess = process
                startAntiStallPump(
                    processInputStream = activeProcess.inputStream,
                    ipcOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide),
                    onPumpComplete = { stream ->
                        val exitCode = runCatching { activeProcess.waitFor() }.getOrDefault(-1)
                        val exitMsg = "\n[进程结束，状态码: $exitCode]\n".toByteArray(Charsets.UTF_8)
                        runCatching {
                            stream.write(exitMsg)
                            stream.flush()
                        }
                    }
                )

            } catch (e: Exception) {
                runCatching {
                    OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(writeSide), "UTF-8").use { writer ->
                        val errorMsg = if (useRoot && e is java.io.IOException) {
                            "Root 提权被拒绝：请解锁手机并在系统 Root 管理器中允许超级用户请求。\n"
                        } else {
                            "执行中断或异常: ${e.message}\n"
                        }
                        writer.write(errorMsg)
                        writer.flush()
                    }
                }
            } finally {
                synchronized(this@ShellService) {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                    if (currentTaskKey == taskKey) {
                        currentTaskKey = null
                    }
                }
                runCatching { os?.close() }
                process?.let { killProcessTree(it, taskKey) }
            }
        }

        return readSide
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startAntiStallPump(
        processInputStream: InputStream,
        ipcOutputStream: OutputStream,
        onPumpComplete: (OutputStream) -> Unit
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val channel = Channel<ByteArray>(Channel.UNLIMITED)

        val drainThread = Thread({
            val rawBuffer = ByteArray(32768)
            try {
                var bytesRead: Int
                while (processInputStream.read(rawBuffer).also { bytesRead = it } != -1) {
                    val chunk = rawBuffer.copyOf(bytesRead)
                    channel.trySend(chunk)
                }
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }, "NativePipeDrainer")

        drainThread.start()

        val ipcWriterThread = Thread({
            try {
                runBlocking {
                    val batchBuffer = ByteArrayOutputStream()
                    var lastFlushTime = System.currentTimeMillis()

                    for (chunk in channel) {
                        batchBuffer.write(chunk)

                        // 耗尽 Channel 中当前所有已存入的碎片数据
                        while (true) {
                            val nextChunk = channel.tryReceive().getOrNull() ?: break
                            batchBuffer.write(nextChunk)
                            if (batchBuffer.size() >= 65536) break
                        }

                        val now = System.currentTimeMillis()
                        // 限流阈值：达到 ~8ms (120 FPS 频率) 或 缓冲区存满 64KB 或 管道空闲时批量刷入 IPC
                        if (now - lastFlushTime >= 8 || batchBuffer.size() >= 65536 || channel.isEmpty) {
                            batchBuffer.writeTo(ipcOutputStream)
                            ipcOutputStream.flush()
                            batchBuffer.reset()
                            lastFlushTime = now
                        }
                    }

                    // 写入末尾剩余字节
                    if (batchBuffer.size() > 0) {
                        batchBuffer.writeTo(ipcOutputStream)
                        ipcOutputStream.flush()
                        batchBuffer.reset()
                    }

                    onPumpComplete(ipcOutputStream)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ipcOutputStream.close() }
            }
        }, "IpcStreamWriter")

        ipcWriterThread.start()

        // 阻塞当前控制线程，确保抽吸和写入完毕后才允许退出 try 块
        runCatching { drainThread.join() }
        runCatching { ipcWriterThread.join() }
    }

    private fun sanitizeCommand(cmd: String): String {
        var processedCmd = cmd.trim()
        if (processedCmd == "dumpsys" || processedCmd.startsWith("dumpsys ")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (processedCmd == "dumpsys") return "dumpsys -t 3"
                if (!processedCmd.contains(" -t ") && !processedCmd.contains(" --timeout ")) {
                    processedCmd = processedCmd.replaceFirst("dumpsys", "dumpsys -t 3")
                }
            }
        }
        return processedCmd
    }

    private fun killProcessTree(proc: java.lang.Process?, taskKey: String? = null) {
        proc ?: return
        runCatching {
            // 仅在进程依然存活的情况下才执行清理动作
            if (proc.isAliveCompat()) {
                if (!taskKey.isNullOrEmpty()) {
                    runCatching {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f '$taskKey'"))
                    }
                }

                val pid = getProcessPid(proc)
                if (pid > 1000) {
                    runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -P $pid")) }
                    runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                } else {
                    proc.destroyForciblyCompat()
                }
            }
        }
    }

    private fun getProcessPid(proc: java.lang.Process): Int {
        return runCatching {
            val field: Field = proc.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(proc)
        }.getOrElse {
            val procStr = proc.toString()
            val pidMatch = Regex("pid=(\\d+)").find(procStr)
            pidMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
    }

    private fun java.lang.Process.isAliveCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.isAlive
        } else {
            try {
                this.exitValue()
                false
            } catch (e: IllegalThreadStateException) {
                true
            }
        }
    }

    private fun java.lang.Process.destroyForciblyCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.destroyForcibly()
        } else {
            this.destroy()
        }
    }

    private fun terminateCurrentCommandInternal() {
        synchronized(this@ShellService) {
            val key = currentTaskKey
            currentTaskKey = null

            if (!key.isNullOrEmpty()) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f '$key'"))
                }
            }

            currentProcess?.let { proc ->
                killProcessTree(proc, key)
                currentProcess = null
            }
        }
    }

    private fun writeDirectMessageToPipe(writeSide: ParcelFileDescriptor, message: String) {
        thread {
            runCatching {
                OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(writeSide), "UTF-8").use { writer ->
                    writer.write(message + "\n")
                    writer.flush()
                }
            }
        }
    }

    private fun handleCdCommand(cmd: String): String {
        val targetPath = if (cmd == "cd") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            cmd.removePrefix("cd ").trim().removeSurrounding("\"", "\"")
        }

        val newDir = if (targetPath.startsWith("/")) {
            File(targetPath)
        } else {
            File(currentWorkingDirectory, targetPath)
        }

        val canonicalDir = runCatching { newDir.canonicalFile }.getOrElse { newDir }

        if (!canonicalDir.exists()) {
            return "sh: cd: $targetPath: No such file or directory"
        }
        if (!canonicalDir.isDirectory) {
            return "sh: cd: $targetPath: Not a directory"
        }

        currentWorkingDirectory = canonicalDir
        return "[系统] 工作目录已成功切至: ${currentWorkingDirectory.absolutePath}"
    }

    private fun parseCommandLine(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        for (ch in cmd.toCharArray()) {
            if (ch == '"' || ch == '\'') {
                inQuotes = !inQuotes
            } else if (ch == ' ' && !inQuotes) {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                }
            } else {
                sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) {
            tokens.add(sb.toString())
        }
        return tokens
    }
}
