package com.adb.kitty.compose

import android.app.Service
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.lang.StringBuilder
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

class LocalShellService : Service() {

    private var currentWorkingDirectory = Environment.getExternalStorageDirectory()

    private val binder = object : ILocalShellService.Stub() {
        override fun executeCommand(cmd: String, useRoot: Boolean): String {
            return performLocalShell(cmd.trim(), useRoot)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun performLocalShell(cmd: String, useRoot: Boolean): String {
        val cwdSnapshot = synchronized(this) {
            if (cmd == "cd" || cmd.startsWith("cd ")) {
                return handleCdCommand(cmd)
            }
            currentWorkingDirectory
        }

        val resultBuilder = StringBuilder()
        var process: Process? = null
        var os: DataOutputStream? = null
        var reader: BufferedReader? = null

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
            os = DataOutputStream(process.outputStream)

            val realExecutionCmd = when {
                cmd.contains(" -c ") -> {
                    cmd.substringAfter(" -c ").removeSurrounding("\"", "\"").removeSurrounding("'", "'")
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
                resultBuilder.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            resultBuilder.append("[进程结束，状态码: $exitCode]")

        } catch (e: Exception) {
            if (useRoot && e is java.io.IOException) {
                resultBuilder.append("Root 提权被拒绝：请解锁手机，并在系统的 Root 管理器中允许超级用户请求。")
            } else {
                resultBuilder.append("执行异常: ${e.message}")
            }
        } finally {
            runCatching { os?.close() }
            runCatching { reader?.close() }
            runCatching { process?.destroy() }
        }

        return resultBuilder.toString().trim()
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
