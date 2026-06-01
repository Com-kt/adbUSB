/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Choreographer
import android.view.Display
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RefreshRateInspector(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    // 外部传入的 UI TextView 日志追加回调 lambda
    private val onLogAppend: (String) -> Unit 
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    
    private var inspectorJob: Job? = null
    private var frameCount = 0
    private var rootCpuBinder: ICpuBinder? = null
    
    // 暂存连接结果的回调
    private var onConnectedCallback: ((Boolean) -> Unit)? = null

    // 跨进程服务连接监听器
    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootCpuBinder = ICpuBinder.Stub.asInterface(service)
            onLogAppend("[系统] 🛑 免注册 Daemon 进程拉起成功，6大硬件节点解锁完成！")
            
            // 通知 MainActivity 连接成功
            onConnectedCallback?.invoke(true)
            onConnectedCallback = null // 及时释放
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootCpuBinder = null
            onConnectedCallback?.invoke(false)
            onConnectedCallback = null
            onLogAppend("[警告] ⚠️ 远程特权服务意外断开连接！")
        }
    }

    // 硬件屏幕刷新脉冲信号计数器
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 判断当前特权进程是否已经就绪
     */
    fun isRootServiceConnected(): Boolean {
        return rootCpuBinder != null
    }

    /**
     * 1. 🔥 核心黑科技：免注册模式异步启动 Daemon 服务
     */
    fun bindRootService(onResult: (Boolean) -> Unit) {
        if (rootCpuBinder != null) {
            onResult(true)
            return
        }
        this.onConnectedCallback = onResult
        
        // 核心改动：使用显式 Intent 指向你的类。
        // 因为清单里没有注册它，libsu 会自动接管，在底层用 Shell 命令行通过 app_process 强行把它当成守护进程孵化！
        val intent = Intent(context, GhzRootService::class.java)
        RootService.bind(intent, rootConnection)
    }

    /**
     * 2. 启动全链路高刷时钟矩阵监测
     */
    fun start() {
        if (inspectorJob != null && inspectorJob!!.isActive) {
            onLogAppend("[提示] 测试已经在运行中，请勿重复启动。")
            return
        }

        // 🔥 捍卫核心逻辑：启动开幕首期必须无情检测并打印硬件面板物理驱动支持的全部高刷档位！
        onLogAppend("==== 🔍 开始检测硬件面板物理档位 ====")
        try {
            defaultDisplay.supportedModes.forEach { mode ->
                onLogAppend(
                    String.format(
                        Locale.getDefault(),
                        "物理 ID: %d -> %dx%d @ %.2f Hz",
                        mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate
                    )
                )
            }
        } catch (e: Exception) {
            onLogAppend("[错误] 无法获取硬件面板物理档位: ${e.message}")
        }
        onLogAppend("====================================\n")

        // 激活 Choreographer 硬件计数
        frameCount = 0
        Choreographer.getInstance().postFrameCallback(frameCallback)

        // 启动高能常驻异步协程，把高频的文件 IO 和拼表操作全部甩给后台线程池，绝不拖累高刷渲染
        inspectorJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    delay(1000) // 每秒精准采点刷新一次

                    val currentHardwareHz = defaultDisplay.refreshRate
                    val capturedFrames = frameCount
                    frameCount = 0 

                    // 实例化 8行6列 的二维物理时钟矩阵
                    val allMatrix = Array(8) { DoubleArray(6) }
                    for (core in 0..7) {
                        // 跨进程 Binder 一次性从 Daemon 服务把该核心 6 项数据打包端回来
                        allMatrix[core] = rootCpuBinder?.getAllCpuFreqData(core) ?: DoubleArray(6)
                    }

                    // 矩阵方阵对齐标签
                    val nodeLabels = arrayOf(
                        "cpuinfo_cur_freq ",
                        "cpuinfo_max_freq ",
                        "cpuinfo_min_freq ",
                        "scaling_max_freq ",
                        "scaling_min_freq ",
                        "scaling_cur_freq "
                    )

                    val logBuilder = StringBuilder()
                    // 组装头部行：实时刷新率与渲染帧率
                    logBuilder.append(String.format(Locale.getDefault(), "[监测] 屏幕: %.1fHz (实际: %dFPS)\n", currentHardwareHz, capturedFrames))

                    // 垂直循环 6 个文件分类
                    for (fileIndex in 0..5) {
                        logBuilder.append("  └─ ").append(nodeLabels[fileIndex]).append(" ->  ")
                        
                        // 横向拼装 8 个 CPU 核心在该指标下的实时 GHz
                        for (core in 0..7) {
                            val freq = allMatrix[core][fileIndex]
                            logBuilder.append(String.format(Locale.getDefault(), "cpu%d: %.2fGHz", core, freq))
                            if (core < 7) logBuilder.append(" | ")
                        }
                        if (fileIndex < 5) logBuilder.append("\n")
                    }

                    val finalLogOutput = logBuilder.toString()

                    // 安全切回 Android 主线程，轰炸式更新 TextView 面板
                    withContext(Dispatchers.Main) {
                        onLogAppend(finalLogOutput)
                    }
                }
            } finally {
                // 无论是手动 stop 还是 Activity 销毁引发的协程取消，都安全释放脉冲监听
                withContext(Dispatchers.Main) {
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    onLogAppend("==== 🛑 帧率与全时钟参数监听已安全停止 ====")
                }
            }
        }
    }

    /**
     * 3. 手动停止监测任务
     */
    fun stop() {
        if (inspectorJob != null && inspectorJob!!.isActive) {
            inspectorJob?.cancel() 
            inspectorJob = null
        } else {
            onLogAppend("[提示] 当前没有正在运行的测试任务。")
        }
    }

    /**
     * 4. 彻底解绑服务（释放 Binder 管道）
     */
    fun unbindRootService() {
        if (rootCpuBinder != null) {
            RootService.unbind(rootConnection)
            rootCpuBinder = null
        }
    }
}
