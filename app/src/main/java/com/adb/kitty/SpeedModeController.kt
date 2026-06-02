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
    
    // 只有 API 31 (Android 12) 以上才支持性能提示管理器
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
                // 1. 获取当前主线程 ID
                val threadIds = intArrayOf(Process.myTid())
                
                // 2. 设定极高标准的预期帧耗时（4毫秒，逼迫系统迅速提升核心频率）
                val targetDurationNanos = TimeUnit.MILLISECONDS.toNanos(4) 
                
                // 3. 创建会话
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hintSession = hintManager.createHintSession(threadIds, targetDurationNanos)
                }
            }
            
            // 4. 发送突发高负载提示 (针对 Android 15 / API 35 及以上环境)
            // 显式本地变量判空，彻底消除 Kotlin 处理高级语法糖时的 'Cannot infer type for type parameter R' 报错
            val session = hintSession
            if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                session.sendHint(PerformanceHintManager.Session.HINT_PREDICT_WORKLOAD_INCREASE)
            }
        } else {
            // 5. 还原正常模式：关闭会话
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hintSession?.close()
            }
            hintSession = null
        }
    }
}
