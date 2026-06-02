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

            // 2. 【渲染窗口层】切回主线程，强制干预系统高刷
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
     * 强行干预 Android Window 渲染流水线，全列表扫描匹配最高刷新率模式
     */
    private fun applyWindowRefreshRate(enable: Boolean) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        try {
            val window = activity.window
            val layoutParams = window.attributes

            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
                    // 1. 获取当前屏幕设备支持的所有显示模式列表
                    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        activity.display
                    } else {
                        @Suppress("DEPRECATION")
                        activity.windowManager.defaultDisplay
                    }
                    
                    val supportedModes = display?.supportedModes
                    if (!supportedModes.isNullOrEmpty()) {
                        var maxRefreshRateModeId = -1
                        var highestRate = 0f

                        // 2. 暴力遍历：找出驱动里注册的帧率最高的那一个 Mode（无论是物理的还是虚拟插帧的）
                        for (mode in supportedModes) {
                            if (mode.refreshRate > highestRate) {
                                highestRate = mode.refreshRate
                                maxRefreshRateModeId = mode.modeId
                            }
                        }

                        // 3. 如果找到了最高帧率档位（比如那个伪装或插帧的 144Hz），强行锁定
                        if (maxRefreshRateModeId != -1) {
                            layoutParams.preferredDisplayModeId = maxRefreshRateModeId
                            // 弹出 Toast 让你清晰知道代码最终强锁在了多少 Hz
                            showToastInMain("🏎️ 已强制匹配硬件驱动最高档：${highestRate.toInt()}Hz")
                        } else {
                            layoutParams.preferredDisplayModeId = 0
                        }
                    } else {
                        layoutParams.preferredDisplayModeId = 0 
                    }
                }
                
                // 4. Android 12+ 强制覆盖系统出于“省电目的”做出的任何降帧判断
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val layoutParamsClass = WindowManager.LayoutParams::class.java
                        val selectionBehaviorProperty = layoutParamsClass.getDeclaredField("frameRateSelectionBehavior")
                        selectionBehaviorProperty.isAccessible = true
                        selectionBehaviorProperty.setInt(layoutParams, 1) // 1 = OVERRIDE_SEAMLESS
                    } catch (e: Exception) {
                        showToastInMain("⚠️ 帧率控制属性反射失败: ${e.localizedMessage}")
                    }
                }
                
                window.attributes = layoutParams
            } else {
                // 还原系统默认调度
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutParams.preferredDisplayModeId = -1 
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val layoutParamsClass = WindowManager.LayoutParams::class.java
                        val selectionBehaviorProperty = layoutParamsClass.getDeclaredField("frameRateSelectionBehavior")
                        selectionBehaviorProperty.isAccessible = true
                        selectionBehaviorProperty.setInt(layoutParams, 0)
                    } catch (e: Exception) { /* 忽略 */ }
                }
                window.attributes = layoutParams
            }
        } catch (e: Exception) {
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
