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
    private val onLogAppend: (String) -> Unit 
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    
    private var inspectorJob: Job? = null
    private var frameCount = 0
    private var rootCpuBinder: ICpuBinder? = null
    private var onConnectedCallback: ((Boolean) -> Unit)? = null

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootCpuBinder = ICpuBinder.Stub.asInterface(service)
            onLogAppend("[系统] 🛑 免注册通用高通监控 Daemon 进程就绪，全系物理矩阵解锁！")
            onConnectedCallback?.invoke(true)
            onConnectedCallback = null 
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootCpuBinder = null
            onConnectedCallback?.invoke(false)
            onConnectedCallback = null
            onLogAppend("[警告] ⚠️ 远程特权服务意外断开连接！")
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun isRootServiceConnected(): Boolean {
        return rootCpuBinder != null
    }

    fun bindRootService(onResult: (Boolean) -> Unit) {
        if (rootCpuBinder != null) {
            onResult(true)
            return
        }
        this.onConnectedCallback = onResult
        val intent = Intent(context, GhzRootService::class.java)
        RootService.bind(intent, rootConnection)
    }

    /**
     * 🔥 核心启动：每秒异步打包同步，收割 8行7列 超维硬件热力学大阵
     */
    fun start() {
        if (inspectorJob != null && inspectorJob!!.isActive) {
            onLogAppend("[提示] 测试已经在运行中，请勿重复启动。")
            return
        }

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

        frameCount = 0
        Choreographer.getInstance().postFrameCallback(frameCallback)

        // 强力常驻后台异步工作流，绝不拖累高刷前台渲染
        inspectorJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    delay(1000) // 每秒精准全谱采点一次

                    val currentHardwareHz = defaultDisplay.refreshRate
                    val capturedFrames = frameCount
                    frameCount = 0 

                    // 1. 跨进程收割大件多维硬件快照（完美合并电池、GPU频率、GPU温度）
                    val sysData = rootCpuBinder?.getSystemTemperatures() ?: DoubleArray(4)
                    val batTemp = sysData[1]
                    val gpuTemp = sysData[2]
                    val gpuFreq = sysData[3]

                    // 2. 跨进程提取全自适应拓扑出来的 8核心物理独立核心温度
                    val coreTemps = rootCpuBinder?.allCpuCoreTemps ?: DoubleArray(8)

                    // 3. 提取 8核心×6维度的频率矩阵数据
                    val allMatrix = Array(8) { DoubleArray(6) }
                    for (core in 0..7) {
                        allMatrix[core] = rootCpuBinder?.getAllCpuFreqData(core) ?: DoubleArray(6)
                    }

                    val nodeLabels = arrayOf(
                        "cpuinfo_cur_freq ", "cpuinfo_max_freq ", "cpuinfo_min_freq ",
                        "scaling_max_freq ", "scaling_min_freq ", "scaling_cur_freq "
                    )

                    val logBuilder = StringBuilder()
                    
                    // 🚀【头部行全大一统合并】屏幕高刷、实际FPS、电池温度、GPU实时主频、GPU物理核心温度
                    logBuilder.append(
                        String.format(
                            Locale.getDefault(),
                            "[监测] 屏幕: %.1fHz (实际: %dFPS) | 🔋 电池: %.1f°C | 🎮 GPU: %.3fGHz @ %.1f°C\n",
                            currentHardwareHz, capturedFrames, batTemp, gpuFreq, gpuTemp
                        )
                    )

                    // 4. 渲染前 6 行：主频控制方阵（使用 %-14s 强行卡位防止抖动）
                    for (fileIndex in 0..5) {
                        logBuilder.append("  └─ ").append(nodeLabels[fileIndex]).append(" ->  ")
                        for (core in 0..7) {
                            val freq = allMatrix[core][fileIndex]
                            val content = String.format(Locale.getDefault(), "cpu%d: %.3fGHz", core, freq)
                            logBuilder.append(String.format(Locale.getDefault(), "%-14s", content))
                            if (core < 7) logBuilder.append(" | ")
                        }
                        logBuilder.append("\n")
                    }

                    // 🚀【并列并线第 7 行指标】8大独立物理核心的精确热量弹幕
                    logBuilder.append("  └─ core_temperature ->  ")
                    for (core in 0..7) {
                        val tempContent = String.format(Locale.getDefault(), "cpu%d: %.1f°C", core, coreTemps[core])
                        logBuilder.append(String.format(Locale.getDefault(), "%-14s", tempContent))
                        if (core < 7) logBuilder.append(" | ")
                    }

                    val finalLogOutput = logBuilder.toString()

                    // 安全切回 Android 主线程，把完全对齐的超大方阵直接拍给 TextView
                    withContext(Dispatchers.Main) {
                        onLogAppend(finalLogOutput)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    onLogAppend("==== 🛑 帧率与全时钟热力学矩阵监听已安全停止 ====")
                }
            }
        }
    }

    fun stop() {
        if (inspectorJob != null && inspectorJob!!.isActive) {
            inspectorJob?.cancel() 
            inspectorJob = null
        }
    }

    fun unbindRootService() {
        if (rootCpuBinder != null) {
            RootService.unbind(rootConnection)
            rootCpuBinder = null
        }
    }
}
