/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.*
import java.util.Locale

class AdbSessionService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "adb_kitty_channel"

    // 全局唯一的长连接物理实体，锁在服务常驻内存里，退后台绝不断线
    var globalKadbInstance: Kadb? = null

    // 专属服务的轻量级协程作用域，接管 3 秒定时刷新任务
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    private val binder = AdbBinder()

    inner class AdbBinder : Binder() {
        fun getService(): AdbSessionService = this@AdbSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 1. 初始状态闪击启动前台服务
        val initialText = "无线调试总线正在初始化... | ⏱️ 已持续运行: 00:00:00"
        
        // 因为 minSdk >= 28，我们只需要专门针对 Android 14 (API 34) 以上进行安全常量绑定即可
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(initialText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            // Android 9.0 ~ Android 13，直接启动即可
            startForeground(NOTIFICATION_ID, buildNotification(initialText))
        }

        // 2. 开启 3 秒高频静默刷新定时器
        startNotificationTicker()
    }

    /**
     * ⏳ 3 秒高频定时轮询器
     */
    private fun startNotificationTicker() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            var totalSeconds = 0
            while (isActive) {
                
                // 1. 动态诊断当前长连接状态文本 (statusText)
                val statusText = if (globalKadbInstance != null) {
                    "无线调试已连通"
                } else {
                    "无线调试总线空闲"
                }

                // 2. 将秒数换算并格式化为标准的时分秒文本 (timeString -> HH:mm:ss)
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

                updateNotification("$statusText | ⏱️ 已持续运行: $timeString")

                delay(3000)
                totalSeconds += 3
            }
        }
    }

    /**
     * 🔄 动态替换通知栏内容
     */
    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * 🏗️ 生产通知对象的工厂方法
     */
    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("adbd前台守护服务")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW) // 静默级别，不打扰用户
            .setOngoing(true) // 强力防误划清除
            .setOnlyAlertOnce(true) // 极其重要：杜绝高频刷新带来的手机异常震动或声音
            .build()
    }

    /**
     * 🌟 针对 minSdk 28 深度瘦身的渠道创建方法
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 因为 minSdk >= 28，百分之百支持 NotificationChannel，直接畅快创建
        val channel = NotificationChannel(
            CHANNEL_ID, 
            "无线调试前台服务", 
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "确保退后台或返回桌面时连接不断开，在前台服务连接无线调试"
            
            // 确保渠道本身彻底静默
            enableLights(false)
            enableVibration(false)
            sound = null
        }
        
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // 服务关闭时，彻底注销协程作用域，熔断时钟并干净释放物理 Socket
        serviceScope.cancel()
        runCatching { globalKadbInstance?.close() }
        super.onDestroy()
    }
}
