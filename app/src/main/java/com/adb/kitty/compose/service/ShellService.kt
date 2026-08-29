package com.adb.kitty.compose.service

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R
import com.adb.kitty.compose.*

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
import androidx.collection.MutableIntList
import androidx.collection.MutableObjectList
import androidx.collection.mutableIntListOf
import androidx.collection.mutableObjectListOf
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.lang.reflect.Field
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ShellService : Service() {

    private var currentWorkingDirectory = Environment.getExternalStorageDirectory()

    @Volatile
    private var currentProcess: java.lang.Process? = null

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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        terminateCurrentCommandInternal()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        terminateCurrentCommandInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL_ID = "com.adb.kitty.compose.core_service_channel_v2"
        const val GROUP_ID = "com.adb.kitty.compose.core_service_group"
        const val NOTIFICATION_ID = 102
    }

    private fun promoteToForeground() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val group = NotificationChannelGroup(GROUP_ID, "核心后台服务组")
            notificationManager.createNotificationChannelGroup(group)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shell 终端执行服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持后台 Shell 命令与日志流的稳定传输"
                setShowBadge(false)
                groupId = GROUP_ID
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

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val cwdSnapshot = synchronized(this) {
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
                    if (useRoot && !args.contains("su")) ProcessBuilder("su") else ProcessBuilder(args)
                } else {
                    ProcessBuilder("sh")
                }

                builder.redirectErrorStream(true)
                builder.environment().putAll(System.getenv())
                builder.directory(cwdSnapshot)

                process = builder.start()
                synchronized(this@ShellService) {
                    currentProcess = process
                }

                os = DataOutputStream(process.outputStream)
                val realExecutionCmd = when {
                    safeCmd.contains(" -c ") -> safeCmd.substringAfter(" -c ").trim('\'', '"', ' ', '\u00A0')
                    safeCmd == "su" || safeCmd.startsWith("su ") -> "id"
                    else -> safeCmd
                }

                os.writeBytes("$realExecutionCmd\n")
                os.writeBytes("exit\n")
                os.flush()

                // 启动抗压双线程字节抽吸
                startAntiStallPump(
                    processInputStream = process.inputStream,
                    ipcOutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
                )

                val exitCode = process.waitFor()
                
                // 拼接进程结束提示
                runCatching {
                    val exitMsg = "\n[进程结束，状态码: $exitCode]\n".toByteArray(Charsets.UTF_8)
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { 
                        it.write(exitMsg)
                        it.flush()
                    }
                }

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
                }
                runCatching { os?.close() }
                process?.let { killProcessTree(it) }
            }
        }

        return readSide
    }

    /**
     * 抗压双线程 Pump：保证写端与 IPC 解耦，防止管道卡死
     */
    private fun startAntiStallPump(
        processInputStream: InputStream,
        ipcOutputStream: OutputStream
    ) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val byteChunkQueue = ArrayBlockingQueue<ByteArray>(128)
        @Volatile var isProcessFinished = false

        val drainThread = Thread({
            val rawBuffer = ByteArray(32768)
            try {
                var bytesRead: Int
                while (processInputStream.read(rawBuffer).also { bytesRead = it } != -1) {
                    val chunk = rawBuffer.copyOf(bytesRead)
                    if (!byteChunkQueue.offer(chunk)) {
                        byteChunkQueue.poll()
                        byteChunkQueue.offer(chunk)
                    }
                }
            } catch (_: Exception) {
            } finally {
                isProcessFinished = true
            }
        }, "NativePipeDrainer")

        drainThread.start()

        val ipcWriterThread = Thread({
            try {
                while (!isProcessFinished || byteChunkQueue.isNotEmpty()) {
                    val chunk = byteChunkQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        ipcOutputStream.write(chunk)
                        ipcOutputStream.flush()
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ipcOutputStream.close() }
            }
        }, "IpcStreamWriter")

        ipcWriterThread.start()
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

    private fun killProcessTree(proc: java.lang.Process?) {
        proc ?: return
        runCatching {
            if (proc.isAliveCompat()) {
                val pid = getProcessPid(proc)
                if (pid > 0) {
                    runCatching { Runtime.getRuntime().exec("pkill -P $pid") }
                }
                proc.destroyForciblyCompat()
            }
        }
    }

    private fun getProcessPid(proc: java.lang.Process): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { proc.pid().toInt() }.getOrDefault(-1)
        } else {
            runCatching {
                val field: Field = proc.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(proc)
            }.getOrDefault(-1)
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
            currentProcess?.let { proc ->
                killProcessTree(proc)
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
        val tokens: MutableObjectList<String> = mutableObjectListOf()
        val charBuf: MutableIntList = mutableIntListOf()
        var inQuotes = false

        for (i in 0 until cmd.length) {
            val ch = cmd[i]
            if (ch == '\"' || ch == '\'') {
                inQuotes = !inQuotes
            } else if (ch == ' ' && !inQuotes) {
                if (charBuf.isNotEmpty()) {
                    tokens.add(charBufToString(charBuf))
                    charBuf.clear()
                }
            } else {
                charBuf.add(ch.code)
            }
        }
        if (charBuf.isNotEmpty()) {
            tokens.add(charBufToString(charBuf))
        }
        return tokens.asList()
    }

    private fun charBufToString(buf: MutableIntList): String {
        val chars = CharArray(buf.size)
        for (i in 0 until buf.size) {
            chars[i] = buf[i].toChar()
        }
        return String(chars)
    }
}
