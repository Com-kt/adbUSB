package com.adb.kitty.compose

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
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import android.webkit.MimeTypeMap
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.annotation.Keep
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Keep
class AdbSessionService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "adb_kitty_channel"

    private val kadbInstancePool = ConcurrentHashMap<String, Kadb>()
    
    var currentDeviceId: String? = null

    // 专属服务的轻量级协程作用域，接管 3 秒定时刷新任务
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    private val binder = AdbBinder()

    inner class AdbBinder : Binder() {
        fun getService(): AdbSessionService = this@AdbSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder
    
    var globalKadbInstance: Kadb?
        get() = currentDeviceId?.let { kadbInstancePool[it] }
        set(value) {
            val id = currentDeviceId ?: "default_device"
            if (value != null) {
                kadbInstancePool[id] = value
            } else {
                kadbInstancePool.remove(id)?.let { runCatching { it.close() } }
            }
        }
        
    fun registerUsbDevice(serialNumber: String, instance: Kadb) {
        val key = "USB_$serialNumber"
        kadbInstancePool[key] = instance
        if (currentDeviceId == null) currentDeviceId = key
    }
    
    fun registerWifiDevice(ipAndPort: String, instance: Kadb) {
        val key = "WIFI_$ipAndPort"
        kadbInstancePool[key] = instance
        if (currentDeviceId == null) currentDeviceId = key
    }
    
    fun unregisterDevice(deviceId: String) {
        kadbInstancePool.remove(deviceId)?.let { runCatching { it.close() } }
        if (currentDeviceId == deviceId) {
            currentDeviceId = kadbInstancePool.keys().asSequence().firstOrNull()
        }
    }
    
    fun getConnectedDeviceIds(): List<String> = kadbInstancePool.keys().toList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 1. 初始状态闪击启动前台服务
        val initialText = "正在初始化... | ⏱️ 已持续运行: 00:00:00"
        
        // 因为 minSdk >= 28，我们只需要专门针对 Android 14 (API 34) 以上进行安全常量绑定即可
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(initialText),
              //  ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
                val connectedCount = kadbInstancePool.size
                val statusText = if (connectedCount > 0) "已连接: ${connectedCount}台" else "等待设备接入"
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

                updateNotification("$statusText | ⏱️ 守护时长: $timeString")
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
            .setContentTitle("adbd 前台守护服务")
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
            setSound(null, null)
        }
        
        manager.createNotificationChannel(channel)
    }
    /**
     * 🌟 追加功能：由前台服务托管的智能网络下载
     */
    fun executeDownloadFromService(urlStr: String, flashFolder: File, onLog: (String) -> Unit) {
        val uri = urlStr.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            onLog("[错误] 下载失败！该指令仅支持 http:// 或 https:// 的网络地址")
            return
        }

        onLog("[系统] 正在建立网络连接...")

        // 🌟 核心：使用属于 Service 自己的 serviceScope 启动 IO 协程
        refreshJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                
                    var fileName = urlStr.substringAfterLast("/").substringBefore("?")
                    if (fileName.isEmpty() || !fileName.contains(".")) {
                        val contentType = connection.contentType
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "bin"
                        fileName = "download_${System.currentTimeMillis()}.$extension"
                    }
                
                    val targetFile = File(flashFolder, fileName)

                    connection.inputStream.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        onLog("[系统] 文件下载成功！")
                        onLog("[系统] 已保存至 flash 目录: ${targetFile.name}")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onLog("[错误] 下载失败，服务器拒绝响应，状态码: ${connection.responseCode}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog("[错误] 网络连接异常: ${e.localizedMessage}")
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        kadbInstancePool.forEach { (_, instance) -> runCatching { instance.close() } }
        kadbInstancePool.clear()
        super.onDestroy()
    }
}
