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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeedModeController(private val context: Context) {

    private val mainScope: CoroutineScope = MainScope()
    private var qcomPerfInstance: Any? = null

    /**
     * 设置是否开启极速模式（高通平台专属锁频）
     */
    fun setExtremeSpeedMode(enable: Boolean) {
        if (enable) {
            if (qcomPerfInstance != null) return // 避免重复开启
            
            // 1. 在后台异步线程进行高通隐藏 API 的反射处理
            mainScope.launch(Dispatchers.Default) {
                try {
                    // 利用 HiddenApiBypass 绕过限制，加载高通核心性能类
                    val perfClass = Class.forName("android.util.BoostFramework")
                    val constructor = perfClass.getConstructor()
                    val instance = constructor.newInstance()
                    
                    // 获取核心锁频函数：perfLockAcquire(int duration, int[] list)
                    val perfLockMethod = perfClass.getMethod("perfLockAcquire", Int::class.java, IntArray::class.java)
                    
                    // 配置提频魔数参数（强制大核高频运转 60 秒）
                    val durationMs = 60000 
                    val boostList = intArrayOf(
                        0x00404000, 0,    // 强制将 CPU 大核的最小频率拉满
                        0x00400000, 1     // 锁定大核心防止由于省电进入休眠
                    )
                    
                    // 执行底层硬件锁频
                    perfLockMethod.invoke(instance, durationMs, boostList)
                    qcomPerfInstance = instance
                    
                    // 2. 反射成功后，切回主线程弹出 Toast 提示
                    showToastInMain("🚀 高通底层硬件级极速模式已激活！")
                    
                } catch (e: Exception) {
                    // 失败后切换回主线程提示（可能由于非高通芯片或ROM彻底移除该私有类）
                    showToastInMain("❌ 极速激活失败：非高通骁龙设备或该ROM不支持")
                }
            }
        } else {
            // 还原正常模式
            val instance = qcomPerfInstance
            if (instance != null) {
                mainScope.launch(Dispatchers.Default) {
                    try {
                        val releaseMethod = instance::class.java.getMethod("perfLockRelease")
                        releaseMethod.invoke(instance)
                        qcomPerfInstance = null
                        
                        showToastInMain("🔋 高通性能锁已释放，功耗回归正常")
                    } catch (e: Exception) {
                        qcomPerfInstance = null
                        showToastInMain("⚠️ 释放异常，性能锁已强制清空")
                    }
                }
            }
        }
    }

    /**
     * 利用 kotlinx 协程强制在主线程安全弹出 Toast 提示
     */
    private fun showToastInMain(message: String) {
        mainScope.launch(Dispatchers.Main) {
            // 💡 优化点：使用 applicationContext 避免某些特定 Activity 场景下 Toast 被系统拦截
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        setExtremeSpeedMode(false)
        mainScope.cancel()
    }
}
