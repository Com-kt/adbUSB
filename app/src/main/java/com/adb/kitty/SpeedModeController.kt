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
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.util.concurrent.TimeUnit

class SpeedModeController(context: Context) {
    private val hintManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
    } else null

    private var hintSession: PerformanceHintManager.Session? = null

    /**
     * 设置是否开启极速模式
     */
    fun setExtremeSpeedMode(enable: Boolean) {
        if (hintManager == null) return

        if (enable) {
            if (hintSession == null) {
                // 1. 将主线程(UI线程)的ID绑定到会话中
                val threadIds = intArrayOf(Process.myTid())
                
                // 2. 设定极高标准的预期帧耗时（例如 4ms/帧，故意设得非常低，逼系统拉满频率）
                val targetDurationNanos = TimeUnit.MILLISECONDS.toNanos(4) 
                
                // 3. 创建会话，这一步执行后，Activity 就会开始享受系统频率倾斜
                hintSession = hintManager.createHintSession(threadIds, targetDurationNanos)
            }
            
            // 4. 发送“突发负载增加”的预警，瞬间将 CPU 大核和 GPU 唤醒并强制拉满主频
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) { // Android 15+
                hintSession?.sendHint(PerformanceHintManager.Session.HINT_PREDICT_WORKLOAD_INCREASE)
            }
        } else {
            // 5. 还原正常模式：直接释放会话。系统会立刻收回调度倾斜，恢复均衡模式
            hintSession?.close()
            hintSession = null
        }
    }
}
