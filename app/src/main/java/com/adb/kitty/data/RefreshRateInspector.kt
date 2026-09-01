package com.adb.kitty.data

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
import androidx.annotation.Keep
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.*

@Keep
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

    @Volatile
    private var shouldThrottleFrames = false

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootCpuBinder = ICpuBinder.Stub.asInterface(service)
            onLogAppend("[系统] 🟢 纯血 Linux 物理节点盲扫引擎准备就绪！")
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
        if (inspectorJob != null && inspectorJob!!.isActive) {
            onLogAppend("[提示] 测试已经在运行中，请勿重复启动。")
            return
        }

        // 🌟【硬核归位】硬件面板物理高刷档位大普查，厂商封印的物理参数都在这
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
        shouldThrottleFrames = false
        Choreographer.getInstance().postFrameCallback(frameCallback)

        inspectorJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                while (isActive) {
                    delay(1000)

                    val currentHardwareHz = defaultDisplay.refreshRate
                    val capturedFrames = frameCount
                    frameCount = 0 

                    shouldThrottleFrames = capturedFrames > (currentHardwareHz.toInt() + 2)

                    // 1. 收割大件快照
                    val snapshots = rootCpuBinder?.hardwareSnapshots ?: DoubleArray(3)
                    val batTemp = snapshots[0]
                    val gpuFreq = snapshots[1]
                    val gpuTemp = snapshots[2]

                    // 2. 暴力收割全系统所有物理热敏原始阵列
                    val rawTemps = rootCpuBinder?.rawThermalTemps ?: DoubleArray(0)
                    val rawTypes = rootCpuBinder?.rawThermalTypes ?: arrayOf()

                    // 3. 稳稳抓取 cpu0..cpu7 真实主频方阵
                    val freqMatrix = Array(8) { DoubleArray(6) }
                    for (core in 0..7) {
                        freqMatrix[core] = rootCpuBinder?.getAllCpuFreqData(core) ?: DoubleArray(6)
                    }

                    val nodeLabels = arrayOf(
                        "cpuinfo_cur_freq ", "cpuinfo_max_freq ", "cpuinfo_min_freq ",
                        "scaling_max_freq ", "scaling_min_freq ", "scaling_cur_freq "
                    )

                    val logBuilder = StringBuilder()
                    
                    // 🚀 顶部全维大件面板
                    logBuilder.append(
                        String.format(
                            Locale.getDefault(),
                            "[监测] 屏幕: %.1fHz (实际: %dFPS) | 🔋 电池: %.1f°C | 🎮 GPU: %.3fGHz @ %.1f°C\n",
                            currentHardwareHz, capturedFrames, batTemp, gpuFreq, gpuTemp
                        )
                    )

                    // 🚀 前 6 行：cpu0 到 cpu7 主频矩阵（14 字符宽度像素级对齐）
                    for (fileIndex in 0..5) {
                        logBuilder.append("  └─ ").append(nodeLabels[fileIndex]).append(" ->  ")
                        for (core in 0..7) {
                            val freq = freqMatrix[core][fileIndex]
                            val content = String.format(Locale.getDefault(), "cpu%d: %.3fGHz", core, freq)
                            logBuilder.append(String.format(Locale.getDefault(), "%-14s", content))
                            if (core < 7) logBuilder.append(" | ")
                        }
                        logBuilder.append("\n")
                    }

                    // 🚀 第 7 行起：全量物理热敏探头阵列（满 5 个自动换行）
                    logBuilder.append("  └─ 🔘 Linux 原始热链路大普查 (全量物理探头平铺展示) ->\n     ")
                    
                    var columnCount = 0
                    for (i in rawTemps.indices) {
                        val type = rawTypes.getOrNull(i) ?: "unknown"
                        val temp = rawTemps[i]
                        
                        val thermalContent = String.format(Locale.getDefault(), "[%s: %.1f°C]", type, temp)
                        logBuilder.append(String.format(Locale.getDefault(), "%-32s", thermalContent))
                        
                        if (i < rawTemps.size - 1) {
                            logBuilder.append(" | ")
                            columnCount++
                            
                            // 当列计数器累加到 5 时，强行塞入换行符并重置
                            if (columnCount >= 5) {
                                logBuilder.append("\n     ")
                                columnCount = 0
                            }
                        }
                    }
                    logBuilder.append("\n")

                    val finalLogOutput = logBuilder.toString()

                    withContext(Dispatchers.Main) {
                        onLogAppend(finalLogOutput)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    shouldThrottleFrames = false
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
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
