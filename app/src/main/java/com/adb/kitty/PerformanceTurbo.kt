/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.content.Context

class PerformanceTurbo(private val context: Context) {

    private var hintSession: PerformanceHintManager.Session? = null

    /**
     * 🔥 开启狂暴性能模式（在开始 Fastboot 刷写前调用）
     */
    fun enterTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            try {
                val hintManager = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
                if (hintManager != null) {
                    // 1. 获取当前 :adb 子进程的主线程 ID (TID)
                    val myTid = Process.myTid()
                    
                    // 2. 创建一个性能提示会话，目标是让这个线程的单次循环开销极度缩短
                    // 传入我们要加速的线程 ID 数组，以及目标时间（通常设为 16ms 以内的纳秒数）
                    val targetDurationNanos = 10_000_000L // 10ms
                    hintSession = hintManager.createHintSession(intArrayOf(myTid), targetDurationNanos)
                    
                    // 3. 实时向内核宣告：我现在正在承受地狱级的超高负载，请立刻把 CPU 频率拉满！
                    // 连续汇报一个极低的实际耗时，内核的 Governor 就会判断该线程遭遇性能瓶颈，从而瞬间激进抬频
                    hintSession?.reportActualWorkDuration(1_000_000L) // 汇报只用了 1ms
                    
                    println("🚀 [:adb 进程] 成功触发 Android 12+ ADPF 内核级狂暴调度策略！")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 🍃 恢复正常省电策略（刷写结束后调用）
     */
    fun exitTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.close()
            hintSession = null
            println("🍃 [:adb 进程] 已释放内核性能锁，恢复智能省电调度。")
        }
    }
}
