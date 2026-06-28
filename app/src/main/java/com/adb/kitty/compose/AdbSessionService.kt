package com.adb.kitty.compose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.p2p.*
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import android.webkit.MimeTypeMap
import android.annotation.SuppressLint

import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import androidx.annotation.Keep
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

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
    
    private val wifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private var p2pChannel: WifiP2pManager.Channel? = null
    private val P2P_PORT = 8888
    var currentP2pTargetIp: String? = null
    
    fun initWifiP2p(onLog: (String) -> Unit) {
        if (p2pChannel == null) {
            p2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
            onLog("[P2P] 原生无线直连引擎初始化成功")
        }
    }
    
    @SuppressLint("MissingPermission")
    fun resetP2pGroup(onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        
        wifiP2pManager.stopPeerDiscovery(p2pChannel, null)
        wifiP2pManager.cancelConnect(p2pChannel, null)

        wifiP2pManager.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                currentP2pTargetIp = null
                onLog("[P2P] 成功强制解散当前无线群组！本地身份已彻底重置归零。")
            }

            override fun onFailure(reason: Int) {
                // 🌟 补丁：即使移除失败（比如已经断开了），本地内存也必须强制洗白，防止状态卡死
                currentP2pTargetIp = null
                
                // 常见错误码 2 表示 BUSY（当前本来就没连上，或者正在断开中）
                if (reason == 2) {
                    onLog("[P2P] 本地当前并无活跃的群组连接，本地状态已强制归零。")
                } else {
                    onLog("[提示] 重置群组状态返回: $reason (本地内存已强制释放)")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun requestP2pPeers(onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        try {
            wifiP2pManager.requestPeers(p2pChannel) { peerList ->
                val devices = peerList.deviceList
                if (devices.isEmpty()) {
                    onLog("[P2P] 附近未发现任何可连接的无线设备，请确认对方也开启了 P2P 搜寻")
                } else {
                    onLog("[P2P] --- 附近物理设备列表 (${devices.size}台) ---")
                    devices.forEach { device ->
                        val statusStr = when(device.status) {
                            0 -> "已连接"
                            1 -> "邀请中"
                            3 -> "可连接"
                            else -> "未知(${device.status})"
                        }
                        onLog("📱 设备名: ${device.deviceName}\n   └─ MAC地址: ${device.deviceAddress} [${statusStr}]")
                    }
                }
            }
        } catch (e: SecurityException) {
            onLog("[错误] 缺少附近设备或定位权限，无法读取设备列表")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToP2pDevice(deviceAddress: String, onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        val config = WifiP2pConfig().apply { 
            this.deviceAddress = deviceAddress 
        }
        try {
            wifiP2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onLog("[P2P] 已发出连接邀请，等待对方同意...") }
                override fun onFailure(reason: Int) { onLog("[错误] 发起连接失败: $reason") }
            })
        } catch (e: SecurityException) {
            onLog("[错误] 缺少附近设备或定位权限，无法发起连接")
        }
    }

    fun checkP2pConnectionState(onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        try {
            wifiP2pManager.requestConnectionInfo(p2pChannel) { info ->
                if (info.groupFormed) {
                    val hostAddress = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                    currentP2pTargetIp = hostAddress
                    
                    if (info.isGroupOwner) {
                        onLog("[P2P] 连接成功！本地身份: 群主(GO) | 正在等待组员发送数据...")
                    } else {
                        onLog("[P2P] 连接成功！本地身份: 组员(GC) | 目标群主IP: $hostAddress")
                    }
                } else {
                    onLog("[P2P] 当前未建立任何 P2P 无线直连")
                }
            }
        } catch (e: Exception) {
            onLog("[错误] 检查连接状态失败: ${e.localizedMessage}")
        }
    }
    
    @SuppressLint("MissingPermission")
    fun discoverP2pDevices(onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        try {
            wifiP2pManager.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onLog("[P2P] 正在搜寻附近的物理设备...") }
                override fun onFailure(reason: Int) { onLog("[错误] 搜寻失败，错误码: $reason") }
            })
        } catch (e: SecurityException) {
            onLog("[错误] 缺少附近设备或定位权限，无法搜寻设备")
        }
    }

    fun p2pSendFolderOrFile(targetIp: String, sourceFile: File, onLog: (String) -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var dos: DataOutputStream? = null

            try {
                if (!sourceFile.exists()) {
                    onLog("[错误] 路径不存在: ${sourceFile.absolutePath}")
                    return@launch
                }

                onLog("[P2P] 正在分析目录结构...")
                val allItems = if (sourceFile.isDirectory) sourceFile.walkTopDown().toList() else listOf(sourceFile)
                val totalBytes = allItems.filter { it.isFile }.sumOf { it.length() }
            
                onLog("[P2P] 正在建立高速硬件通道 ($targetIp:8888) ...")
                socket = Socket()
                socket.connect(InetSocketAddress(targetIp, 8888), 10000)
                dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                onLog("[P2P] 🚀 通道并网成功！开始流式投递目录树...")

                var bytesSent = 0L
                val startTime = System.currentTimeMillis()
                var lastLogTime = startTime
                val buffer = ByteArray(64 * 1024)

                val baseParent = sourceFile.parentFile

                for (item in allItems) {
                    dos.writeBoolean(false)

                    val relativePath = baseParent?.let { item.relativeTo(it).path } ?: item.name
                    dos.writeUTF(relativePath)

                    val isDir = item.isDirectory
                    dos.writeBoolean(isDir)

                    if (!isDir) {
                        val fileSize = item.length()
                        dos.writeLong(fileSize)

                        var fis: FileInputStream? = null
                        try {
                            fis = FileInputStream(item)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                dos.write(buffer, 0, bytesRead)
                                bytesSent += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastLogTime >= 500) {
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speedMbPerSec = if (elapsedSec > 0) (bytesSent / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                    val progress = if (totalBytes > 0) (bytesSent.toDouble() / totalBytes * 100).toInt() else 100
                                    onLog(String.format(Locale.US, "[P2P] ⚡ 总进度: %d%% | 速度: %.2f MB/s | 已用时: %.1f 秒", progress, speedMbPerSec, elapsedSec))
                                    lastLogTime = now
                                }
                            }
                        } finally {
                            fis?.close()
                        }
                    }
                    dos.flush()
                }

                dos.writeBoolean(true)
                dos.flush()

                val totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                val avgSpeed = if (totalTimeSec > 0) (totalBytes / (1024.0 * 1024.0)) / totalTimeSec else 0.0

                onLog("[P2P] ========================================")
                onLog(String.format(Locale.US, "[P2P] 🎉 文件夹/文件斩断成功: %s", sourceFile.name))
                onLog(String.format(Locale.US, "[P2P] 🎉 总传输数据: %.2f MB", totalBytes / (1024.0 * 1024.0)))
                onLog(String.format(Locale.US, "[P2P] 🎉 总用时: %.2f 秒 | 平均速度: %.2f MB/s", totalTimeSec, avgSpeed))
                onLog("[P2P] ========================================")

            } catch (e: Exception) {
                onLog("[错误] 发送中断: ${e.localizedMessage}")
            } finally {
                try { dos?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun p2pStartReceiveFolderServer(outputFolder: File, onLog: (String) -> Unit) {
        serviceScope.launch(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            var dis: DataInputStream? = null

            try {
                onLog("[P2P] 接收端协议监听已挂载（端口: 8888），盲等数据树进港...")
                serverSocket = ServerSocket(8888)
                val clientSocket = serverSocket.accept()
                onLog("[P2P] 📡 侦测到数据链连接！来自: ${clientSocket.inetAddress.hostAddress}")

                dis = DataInputStream(BufferedInputStream(clientSocket.getInputStream()))
            
                val startTime = System.currentTimeMillis()
                var lastLogTime = startTime
                var totalBytesReceived = 0L
                val buffer = ByteArray(64 * 1024)

                while (!dis.readBoolean()) {
                    val relativePath = dis.readUTF()
                    val isDir = dis.readBoolean()

                    val targetFile = File(outputFolder, relativePath)

                    if (isDir) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()

                        val fileSize = dis.readLong()
                        var fileOutputStream: FileOutputStream? = null
                    
                        try {
                            fileOutputStream = FileOutputStream(targetFile)
                            var remaining = fileSize
                        
                            while (remaining > 0) {
                                val readAmt = minOf(buffer.size.toLong(), remaining).toInt()
                                val bytesRead = dis.read(buffer, 0, readAmt)
                                if (bytesRead == -1) throw java.io.EOFException("网络断开，流异常终止")
                            
                                fileOutputStream.write(buffer, 0, bytesRead)
                                remaining -= bytesRead
                                totalBytesReceived += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastLogTime >= 500) {
                                    val elapsedSec = (now - startTime) / 1000.0
                                    val speedMbPerSec = if (elapsedSec > 0) (totalBytesReceived / (1024.0 * 1024.0)) / elapsedSec else 0.0
                                    onLog(String.format(Locale.US, "[P2P] 📥 已接收: %.2f MB | 瞬时接收速度: %.2f MB/s", totalBytesReceived / (1024.0 * 1024.0), speedMbPerSec))
                                    lastLogTime = now
                                }
                            }
                        } finally {
                            fileOutputStream?.flush()
                            fileOutputStream?.close()
                        }
                    }
                }

                val totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
                val avgSpeed = if (totalTimeSec > 0) (totalBytesReceived / (1024.0 * 1024.0)) / totalTimeSec else 0.0

                onLog("[P2P] ========================================")
                onLog("[P2P] 💾 整个文件夹结构已完美落盘重建！")
                onLog(String.format(Locale.US, "[P2P] 💾 存储根目录: %s", outputFolder.absolutePath))
                onLog(String.format(Locale.US, "[P2P] 💾 总收盘用时: %.2f 秒 | 平均写入速度: %.2f MB/s", totalTimeSec, avgSpeed))
                onLog("[P2P] ========================================")

            } catch (e: Exception) {
                onLog("[错误] 接收中断: ${e.localizedMessage}")
            } finally {
                try { dis?.close() } catch (_: Exception) {}
                try { serverSocket?.close() } catch (_: Exception) {}
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
