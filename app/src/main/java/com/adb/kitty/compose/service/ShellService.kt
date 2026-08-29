package com.adb.kitty.compose.service

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.StringBuilder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R
import com.adb.kitty.compose.*

class ShellService : Service() {

    private var currentWorkingDirectory = Environment.getExternalStorageDirectory()
    
    @Volatile
    private var currentProcess: Process? = null

    private val watchdogScheduler = Executors.newSingleThreadScheduledExecutor()

    private val binder = object : IShellService.Stub() {
        
        override fun executeCommandStream(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
            return performLocalShellPipeline(cmd.trim(), useRoot)
        }

        override fun terminateCurrentCommand() {
            terminateCurrentCommandInternal()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        terminateCurrentCommandInternal()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        terminateCurrentCommandInternal()
        watchdogScheduler.shutdownNow()
        super.onDestroy()
    }

    private fun performLocalShellPipeline(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
        // 关键：新任务发起时，强制清理并终止上一条未结束的任务进程
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

        thread(start = true, name = "ShellStreamPump") {
            var process: Process? = null
            var os: DataOutputStream? = null
            var reader: BufferedReader? = null
            val pipeWriter = OutputStreamWriter(ParcelFileDescriptor.AutoCloseOutputStream(writeSide), "UTF-8")
            
            var watchdogTask: ScheduledFuture<*>? = null
            val lastOutputTime = AtomicLong(System.currentTimeMillis())

            try {
                val builder = if (useRoot || cmd.startsWith("su")) {
                    val baseSuCmd = if (cmd.contains(" -c ")) cmd.substringBefore(" -c ") else cmd
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
                builder.environment().putAll(System.getenv())
                builder.directory(cwdSnapshot)

                process = builder.start()
                synchronized(this@LocalShellService) {
                    currentProcess = process
                }

                // 启动无输出空闲超时看门狗：dumpsys 5秒空闲，其他命令 15秒空闲
                val idleTimeoutMs = if (cmd.contains("dumpsys")) 5000L else 15000L

                watchdogTask = watchdogScheduler.scheduleWithFixedDelay({
                    val idleMillis = System.currentTimeMillis() - lastOutputTime.get()
                    if (idleMillis >= idleTimeoutMs) {
                        if (process?.isAliveCompat() == true) {
                            runCatching {
                                pipeWriter.write("\n[警告] 检测到命令连续 ${idleTimeoutMs / 1000} 秒无输出（可能已卡死），强制终止...\n")
                                pipeWriter.flush()
                            }
                            process.destroyForciblyCompat()
                        }
                    }
                }, 1, 1, TimeUnit.SECONDS)

                os = DataOutputStream(process.outputStream)

                val realExecutionCmd = when {
                    cmd.contains(" -c ") -> {
                        cmd.substringAfter(" -c ").trim { 
                            it == '\'' || it == '"' || it.isWhitespace() || it.code == 160 
                        }
                    }
                    cmd == "su" || cmd.startsWith("su ") -> {
                        "id" 
                    }
                    else -> cmd
                }

                os.writeBytes("$realExecutionCmd\n")
                os.writeBytes("exit\n") 
                os.flush()

                reader = BufferedReader(InputStreamReader(process.inputStream, "UTF-8"))
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    lastOutputTime.set(System.currentTimeMillis())
                    pipeWriter.write(line + "\n")
                    pipeWriter.flush()
                }

                val exitCode = process.waitFor()
                pipeWriter.write("[进程结束，状态码: $exitCode]\n")
                pipeWriter.flush()

            } catch (e: Exception) {
                val errorMsg = if (useRoot && e is java.io.IOException) {
                    "Root 提权被拒绝：请解锁手机并在系统 Root 管理器中允许超级用户请求。\n"
                } else {
                    "执行中断或异常: ${e.message}\n"
                }
                
                runCatching {
                    pipeWriter.write(errorMsg)
                    pipeWriter.flush()
                }
            } finally {
                watchdogTask?.cancel(true)

                synchronized(this@LocalShellService) {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                }

                runCatching { os?.close() }
                runCatching { reader?.close() }
                runCatching { pipeWriter.close() }
                
                process?.let { proc ->
                    runCatching {
                        if (proc.isAliveCompat()) proc.destroyForciblyCompat()
                    }
                }
            }
        }

        return readSide
    }

    private fun Process.isAliveCompat(): Boolean {
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

    private fun Process.destroyForciblyCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.destroyForcibly()
        } else {
            this.destroy()
        }
    }

    private fun terminateCurrentCommandInternal() {
        synchronized(this@ShellService) {
            currentProcess?.let { proc ->
                runCatching {
                    if (proc.isAliveCompat()) {
                        proc.destroyForciblyCompat()
                    }
                }
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
            if (ch == '\"' || ch == '\'') {
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
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }
}
