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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object FastbootExecutor {

    /**
     * 异步执行 fastboot 命令，并将日志实时回调
     * @param context 上下文
     * @param args 参数列表
     * @param onLog 日志回调函数（运行在主线程，可直接更新 UI）
     */
    suspend fun execute(
        context: Context, 
        args: List<String>, 
        onLog: (String) -> Unit
    ) = withContext(Dispatchers.IO) { // 切换到 IO 线程执行，防止界面卡死

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val fastbootFile = File(nativeDir, "libfastboot.so")

        if (!fastbootFile.exists()) {
            withContext(Dispatchers.Main) {
                onLog("❌ 错误：未找到 libfastboot.so 文件，请检查 jniLibs 配置。\n")
            }
            return@withContext
        }

        val command = mutableListOf<String>().apply {
            add(fastbootFile.absolutePath)
            addAll(args)
        }

        // 打印即将执行的完整命令
        withContext(Dispatchers.Main) {
            onLog("🚀 执行命令: fastboot ${args.joinToString(" ")}\n")
        }

        try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true) // 合并标准错误和标准输出
            val process = processBuilder.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            // 实时循环读取单行输出
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line // 局部变量捕获
                if (currentLine != null) {
                    // 切回主线程，把这一行日志抛给 MainActivity
                    withContext(Dispatchers.Main) {
                        onLog("$currentLine\n")
                    }
                }
            }

            val exitCode = process.waitFor()
            withContext(Dispatchers.Main) {
                onLog("🏁 进程已结束，退出码: $exitCode\n\n")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onLog("💥 发生异常: ${e.message}\n\n")
            }
        }
    }
}
