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
import android.hardware.display.DisplayManager
import android.view.Choreographer
import android.view.Display
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
    // 外部传入你的日志打印方法
    private val onLogAppend: (String) -> Unit 
) {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    
    // 核心：保存当前正在运行的协程句柄
    private var inspectorJob: Job? = null
    private var frameCount = 0

    // 编舞者回调定义
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 启动测试
     */
    fun start() {
        // 防止用户重复点击导致启动多个协程
        if (inspectorJob != null && inspectorJob!!.isActive) {
            onLogAppend("[提示] 测试已经在运行中，请勿重复启动。")
            return
        }

        onLogAppend("==== 🔍 开始检测硬件面板物理档位 ====")
        defaultDisplay.supportedModes.forEach { mode ->
            onLogAppend(
                String.format(
                    Locale.getDefault(),
                    "物理 ID: %d -> %dx%d @ %.2f Hz",
                    mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate
                )
            )
        }
        onLogAppend("====================================\n")

        // 激活硬件编舞者脉冲
        frameCount = 0
        Choreographer.getInstance().postFrameCallback(frameCallback)

        // 启动生命周期绑定的协程，并把句柄存入 inspectorJob
        inspectorJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            try {
                var lastFpsUpdateTime = System.currentTimeMillis()
                while (isActive) {
                    delay(1000)

                    val currentHardwareHz = defaultDisplay.refreshRate
                    val capturedFrames = frameCount
                    frameCount = 0 

                    val logOutput = String.format(
                        Locale.getDefault(),
                        "[帧率监测] 屏幕物理档位: %.1f Hz | 实际画面渲染: %d FPS",
                        currentHardwareHz, capturedFrames
                    )

                    withContext(Dispatchers.Main) {
                        onLogAppend(logOutput)
                    }
                }
            } finally {
                // 无论是手动 cancel 还是 Activity 销毁，都会走进这个资源释放大本营
                withContext(Dispatchers.Main) {
                    Choreographer.getInstance().removeFrameCallback(frameCallback)
                    onLogAppend("==== 🛑 帧率测试已安全释放/停止 ====")
                }
            }
        }
    }

    /**
     * 手动停止并释放所有资源
     */
    fun stop() {
        if (inspectorJob != null && inspectorJob!!.isActive) {
            // 极其暴力的直接掐断协程，内部的 while(isActive) 会瞬间演变为 false 并触发 finally 块
            inspectorJob?.cancel() 
            inspectorJob = null
        } else {
            onLogAppend("[提示] 当前没有正在运行的测试任务。")
        }
    }
}
