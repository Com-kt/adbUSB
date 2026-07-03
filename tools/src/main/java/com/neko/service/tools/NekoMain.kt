package com.neko.service.tools

import android.os.Looper
import android.system.Os
import android.util.Log
import kotlinx.coroutines.*
import kotlin.system.exitProcess

object NekoMain {

    private const val TAG = "NekoDaemon"
    private const val NOTIFY_TAG = "NekoStatusTag"

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "=========================================")
        Log.i(TAG, "Neko 守护进程正在启动...")

        val uid = Os.getuid()
        val gid = Os.getgid()
        
        Log.i(TAG, "当前执行账号的 UID: $uid | GID: $gid")
        
        if (uid != 0 || gid != 0) {
            Log.e(TAG, "严重错误：当前未运行在绝对 ROOT 环境下！进程即将退出。")
            exitProcess(1)
        }
        Log.i(TAG, "ROOT 权限与用户组确认成功，已接管底层最高控制权。")

        Looper.prepareMainLooper()

        // 🚀 第一次点火成功：发送一条实时通知
        sendSystemNotification("Neko 安全中心", "守护进程已成功点火！当前运行在真·Root域 (UID 0)。")

        val daemonScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("NekoCoreScope"))

        Log.i(TAG, "准备就绪，正在激活核心后台轮询任务...")
        daemonScope.launch {
            try {
                startCoreBusinessLoop()
            } catch (e: Exception) {
                Log.e(TAG, "核心任务循环发生未捕获异常: ${e.message}", e)
            }
        }

        Looper.loop()

        Log.w(TAG, "警告：Looper 循环意外终止，守护进程退出！")
        exitProcess(0)
    }

    private suspend fun startCoreBusinessLoop() {
        var heartbeatCount = 0
        
        while (true) {
            heartbeatCount++
            Log.d(TAG, "【心跳正常】#$heartbeatCount | 当前活跃线程数: ${Thread.activeCount()}")
            
            // 💓 每到第 5 次心跳，更新一次通知状态
            if (heartbeatCount % 5 == 0) {
                sendSystemNotification(
                    "Neko 运行监控", 
                    "服务正在安全运行中... 心跳计数: #$heartbeatCount"
                )
            }
            
            delay(5000)
        }
    }

    /**
     * 底层硬核硬穿透：利用系统 cmd 命令发送实时通知
     */
    private fun sendSystemNotification(title: String, text: String) {
        try {
            // 使用 cmd notification post [-t 标题] <标签> <内容>
            // 在现代 Android 系统中，如果未指定渠道，系统会自动将其归类到系统的临时/命令行通知渠道中
            val command = arrayOf(
                "cmd", "notification", "post",
                "-t", title,
                NOTIFY_TAG,
                text
            )
            
            // 极其轻量地拉起系统自带的通知投递器
            val process = Runtime.getRuntime().exec(command)
            process.waitFor() // 等待投递完成
            
            Log.i(TAG, "流式通知投递成功 -> [$title] $text")
        } catch (e: Exception) {
            Log.e(TAG, "流式通知投递失败: ${e.message}", e)
        }
    }
}
