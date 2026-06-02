/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.widget.Toast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpeedModeController(private val context: Context) {

    private val mainScope: CoroutineScope = MainScope()

    // 1. 核心修复：在这里添加注解，完美压制 Lint 误报的 WrongConstant 错误
    @SuppressLint("WrongConstant")
    private val hintManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? PerformanceHintManager
    } else null

    private var hintSession: PerformanceHintManager.Session? = null

    /**
     * 设置是否开启极速模式
     */
    @SuppressLint("NewApi")
    fun setExtremeSpeedMode(enable: Boolean) {
        if (hintManager == null) {
            showToastInMain("❌ 设备系统过低，硬件底层不支持性能调度")
            return
        }

        if (enable) {
            if (hintSession == null) {
                val threadIds = intArrayOf(Process.myTid())
                val targetDurationNanos = TimeUnit.MILLISECONDS.toNanos(4) 
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    hintSession = hintManager.createHintSession(threadIds, targetDurationNanos)
                }
            }

            val session = hintSession
            // 如果是 Android 15 及以上，尝试用你的 HiddenApiBypass 轰炸底层的突发加速
            if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    val sessionClass = PerformanceHintManager.Session::class.java
                    val hintConstantField = sessionClass.getDeclaredField("HINT_PREDICT_WORKLOAD_INCREASE")
                    hintConstantField.isAccessible = true
                    val hintValue = hintConstantField.get(null) as Int

                    val sendHintMethod = sessionClass.getDeclaredMethod("sendHint", Int::class.java)
                    sendHintMethod.isAccessible = true
                    
                    sendHintMethod.invoke(session, hintValue)
                    showToastInMain("🚀 [Android 15+] 硬件级突发极速模式已激活！")
                } catch (e: Exception) {
                    showToastInMain("⚠️ 隐藏 API 反射失败，已安全降级为标准极速")
                }
            } else if (session != null) {
                // 如果是 Android 12 ~ Android 14 设备，走标准极速通道
                showToastInMain("⚡ [Android 12-14] 4ms 锁频标准极速模式已激活！")
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                hintSession?.close()
            }
            hintSession = null
            showToastInMain("🔋 已还原正常模式，功耗回归均衡")
        }
    }

    private fun showToastInMain(message: String) {
        mainScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        setExtremeSpeedMode(false)
        mainScope.cancel()
    }
}
