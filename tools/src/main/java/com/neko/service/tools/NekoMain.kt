package com.neko.service.tools

import android.os.Looper
import android.system.Os
import android.util.Log
import kotlinx.coroutines.*
import kotlin.system.exitProcess

object NekoMain {

    private const val TAG = "NekoDaemon"

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "=========================================")
        Log.i(TAG, "Neko 守护进程正在静默启动...")

        val uid = Os.getuid()
        val gid = Os.getgid()
        
        Log.i(TAG, "当前执行账号的 UID: $uid | GID: $gid")
        
        // 严格锁死真·Root环境
        if (uid != 0 || gid != 0) {
            Log.e(TAG, "严重错误：当前未运行在绝对 ROOT 环境下！进程即将退出。")
            exitProcess(1)
        }
        Log.i(TAG, "ROOT 安全沙箱穿透成功，进入绝对静默守护状态。")

        Looper.prepareMainLooper()

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
            // 保持 Log.d 打印，方便通过本地 sh 脚本跟踪，但对手机用户完全无感
            Log.d(TAG, "【心跳正常】#$heartbeatCount | 当前活跃线程数: ${Thread.activeCount()}")
            delay(5000)
        }
    }
}
