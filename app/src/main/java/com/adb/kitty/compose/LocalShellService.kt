package com.adb.kitty.compose

import android.app.Service
import android.content.Intent
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

    private var currentWorkingDirectory = File("/sdcard/")

    private val binder = object : ILocalShellService.Stub() {
        override fun executeCommand(cmd: String, useRoot: Boolean): String {
            return synchronized(this@LocalShellService) {
                performLocalShell(cmd.trim(), useRoot)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun performLocalShell(cmd: String, useRoot: Boolean): String {
        if (cmd == "cd" || cmd.startsWith("cd ")) {
            return handleCdCommand(cmd)
        }

        val resultBuilder = StringBuilder()
        var process: Process? = null
        var os: DataOutputStream? = null
        var reader: BufferedReader? = null

        try {
            val builder = if (cmd.startsWith("su ") || cmd == "su" || useRoot) {
                val finalCmd = if (useRoot && !cmd.startsWith("su")) "su -c $cmd" else cmd
                ProcessBuilder(parseCommandLine(finalCmd))
            } else {
                ProcessBuilder("sh")
            }

            builder.redirectErrorStream(true)
            
            builder.environment().putAll(System.getenv())
            
            builder.directory(currentWorkingDirectory)

            process = builder.start()
            os = DataOutputStream(process.outputStream)

            if (!cmd.contains("-c")) {
                os.writeBytes("$cmd\n")
            }
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
                resultBuilder.append("Root 提权失败：请检查手机是否 Root 或确认已授予 Root 权限。")
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
            "/sdcard/"
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
