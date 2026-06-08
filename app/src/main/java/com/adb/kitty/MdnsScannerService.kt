/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MdnsScannerService : Service() {

    private var nsdManager: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isScanning = false

    companion object {
        private const val TAG = "MdnsScannerService"
        private const val CHANNEL_ID = "adb_scanner_channel"
        private const val NOTIFICATION_ID = 1024
        
        const val ACTION_DEVICE_FOUND = "com.adb.kitty.ACTION_DEVICE_FOUND"
        private const val SERVICE_TYPE_PAIRING = "_adb_secure_pairing._tcp."
    }

    override fun onCreate() {
        super.onCreate()
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        
        // 🛠️ 强行拧开硬件组播锁，打通 mDNS 数据包拦截通道
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("AdbMdnsLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        
        val pendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("adbWiFi 雷达扫描中")
            .setContentText("正在盲扫局域网内无线调试节点...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()

        // 🌟 强行提权为前台服务
        startForeground(NOTIFICATION_ID, notification)

        if (!isScanning) {
            isScanning = true
            try {
                nsdManager?.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "启动发现异常", e)
            }
        }
        return START_NOT_STICKY
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(st: String?, err: Int) { isScanning = false }
        override fun onStopDiscoveryFailed(st: String?, err: Int) { isScanning = false }
        override fun onDiscoveryStarted(st: String?) {}
        override fun onDiscoveryStopped(st: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            // 🌟 核心硬核点：针对高版本系统的自适应合规解析
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                nsdManager?.registerServiceInfoCallback(serviceInfo, mainExecutor, object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}
                    override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                        dispatchFoundDevice(resolvedInfo)
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                })
            } else {
                nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo?, e: Int) {}
                    override fun onServiceResolved(ri: NsdServiceInfo) {
                        dispatchFoundDevice(ri)
                    }
                })
            }
        }

        override fun onServiceLost(si: NsdServiceInfo?) {}
    }

    private fun dispatchFoundDevice(info: NsdServiceInfo) {
        // 🌟 放弃不稳定的 hostAddress (IP)，直接提取标准的 .local 虚拟网络域名后缀
        val resolvedHostName = info.host?.hostName ?: "${info.serviceName}.local"
        val intent = Intent(ACTION_DEVICE_FOUND).apply {
            putExtra("hostName", resolvedHostName)
            putExtra("port", info.port)
            putExtra("name", info.serviceName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "雷达通知", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { nsdManager?.stopServiceDiscovery(discoveryListener) } catch (e: Exception) {}
        multicastLock?.let { if (it.isHeld) it.release() }
        isScanning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
