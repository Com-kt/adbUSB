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
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.Executors

class RefreshRateInspector(
    private val context: Context,
    private val onLogAppend: (String) -> Unit 
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    
    private var inspectorJob: Job? = null
    private var frameCount = 0
    private var rootCpuBinder: ICpuBinder? = null
    private var onConnectedCallback: ((Boolean) -> Unit)? = null

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var shouldThrottleFrames = false

    // 🚀 专属高优先级单线程，专门用来应付高热状态下的文本拼接，拒绝和主线程一起死锁
    private val inspectorDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootCpuBinder = ICpuBinder.Stub.asInterface(service)
            onLogAppend("[系统] 🛑 纯血 Linux 物理节点盲扫引擎准备就绪！")
            onConnectedCallback?.invoke(true)
            onConnectedCallback = null 
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            rootCpuBinder = null
            onConnectedCallback?.invoke(false)
            onConnectedCallback = null
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (shouldThrottleFrames && frameCount >= defaultDisplay.refreshRate.toInt()) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            frameCount++
            Choreographer.getInstance().postFrameCallback(this)
        }
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

    fun start() {
        if (inspectorJob != null && inspectorJob!!.isActive) return

        try {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kitsunebi:ThermalInspector").apply {
                acquire(30 * 60 * 1000L) // 直接续命 30 分钟后台不死金身
            }
        } catch (e: Exception) {}

        frameCount = 0
        shouldThrottleFrames = false
        
        try {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (e: Exception) {}

        // 🌟 核心绑定：使用全局进程生命周期，切后台也死磕到底
        inspectorJob = ProcessLifecycleOwner.get().lifecycleScope.launch(inspectorDispatcher) {
            try {
                var nextExecutionTime = System.currentTimeMillis()

                while (isActive) {
                    nextExecutionTime += 1000
                    val sleepTime = nextExecutionTime - System.currentTimeMillis()
                    if (sleepTime > 0) {
                        delay(sleepTime)
                    } else {
                        nextExecutionTime = System.currentTimeMillis() // 强制对齐时钟
                    }

                    val currentHardwareHz = defaultDisplay.refreshRate
                    val capturedFrames = frameCount
                    frameCount = 0 

                    shouldThrottleFrames = capturedFrames > (currentHardwareHz.toInt() + 2)

                    // 🛡️ 后台重新向 Choreographer 索要心跳，防止因前台不可见导致的底层队列断流
                    withContext(Dispatchers.Main) {
                        try {
                            Choreographer.getInstance().removeFrameCallback(frameCallback)
                            Choreographer.getInstance().postFrameCallback(frameCallback)
                        } catch (e: Exception) {}
                    }

                    // 1. 秒取内存缓冲区快照（耗时 0ms）
                    val snapshots = rootCpuBinder?.hardwareSnapshots ?: DoubleArray(3)
                    val rawTemps = rootCpuBinder?.rawThermalTemps ?: DoubleArray(0)
                    val rawTypes = rootCpuBinder?.rawThermalTypes ?: arrayOf()

                    val freqMatrix = Array(8) { DoubleArray(6) }
                    for (core in 0..7) {
                        freqMatrix[core] = rootCpuBinder?.getAllCpuFreqData(core) ?: DoubleArray(6)
                    }

                    // 2. 纯粹、无情的全量数据物理直出（剥离多余判定）
                    val logBuilder = StringBuilder()
                    val timeStamp = java.text.SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault()).format(java.util.Date())
                    
                    logBuilder.append(
                        String.format(
                            Locale.getDefault(),
                            "%s [监测] 屏幕: %.1fHz (实际: %dFPS) | 🔋 电池: %.1f°C | 🎮 GPU: %.3fGHz @ %.1f°C\n",
                            timeStamp, currentHardwareHz, capturedFrames, snapshots[0], snapshots[1], snapshots[2]
                        )
                    )

                    val nodeLabels = arrayOf(
                        "cpuinfo_cur_freq ", "cpuinfo_max_freq ", "cpuinfo_min_freq ",
                        "scaling_max_freq ", "scaling_min_freq ", "scaling_cur_freq "
                    )

                    for (fileIndex in 0..5) {
                        logBuilder.append("  └─ ").append(nodeLabels[fileIndex]).append(" ->  ")
                        for (core in 0..7) {
                            val content = String.format(Locale.getDefault(), "cpu%d: %.3fGHz", core, freqMatrix[core][fileIndex])
                            logBuilder.append(String.format(Locale.getDefault(), "%-14s", content))
                            if (core < 7) logBuilder.append(" | ")
                        }
                        logBuilder.append("\n")
                    }

                    logBuilder.append("  └─ 🔘 Linux 原始热链路大普查 (全量物理探头平铺展示) ->\n     ")
                    var columnCount = 0
                    for (i in rawTemps.indices) {
                        val type = rawTypes.getOrNull(i) ?: "unknown"
                        val thermalContent = String.format(Locale.getDefault(), "[%s: %.1f°C]", type, rawTemps[i])
                        logBuilder.append(String.format(Locale.getDefault(), "%-32s", thermalContent))
                        if (i < rawTemps.size - 1) {
                            logBuilder.append(" | ")
                            columnCount++
                            if (columnCount >= 5) {
                                logBuilder.append("\n     ")
                                columnCount = 0
                            }
                        }
                    }
                    logBuilder.append("\n")

                    val finalLogOutput = logBuilder.toString()

                    // 3. 唯有在最后输出时，才利用 Main 线程贴图，绝不在主线程做耗时计算
                    withContext(Dispatchers.Main) {
                        onLogAppend(finalLogOutput)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    shouldThrottleFrames = false
                    try {
                        Choreographer.getInstance().removeFrameCallback(frameCallback)
                    } catch (e: Exception) {}
                    try {
                        if (wakeLock?.isHeld == true) wakeLock?.release()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    fun stop() {
        inspectorJob?.cancel()
        inspectorJob = null
    }

    fun unbindRootService() {
        if (rootCpuBinder != null) {
            RootService.unbind(rootConnection)
            rootCpuBinder = null
        }
    }
}
