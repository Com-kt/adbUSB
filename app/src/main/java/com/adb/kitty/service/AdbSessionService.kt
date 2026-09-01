package com.adb.kitty.service

import android.util.Log
import android.graphics.*
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.*
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.createBitmap
import android.webkit.MimeTypeMap
import android.annotation.SuppressLint
import androidx.annotation.RequiresApi

import com.flyfishxu.kadb.Kadb
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R
import com.adb.kitty.*

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.lang.reflect.Method

@Keep
class AdbSessionService : Service() {

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "com.adb.kitty.core_service_channel_v1"
    private val GROUP_ID = "com.adb.kitty.core_service_group"
    private val MAX_LOG_COUNT = 1
    private val notificationLogs = mutableListOf<String>()
    
    companion object {
        private const val ACTION_REPLY_COMMAND = "com.adb.kitty.ACTION_REPLY_COMMAND"
        private const val KEY_REPLY_INPUT = "key_reply_input"
    }
    
    private var lastCommand: String? = null
    private var cachedCircularIcon: IconCompat? = null
    var onCommandReceivedListener: ((String) -> Unit)? = null

    private val kadbInstancePool = ConcurrentHashMap<String, Kadb>()
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    private val binder = AdbBinder()

    inner class AdbBinder : Binder() {
        fun getService(): AdbSessionService = this@AdbSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder
    
    private val _currentDeviceId = MutableStateFlow<String?>(null)
    val currentDeviceIdState = _currentDeviceId.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<String>>(emptyList())
    val connectedDevicesState = _connectedDevices.asStateFlow()
    
    var currentDeviceId: String?
        get() = _currentDeviceId.value
        set(value) {
            _currentDeviceId.value = value
        }

    var globalKadbInstance: Kadb?
        get() = currentDeviceId?.let { kadbInstancePool[it] }
        set(value) {
            val id = currentDeviceId ?: "default_device"
            if (value != null) {
                kadbInstancePool[id] = value
                notifyDeviceDataChanged()
            } else {
                kadbInstancePool.remove(id)?.let { runCatching { it.close() } }
                notifyDeviceDataChanged()
            }
        }
        
    private fun notifyDeviceDataChanged() {
        _connectedDevices.value = kadbInstancePool.keys().toList()
    }
        
    fun registerUsbDevice(serialNumber: String, instance: Kadb) {
        val key = "USB_$serialNumber"
        kadbInstancePool[key] = instance
        if (currentDeviceId == null) currentDeviceId = key
        notifyDeviceDataChanged()
    }
    
    fun registerWifiDevice(ipAndPort: String, instance: Kadb) {
        val key = "WIFI_$ipAndPort"
        kadbInstancePool[key] = instance
        if (currentDeviceId == null) currentDeviceId = key
        notifyDeviceDataChanged()
    }
    
    fun unregisterDevice(deviceId: String) {
        kadbInstancePool.remove(deviceId)?.let { runCatching { it.close() } }
        if (currentDeviceId == deviceId) {
            currentDeviceId = kadbInstancePool.keys().asSequence().firstOrNull()
        }
        notifyDeviceDataChanged()
    }
    
    fun getConnectedDeviceIds(): List<String> = kadbInstancePool.keys().toList()
    
    fun logToNotification(log: String) {
        synchronized(notificationLogs) {
            if (notificationLogs.size >= MAX_LOG_COUNT) {
                notificationLogs.removeAt(0)
            }
            notificationLogs.add(log)
        }
        triggerTickerRefreshImmediate()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 1. 初始状态闪击启动前台服务
        val initialText = "00:00:00"
        
        // 因为 minSdk >= 29，我们只需要专门针对 Android 14 (API 34) 以上进行安全常量绑定即可
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(initialText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 10 ~ Android 13，直接启动即可
            startForeground(NOTIFICATION_ID, buildNotification(initialText))
        }

        startNotificationTicker()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REPLY_COMMAND) {
            handleNotificationInput(intent)
        }
        return START_STICKY
    }

    private fun handleNotificationInput(intent: Intent) {
        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val inputText = remoteInputResults?.getCharSequence(KEY_REPLY_INPUT)?.toString()

        if (!inputText.isNullOrBlank()) {
            lastCommand = inputText
            
            serviceScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    onCommandReceivedListener?.invoke(inputText)
                }
            }

            logToNotification("📡 已发送: $inputText")
        }
    }
    
    private var totalSeconds = 0
    private fun startNotificationTicker() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (isActive) {
                updateTickerNotification()
                delay(18000)
                totalSeconds += 18
            }
        }
    }
    
    private fun updateTickerNotification() {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        updateNotification(timeString)
    }

    private fun triggerTickerRefreshImmediate() {
        serviceScope.launch {
            updateTickerNotification()
        }
    }
    
    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * 🏗️ 生产通知对象的工厂方法
     */
    private fun buildNotification(contentText: String): Notification {
        val shortcutId = "adb_conversation_shortcut_id"
        val personKey = "adb_person_key_001"
    
        val remoteInput = RemoteInput.Builder(KEY_REPLY_INPUT)
            .setLabel(getString(R.string.action_service_aad))
            .build()

        val replyIntent = Intent(this, AdbSessionService::class.java).apply {
            action = ACTION_REPLY_COMMAND
        }
        val replyPendingIntent = PendingIntent.getService(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Android 12+ 必须为 MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            getString(R.string.action_service_aab),
            replyPendingIntent
        )
        .addRemoteInput(remoteInput)
        .build()
        
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val openAppAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            getString(R.string.action_service_aac),
            openPendingIntent
        ).build()
        
        val avatarIcon = getCircularIcon()
        
        val consoleUser = Person.Builder()
            .setName(getString(R.string.action_service_aaa))
            .setIcon(avatarIcon)
            .setKey(personKey)
            .build()
            
        val anonymousSender = Person.Builder()
            .setName("")
            .build()
            
        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(getString(R.string.action_service_aaa))
            .setIcon(avatarIcon)
            .setIntent(Intent(this, AdbSessionService::class.java).apply { action = "LAUNCH_FROM_NOTIF" })
            .setPerson(consoleUser)
            .setLongLived(true)
            .setIsConversation()
            .build()
        
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
            
        val messagingStyle = NotificationCompat.MessagingStyle(consoleUser)
            .setConversationTitle(getString(R.string.action_service_aae))
            
        val lastLog = synchronized(notificationLogs) {
            notificationLogs.lastOrNull()
        }
        val line1Text = lastLog ?: "📡 暂无执行指令"
        
        val connectedCount = kadbInstancePool.size
        val statusText = if (connectedCount > 0) "🟢 已连接: ${connectedCount}台设备" else "⏳ 等待设备接入"
        val line2Text = "$statusText | ⏱️ 守护时长: $contentText"
        
        messagingStyle.addMessage(line1Text, System.currentTimeMillis() - 1000, anonymousSender)
        messagingStyle.addMessage(line2Text, System.currentTimeMillis(), anonymousSender)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_small_kiss)
            .setStyle(messagingStyle)
            .setShortcutId(shortcutId)
            .setBubbleMetadata(null)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true) 
            .setOnlyAlertOnce(true) 
            .addAction(replyAction)
            .addAction(openAppAction)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        try {
            val oldChannelId = "adb_kitty_channel"
            val existingChannel = manager.getNotificationChannel(oldChannelId)
            if (existingChannel != null) {
                manager.deleteNotificationChannel(oldChannelId)
            }

            val groupName = getString(R.string.action_service_aaf)
            val channelGroup = NotificationChannelGroup(GROUP_ID, groupName)
            manager.createNotificationChannelGroup(channelGroup)

            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.action_service_aag),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.action_service_aah)
                group = GROUP_ID
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun reloadAvatar() {
        cachedCircularIcon = null
        triggerTickerRefreshImmediate()
    }

    private fun getCircularIcon(): IconCompat {
        cachedCircularIcon?.let { return it }

        val avatarFile = File(filesDir, "custom_avatar.png")
        var srcBitmap: Bitmap? = null

        if (avatarFile.exists()) {
            runCatching {
                srcBitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)
            }
        }

        if (srcBitmap == null) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                val tempBitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_service_icon, options)
            
                tempBitmap?.let {
                    val size = Math.min(it.width, it.height)
                    val dstBitmap = createBitmap(size, size)
                    val canvas = Canvas(dstBitmap)
                    val paint = Paint().apply { isAntiAlias = true }
                    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    canvas.drawBitmap(it, Rect((it.width - size) / 2, (it.height - size) / 2, (it.width + size) / 2, (it.height + size) / 2), Rect(0, 0, size, size), paint)
                    it.recycle()
                    srcBitmap = dstBitmap
                }
            }
        }

        val finalBitmap = srcBitmap ?: return IconCompat.createWithResource(this, android.R.drawable.ic_dialog_info)

        val finalIcon = IconCompat.createWithBitmap(finalBitmap)
        cachedCircularIcon = finalIcon
        return finalIcon
    }
    
    fun executeDownloadFromService(urlStr: String, flashFolder: File, onLog: (String) -> Unit) {
        val uri = urlStr.toUri()
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            onLog("[错误] 下载失败！该指令仅支持 http:// 或 https:// 的网络地址")
            return
        }

        onLog("[系统] 正在建立网络连接...")

        refreshJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val contentLength = connection.contentLengthLong

                    var fileName = urlStr.substringAfterLast("/").substringBefore("?")
                    if (fileName.isEmpty() || !fileName.contains(".")) {
                        val contentType = connection.contentType
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "bin"
                        fileName = "download_${System.currentTimeMillis()}.$extension"
                    }
            
                    val targetFile = File(flashFolder, fileName)

                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                
                    val startTime = System.currentTimeMillis()
                    var lastLogTime = startTime

                    connection.inputStream.use { inputStream ->
                        targetFile.outputStream().use { outputStream ->
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastLogTime >= 500) {
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speedMbPerSec = if (elapsedSec > 0) (totalBytesRead / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                
                                    withContext(Dispatchers.Main) {
                                        if (contentLength > 0) {
                                            val progress = (totalBytesRead.toDouble() / contentLength * 100).toInt()
                                            onLog(String.format(Locale.US, "[网络] ⚡ 下载中: %d%% | 速度: %.2f MB/s | 已用时: %.1f 秒", progress, speedMbPerSec, elapsedSec))
                                        } else {
                                            val downloadedMb = totalBytesRead / (1024.0 * 1024.0)
                                            onLog(String.format(Locale.US, "[网络] ⚡ 已下载: %.2f MB | 速度: %.2f MB/s | 已用时: %.1f 秒", downloadedMb, speedMbPerSec, elapsedSec))
                                        }
                                    }
                                    lastLogTime = now
                                }
                            }
                            outputStream.flush()
                        }
                    }

                    val totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                    val avgSpeed = if (totalTimeSec > 0) (totalBytesRead / (1024.0 * 1024.0)) / totalTimeSec else 0.0

                    withContext(Dispatchers.Main) {
                        onLog("[系统] ========================================")
                        onLog("[系统] 🎉 文件下载成功！")
                        onLog("[系统] 已保存至 flash 目录: ${targetFile.name}")
                        onLog(String.format(Locale.US, "[系统] 总用时: %.2f 秒 | 平均速度: %.2f MB/s", totalTimeSec, avgSpeed))
                        onLog("[系统] ========================================")
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
