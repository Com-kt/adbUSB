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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream

object FastbootExecutor {

    /**
     * 执行 fastboot 命令
     * @param context 上下文
     * @param args fastboot 的参数列表，例如：listOf("devices") 或 listOf("flash", "boot", "/sdcard/boot.img")
     * @return 命令行输出的字符串结果
     */
    fun execute(context: Context, args: List<String>): String {
        // 1. 获取系统解压 Native 库的绝对路径 (通常是 /data/app/~~.../lib/arm64)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val fastbootFile = File(nativeDir, "libfastboot.so")

        // 2. 检查文件是否存在
        if (!fastbootFile.exists()) {
            return "错误：未找到 libfastboot.so 文件，请检查 jniLibs 配置。"
        }

        // 3. 拼接完整的命令。 第一项必须是可执行文件的绝对路径
        val command = mutableListOf<String>()
        command.add(fastbootFile.absolutePath)
        command.addAll(args)

        val output = StringBuilder()

        try {
            // 4. 使用 ProcessBuilder 启动进程
            val processBuilder = ProcessBuilder(command)
            // 将标准错误流（stderr）和标准输出流（stdout）合并，方便一起读取 fastboot 的提示信息
            processBuilder.redirectErrorStream(true) 
            
            val process = processBuilder.start()

            // 5. 读取 fastboot 执行后返回的文本
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }

            // 6. 等待进程执行结束，并获取退出码（0 代表成功）
            val exitCode = process.waitFor()
            output.append("[进程已结束，退出码: $exitCode]")

        } catch (e: Exception) {
            e.printStackTrace()
            return "执行期间发生异常: ${e.message}"
        }

        return output.toString()
    }
}
