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
import android.widget.Toast
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpeedModeController(private val context: Context) {

    // 创建一个专用于主线程 UI 的协程作用域
    private val mainScope: CoroutineScope = MainScope()

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

            // 4. 利用已经豁免的隐藏 API 强制发送 HINT_PREDICT_WORKLOAD_INCREASE
            val session = hintSession
            if (session != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                try {
                    val sessionClass = PerformanceHintManager.Session::class.java
                    val hintConstantField = sessionClass.getDeclaredField("HINT_PREDICT_WORKLOAD_INCREASE")
                    hintConstantField.isAccessible = true
                    val hintValue = hintConstantField.get(null) as Int

                    val sendHintMethod = sessionClass.getDeclaredMethod("sendHint", Int::class.java)
                    sendHintMethod.isAccessible = true
                    
                    // 硬件级突发加速调用，强制拉满 CPU/GPU 频率！
                    sendHintMethod.invoke(session, hintValue)
                    
                    // 使用 kotlinx 协程切换到主线程切弹出 Toast
                    showToastInMain("🚀 成功激活硬件级极速预判负载模式！")
                } catch (e: Exception) {
                    // 使用 kotlinx 协程切换到主线程切弹出 Toast
                    showToastInMain("⚠️ 隐藏 API 调用失败，将降级运行普通极限调频")
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

    /**
     * 使用 kotlinx 协程确保在 Main 线程安全弹出 Toast
     */
    private fun showToastInMain(message: String) {
        mainScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 当不需要这个 Controller 时（例如 Activity 销毁），释放协程作用域防止内存泄漏
     */
    fun destroy() {
        setExtremeSpeedMode(false)
        mainScope.cancel() // 销毁所有未完成的协程
    }
}
