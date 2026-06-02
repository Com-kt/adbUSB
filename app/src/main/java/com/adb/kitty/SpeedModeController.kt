/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SpeedModeController(private val context: Context) {

    private val mainScope: CoroutineScope = MainScope()
    private var qcomPerfInstance: Any? = null

    /**
     * 设置是否开启极速模式
     * @param enable true-开启(高通锁频+屏幕高刷拉满)；false-还原(恢复均衡)
     */
    fun setExtremeSpeedMode(enable: Boolean) {
        if (enable) {
            if (qcomPerfInstance != null) return // 避免重复开启
            
            // 1. 【高通硬件层】在后台异步线程拉满 CPU/GPU 大核主频
            mainScope.launch(Dispatchers.Default) {
                try {
                    val perfClass = Class.forName("android.util.BoostFramework")
                    val constructor = perfClass.getConstructor()
                    val instance = constructor.newInstance()
                    
                    val perfLockMethod = perfClass.getMethod("perfLockAcquire", Int::class.java, IntArray::class.java)
                    
                    val durationMs = 60000 // 强制锁频持续 60 秒
                    val boostList = intArrayOf(
                        0x00404000, 0,    // 强制大核最小频率全满
                        0x00400000, 1     // 锁定大核心不准休眠
                    )
                    
                    perfLockMethod.invoke(instance, durationMs, boostList)
                    qcomPerfInstance = instance
                    
                    showToastInMain("🚀 极速模式：高通底层算力已拉满！")
                } catch (e: Exception) {
                    showToastInMain("⚠️ 芯片提频失败：非高通设备或ROM不支持")
                }
            }

            // 2. 【渲染窗口层】切回主线程，通过反射绕过编译限制，强行锁定最高显示刷新率
            mainScope.launch(Dispatchers.Main) {
                applyWindowRefreshRate(enable = true)
            }

        } else {
            // 还原正常模式：释放高通锁
            val instance = qcomPerfInstance
            if (instance != null) {
                mainScope.launch(Dispatchers.Default) {
                    try {
                        val releaseMethod = instance::class.java.getMethod("perfLockRelease")
                        releaseMethod.invoke(instance)
                        qcomPerfInstance = null
                        showToastInMain("🔋 性能锁已释放，功耗回归正常")
                    } catch (e: Exception) {
                        qcomPerfInstance = null
                    }
                }
            }

            // 还原正常模式：将屏幕刷新率交还系统托管
            mainScope.launch(Dispatchers.Main) {
                applyWindowRefreshRate(enable = false)
            }
        }
    }

    /**
     * 强行干预 Android Window 渲染流水线，突破系统的帧率控制策略
     */
    private fun applyWindowRefreshRate(enable: Boolean) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val window = activity.window
            val layoutParams = window.attributes

            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
                    // 💡 0 instructs SurfaceFlinger to pick the highest physical frame rate available (e.g. 144Hz)
                    layoutParams.preferredDisplayModeId = 0 
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
                    try {
                        // 💡 Reflection Patch: Safely extract and assign hidden frame rate parameters using your HiddenApiBypass
                        val layoutParamsClass = WindowManager.LayoutParams::class.java
                        
                        // Find the hidden constant field FRAME_RATE_SELECTION_BEHAVIOR_OVERRIDE_SEAMLESS (value typically equals 1)
                        val behaviorField = layoutParamsClass.getDeclaredField("FRAME_RATE_SELECTION_BEHAVIOR_OVERRIDE_SEAMLESS")
                        behaviorField.isAccessible = true
                        val behaviorValue = behaviorField.get(null) as Int

                        // Find the hidden property field frameRateSelectionBehavior
                        val selectionBehaviorProperty = layoutParamsClass.getDeclaredField("frameRateSelectionBehavior")
                        selectionBehaviorProperty.isAccessible = true
                        
                        // Overwrite the property on our active window layoutParams instance
                        selectionBehaviorProperty.setInt(layoutParams, behaviorValue)
                    } catch (e: Exception) {
                        // 💡 核心修改：Window 隐藏 API 反射失败时弹出 Toast 并打印具体异常
                        showToastInMain("⚠️ 帧率行为控制反射失败: ${e.localizedMessage}")
                    }
                }
                
                window.attributes = layoutParams
                showToastInMain("🏎️ 屏幕物理 144Hz/120Hz 极限刷新率已强制锁定！")
            } else {
                // 还原系统默认调度
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutParams.preferredDisplayModeId = -1 // -1 restores default automatic refresh rates
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val layoutParamsClass = WindowManager.LayoutParams::class.java
                        val selectionBehaviorProperty = layoutParamsClass.getDeclaredField("frameRateSelectionBehavior")
                        selectionBehaviorProperty.isAccessible = true
                        selectionBehaviorProperty.setInt(layoutParams, 0) // 0 resets behavior to default configuration
                    } catch (e: Exception) { /* Ignore fallback errors */ }
                }
                
                window.attributes = layoutParams
            }
        } catch (e: Exception) {
            // Anti-crash layer for heavily skinned custom ROM distributions
            showToastInMain("💥 窗口刷新率应用遭遇全局异常: ${e.localizedMessage}")
        }
    }

    private fun showToastInMain(message: String) {
        mainScope.launch(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        setExtremeSpeedMode(false)
        mainScope.cancel()
    }
}
