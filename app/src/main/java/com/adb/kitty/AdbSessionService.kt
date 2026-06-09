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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flyfishxu.kadb.Kadb

/**
 * 🛰️ 前台守护服务（全时高活心跳版）
 * 承载 Kadb 长连接，并通过动态刷新运行时长，彻底防止通知被系统判定为“僵尸通知”而折叠收起。
 */
class AdbSessionService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "adb_kitty_channel"

    // 全局托管的常驻 Kadb 句柄
    var liveKadbInstance: Kadb? = null
        private set
        
    private val binder = AdbBinder()

    // 🕒 计时器核心成员
    private var startTimeMillis: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            // 每3秒更新一次通知栏文案
            updateNotificationWithDuration()
            mainHandler.postDelayed(this, 3000)
        }
    }

    inner class AdbBinder : Binder() {
        fun getService(): AdbSessionService = this@AdbSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 记录启动的绝对时间戳
        startTimeMillis = System.currentTimeMillis()
        
        // 1. 启动初始前台通知
        startForeground(NOTIFICATION_ID, buildNotification("正在初始化总线..."))
        
        // 2. 🌟 激活高活心跳轮询器，让通知栏文字“活”起来
        mainHandler.post(tickerRunnable)
    }

    /**
     * 📥 注入处于激活状态的 Kadb 连接
     */
    fun setKadbInstance(instance: Kadb?) {
        this.liveKadbInstance = instance
        Log.d("AdbSessionService", "🛰️ 守护进程：成功接管底层长连接句柄")
    }

    /**
     * 🔄 核心：动态组装最新的运行时长，并强制轰炸系统通知栏更新
     */
    private fun updateNotificationWithDuration() {
        val durationMillis = System.currentTimeMillis() - startTimeMillis
        val seconds = (durationMillis / 1000) % 60
        val minutes = (durationMillis / (1000 * 60)) % 60
        val hours = (durationMillis / (1000 * 60 * 60))
        
        // 格式化为：01:23:45
        val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        val statusText = if (liveKadbInstance != null) "无线设备已连接" else "等待无线设备连接"
        
        val contentText = "$statusText | ⏱️ 已持续连接: $timeString"
        
        // 强制刷新通知
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * 🏗️ 构造通知栏实体的公共方法
     */
    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Adb设备连接守护服务")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW) // 用 LOW 即可，因为高频刷新足以保活
            .setOngoing(true) // 设为常驻，防止用户手动划掉
            .setOnlyAlertOnce(true) // 🌟 极其重要：防止每次刷新都发出滴滴声或震动，只在第一次提醒
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ADB无线连接守护服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "确保退后台时，无线调试命令依然可以正常传输"
            enableLights(false)
            enableVibration(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // 释放计时器，防止内存泄漏
        mainHandler.removeCallbacks(tickerRunnable)
        // 优雅断开 TCP Socket
        runCatching { liveKadbInstance?.close() }
        super.onDestroy()
    }
}
