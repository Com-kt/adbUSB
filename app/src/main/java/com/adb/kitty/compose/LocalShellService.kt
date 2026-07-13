package com.adb.kitty.compose

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.StringBuilder
import kotlin.concurrent.thread
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

class LocalShellService : Service() {

    private var currentWorkingDirectory = Environment.getExternalStorageDirectory()
    
    @Volatile
    private var currentProcess: Process? = null

    private val binder = object : ILocalShellService.Stub() {
        
        override fun executeCommandStream(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
            return performLocalShellPipeline(cmd.trim(), useRoot)
        }

        override fun terminateCurrentCommand() {
            synchronized(this@LocalShellService) {
                currentProcess?.let {
                    runCatching { 
                        it.destroy()
                    }
                    currentProcess = null
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun performLocalShellPipeline(cmd: String, useRoot: Boolean): ParcelFileDescriptor {
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
                    pipeWriter.write(line + "\n")
                    pipeWriter.flush()
                }

                val exitCode = process.waitFor()
                pipeWriter.write("[进程结束，状态码: $exitCode]\n")
                pipeWriter.flush()

            } catch (e: Exception) {
                val errorMsg = if (useRoot && e is java.io.IOException) {
                    "Root 提权被拒绝：请解锁手机，并在系统的 Root 管理器中允许超级用户请求。\n"
                } else if (e is java.io.IOException && e.message?.contains("Stream closed") == true) {
                    "[进程已被用户手动终止]\n"
                } else {
                    "执行异常: ${e.message}\n"
                }
                
                runCatching {
                    pipeWriter.write(errorMsg)
                    pipeWriter.flush()
                }
            } finally {
                synchronized(this@LocalShellService) {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                }
                runCatching { os?.close() }
                runCatching { reader?.close() }
                runCatching { pipeWriter.close() }
                runCatching { process?.destroy() }
            }
        }

        return readSide
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
