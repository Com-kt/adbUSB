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
import android.util.Log
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
                // 1. 绑定当前 Activity 的主线程 ID
                val threadIds = intArrayOf(Process.myTid())
                
                // 2. 设定极高标准的预期帧耗时（4毫秒，触发系统主频倾斜）
                val targetDurationNanos = TimeUnit.MILLISECONDS.toNanos(4) 
                
                // 3. 创建会话
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hintSession = hintManager.createHintSession(threadIds, targetDurationNanos)
                }
            }

            // 4. 利用已经豁免的隐藏 API 强制发送 HINT_PREDICT_WORKLOAD_INCREASE (值通常为 0 或 1)
            // 既然有 HiddenApiBypass，直接用反射突破 `@hide`
            val session = hintSession
            if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    // 反射获取隐藏的内部常量 HINT_PREDICT_WORKLOAD_INCREASE 的实际整型值
                    val sessionClass = PerformanceHintManager.Session::class.java
                    val hintConstantField = sessionClass.getDeclaredField("HINT_PREDICT_WORKLOAD_INCREASE")
                    hintConstantField.isAccessible = true
                    val hintValue = hintConstantField.get(null) as Int

                    // 反射获取隐藏的方法 sendHint(int hint)
                    val sendHintMethod = sessionClass.getDeclaredMethod("sendHint", Int::class.java)
                    sendHintMethod.isAccessible = true
                    
                    // 硬件级突发加速调用，强制拉满 CPU/GPU 频率！
                    sendHintMethod.invoke(session, hintValue)
                    Log.d("SpeedMode", "成功通过隐藏 API 激活硬件级极速预判负载模式！")
                } catch (e: Exception) {
                    Log.e("SpeedMode", "隐藏 API 调用失败（可能是ROM定制导致常量缺失），降级运行普通极限调频：", e)
                }
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
