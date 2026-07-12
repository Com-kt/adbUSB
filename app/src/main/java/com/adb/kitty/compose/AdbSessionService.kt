package com.adb.kitty.compose

import android.util.Log
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
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*

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
    private val CHANNEL_ID = "com.adb.kitty.compose.core_service_channel_v2"
    private val GROUP_ID = "com.adb.kitty.compose.core_service_group"
    
    companion object {
        private const val ACTION_REPLY_COMMAND = "com.adb.kitty.compose.ACTION_REPLY_COMMAND"
        private const val KEY_REPLY_INPUT = "key_reply_input"
    }
    
    private var lastCommand: String? = null

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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 1. 初始状态闪击启动前台服务
        val initialText = "正在初始化 | ⏱️ 已运行: 00:00:00"
        
        // 因为 minSdk >= 29，我们只需要专门针对 Android 14 (API 34) 以上进行安全常量绑定即可
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification(initialText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            // Android 10 ~ Android 13，直接启动即可
            startForeground(NOTIFICATION_ID, buildNotification(initialText))
        }

        // 2. 开启 3 秒高频静默刷新定时器
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
            // 1. 临时保存（未来可以改造成直接执行 adb shell 指令）
            lastCommand = inputText
            Log.d("AdbSessionService", "收到通知栏快捷输入: $inputText")

            // 2. ⚡ 极其重要：收到输入后必须立即闪击刷新一下通知
            // 否则通知栏上的发送按钮会陷入无限转圈（Loading）状态
            triggerTickerRefreshImmediate()
        }
    }
    
    private var totalSeconds = 0
    private fun startNotificationTicker() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (isActive) {
                updateTickerNotification()
                delay(3000)
                totalSeconds += 3
            }
        }
    }
    
    private fun updateTickerNotification() {
        val connectedCount = kadbInstancePool.size
        val statusText = if (connectedCount > 0) "已连接: ${connectedCount}台" else "等待设备接入"
        
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeString = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)

        // 如果有输入过指令，就展示出来，没有就空着
        val commandPart = lastCommand?.let { " | 📡 指令: $it" } ?: ""
        updateNotification("$statusText$commandPart | ⏱️ 守护时长: $timeString")
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
        // 1. ✨ 创建输入框实例
        val remoteInput = RemoteInput.Builder(KEY_REPLY_INPUT)
            .setLabel("输入快捷命令 (如 adb shell...)") // 输入框 Hint 提示
            .build()

        // 2. ✨ 创建直达自身 Service 的 PendingIntent
        val replyIntent = Intent(this, AdbSessionService::class.java).apply {
            action = ACTION_REPLY_COMMAND
        }
        val replyPendingIntent = PendingIntent.getService(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE // Android 12+ 必须为 MUTABLE
        )

        // 3. ✨ 将输入框绑定到通知的 Action 按钮上
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, // 图标样式
            "运行指令",                       // 按钮文本
            replyPendingIntent
        )
        .addRemoteInput(remoteInput)
        .build()

        // 4. 构建最终的前台服务通知
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在运行前台核心服务")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
        //    .setPriority(NotificationCompat.PRIORITY_MIN)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
         //   .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true) 
            .setOnlyAlertOnce(true) 
            .addAction(replyAction) // ✨ 将带有输入框的 Action 装载进通知
            .build()
    }

    /**
     * 🌟 针对 minSdk 29 深度瘦身的渠道创建方法
     */
    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val oldChannelId = "com.adb.kitty.compose.core_service_channel"
        val existingChannel = manager.getNotificationChannel(oldChannelId)
        if (existingChannel != null) {
            manager.deleteNotificationChannel(oldChannelId)
        }
        
        val groupName = "应用核心服务"
        val channelGroup = NotificationChannelGroup(GROUP_ID, groupName)
        manager.createNotificationChannelGroup(channelGroup)

        val channel = NotificationChannel(
            CHANNEL_ID, 
            "核心前台服务", 
        //    NotificationManager.IMPORTANCE_MIN
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "此服务可以确保在退后台或返回桌面时连接不断开、网络不断开"
            group = GROUP_ID
            setShowBadge(false)
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
    private val P2P_PORT = 52020
    var currentP2pTargetIp: String? = null
    
    private val DEFAULT_P2P_PORT = 52020
    private var activeServerSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    private var proxyServerSocket: ServerSocket? = null
    @Volatile private var isProxyRunning = false
    
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
    fun connectToP2pDevice(deviceAddress: String, intentValue: Int, onLog: (String) -> Unit) {
        initWifiP2p(onLog)
        val config = WifiP2pConfig().apply { 
            this.deviceAddress = deviceAddress 
            this.groupOwnerIntent = intentValue // 0 为 GC，15 为 GO，7 为系统默认
        }
        try {
            val roleStr = when(intentValue) {
                15 -> "【强制群主 GO】"
                0 -> "【强制组员 GC】"
                else -> "【自动协商】"
            }
            onLog("[P2P] 正在以 $roleStr 模式尝试连接设备: $deviceAddress ...")
        
            wifiP2pManager.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onLog("[P2P] 已发出连接邀请，等待对方同意...") }
                override fun onFailure(reason: Int) { onLog("[错误] 发起连接失败: $reason") }
            })
        } catch (e: SecurityException) {
            onLog("[错误] 缺少附近设备或定位权限，无法发起连接")
        }
    }
    
    @SuppressLint("MissingPermission")
    fun startP2pGroup(customSsid: String? = null, customPass: String? = null, onLog: (String) -> Unit) {
        initWifiP2p(onLog)

        val manager = wifiP2pManager
        val channel = p2pChannel ?: return

        if (!customSsid.isNullOrEmpty() && !customPass.isNullOrEmpty()) {
            onLog("[P2P] 启用自定义 SSID/密码 模式...")
            
            // 避坑点 1：P2P 的 SSID 必须以 "DIRECT-" 开头
            val formattedSsid = if (customSsid.startsWith("DIRECT-")) customSsid else "DIRECT-$customSsid"
        
            // 避坑点 2：密码长度必须在 8 ~ 63 位之间
            if (customPass.length < 8 || customPass.length > 63) {
                onLog("[错误] 自定义密码长度必须在 8-63 位之间！")
                return
            }

            val config = WifiP2pConfig.Builder()
                .setNetworkName(formattedSsid)
                .setPassphrase(customPass)
                .build()

            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onLog("[P2P] 自定义群组锁定成功！")
                    onLog("[P2P] 本地网络信息 -> SSID: $formattedSsid | 密码: $customPass")
                }

                override fun onFailure(reason: Int) {
                    onLog("[错误] 自定义群组创建失败，原因代码: $reason")
                }
            })
        } else {
            onLog("[P2P] 未提供自定义参数，启用标准模式创建群组（系统随机分配凭证）...")
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onLog("[P2P] 随机群组创建成功！正在向系统索要网络凭证...")
                
                    manager.requestGroupInfo(channel) { group ->
                        if (group != null) {
                            onLog("[P2P] 成功获取随机群组凭证：")
                            onLog("      SSID -> ${group.networkName}")
                            onLog("      密码 -> ${group.passphrase}")
                        } else {
                            onLog("[错误] 获取到的群组信息为空 (Group is null)")
                        }
                    }
                }
                override fun onFailure(reason: Int) {
                    onLog("[错误] 随机群组创建失败: $reason")
                }
            })
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

    fun closeAllActiveSockets() {
        try { activeClientSocket?.close() } catch (_: Exception) {} finally { activeClientSocket = null }
        try { activeServerSocket?.close() } catch (_: Exception) {} finally { activeServerSocket = null }
    }

    @SuppressLint("MissingPermission")
    fun autoP2pSend(sourceFile: File, userPort: Int? = null, onLog: (String) -> Unit) {
        val port = userPort ?: DEFAULT_P2P_PORT
        if (port !in 1024..65535) {
            onLog("[错误] 端口超出合法范围 (1024~65535): $port")
            return
        }

        initWifiP2p(onLog)
        wifiP2pManager.requestConnectionInfo(p2pChannel) { info ->
            if (!info.groupFormed) {
                onLog("[错误] 无法发送！当前未建立任何 P2P 无线直连通道，请先执行连接。")
                return@requestConnectionInfo
            }

            if (info.isGroupOwner) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        closeAllActiveSockets()
                        
                        onLog("[P2P-群主发] 正在 $port 端口架设发射台，等待组员管道并网...")
                        
                        val sSocket = ServerSocket().apply {
                            reuseAddress = true 
                            bind(InetSocketAddress(port))
                        }
                        activeServerSocket = sSocket
                        
                        val clientSocket = sSocket.accept() 
                        activeClientSocket = clientSocket
                        
                        onLog("[P2P-群主发] 捕获到组员握手信号！开始向其推流...")
                        executeStreamTransfer(clientSocket, sourceFile, onLog)
                    } catch (e: Exception) {
                        onLog("[错误] 群主发射台崩溃: ${e.localizedMessage}")
                    } finally {
                        closeAllActiveSockets()
                    }
                }
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        closeAllActiveSockets()
                        val targetIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        onLog("[P2P-组员发] 正在主动接入群主服务器 ($targetIp:$port) ...")
                        
                        val socket = Socket()
                        activeClientSocket = socket
                        socket.connect(InetSocketAddress(targetIp, port), 10000)
                        
                        onLog("[P2P-组员发] 并网握手成功！启动大文件流式管道...")
                        executeStreamTransfer(socket, sourceFile, onLog)
                    } catch (e: Exception) {
                        onLog("[错误] 组员管道接入失败: ${e.localizedMessage}")
                    } finally {
                        closeAllActiveSockets()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun autoP2pReceive(outputFolder: File, userPort: Int? = null, onLog: (String) -> Unit) {
        val port = userPort ?: DEFAULT_P2P_PORT
        if (port !in 1024..65535) {
            onLog("[错误] 端口超出合法范围 (1024~65535): $port")
            return
        }

        initWifiP2p(onLog)
        wifiP2pManager.requestConnectionInfo(p2pChannel) { info ->
            if (!info.groupFormed) {
                onLog("[错误] 无法接收！当前未建立任何 P2P 无线直连通道，请先执行连接。")
                return@requestConnectionInfo
            }

            if (info.isGroupOwner) {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        closeAllActiveSockets()
                        onLog("[P2P-群主收] 接收端协议监听已挂载（端口: $port），盲等组员送货...")
                        
                        val sSocket = ServerSocket().apply {
                            reuseAddress = true 
                            bind(InetSocketAddress(port))
                        }
                        activeServerSocket = sSocket
                        
                        val clientSocket = sSocket.accept()
                        activeClientSocket = clientSocket
                        
                        onLog("[P2P-群主收] 📡 侦测到组员数据链进港！来自: ${clientSocket.inetAddress.hostAddress}")
                        executeStreamReceive(clientSocket, outputFolder, onLog)
                    } catch (e: Exception) {
                        onLog("[错误] 群主接收中断: ${e.localizedMessage}")
                    } finally {
                        closeAllActiveSockets()
                    }
                }
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        closeAllActiveSockets()
                        val targetIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                        onLog("[P2P-组员收] 正在主动刺入群主数据库 ($targetIp:$port) 准备吸纳下载流...")
                        
                        val socket = Socket()
                        activeClientSocket = socket
                        socket.connect(InetSocketAddress(targetIp, port), 10000)
                        
                        executeStreamReceive(socket, outputFolder, onLog)
                    } catch (e: Exception) {
                        onLog("[错误] 组员吸纳数据失败: ${e.localizedMessage}")
                    } finally {
                        closeAllActiveSockets()
                    }
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireHighPerformanceLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                // PARTIAL_WAKE_LOCK (1) | ON_AFTER_RELEASE (0x20000000)
                val combinedCpuFlags = PowerManager.PARTIAL_WAKE_LOCK or 0x20000000
                wakeLock = powerManager.newWakeLock(combinedCpuFlags, "AdbSessionService:P2pTransferLock")
            
                try {
                    val mTagField = PowerManager.WakeLock::class.java.getDeclaredField("mTag")
                    mTagField.isAccessible = true
                    mTagField.set(wakeLock, "AdbSessionService:PrivilegedCpuMatrix")
                } catch (_: Exception) {}
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AdbSessionService:FallbackLock")
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val activeHighPerfMode = 4

                val hiddenLockObj = HiddenApiBypass.invoke(
                    WifiManager::class.java,
                    wifiManager,
                    "createWifiLock",
                    activeHighPerfMode,
                    "AdbSessionService:P2pWifiLock"
                )

                if (hiddenLockObj is WifiManager.WifiLock) {
                    wifiLock = hiddenLockObj
                }
            }
        } catch (e: Exception) {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AdbSessionService:FallbackWifiLock")
            }
        }

        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }
    
    private fun releasePerformanceLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {} finally {
            wakeLock = null 
        }
    
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {} finally {
            wifiLock = null 
        }
    }

    private fun executeStreamTransfer(socket: Socket, sourceFile: File, onLog: (String) -> Unit) {
        var dos: DataOutputStream? = null
        try {
            acquireHighPerformanceLocks()

            onLog("[P2P] 正在分析目录结构...")
            val allItems = if (sourceFile.isDirectory) sourceFile.walkTopDown().toList() else listOf(sourceFile)
            val totalBytes = allItems.filter { it.isFile }.sumOf { it.length() }
    
            dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            onLog("[P2P] 🚀 硬件通道并网成功！开始流式投递目录树...")

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

                    FileInputStream(item).use { fis ->
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
            releasePerformanceLocks()
            try { dos?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun executeStreamReceive(socket: Socket, outputFolder: File, onLog: (String) -> Unit) {
        var dis: DataInputStream? = null
        try {
            acquireHighPerformanceLocks()

            dis = DataInputStream(BufferedInputStream(socket.getInputStream()))
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
                
                    FileOutputStream(targetFile).use { fileOutputStream ->
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
                                onLog(String.format(Locale.US, "[P2P] 📥 已接收: %.2f MB | 瞬时速度: %.2f MB/s", totalBytesReceived / (1024.0 * 1024.0), speedMbPerSec))
                                lastLogTime = now
                            }
                        }
                        fileOutputStream.flush()
                    }
                }
            }

            val totalTimeSec = (System.currentTimeMillis() - startTime) / 1000.0
            val avgSpeed = if (totalTimeSec > 0) (totalBytesReceived / (1024.0 * 1024.0)) / totalTimeSec else 0.0

            onLog("[P2P] ========================================")
            onLog("[P2P] 💾 整个文件夹结构已完美落盘重建！")
            onLog(String.format(Locale.US, "[P2P] 💾 存储根目录: %s", outputFolder.absolutePath))
            onLog(String.format(Locale.US, "[P2P] 💾 总收盘用时: %.2f 秒 | 平均速度: %.2f MB/s", totalTimeSec, avgSpeed))
            onLog("[P2P] ========================================")

        } catch (e: Exception) {
            onLog("[错误] 接收中断: ${e.localizedMessage}")
        } finally {
            releasePerformanceLocks()
            try { dis?.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }
    
    private fun handleSocks5Client(client: Socket) {
        try {
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val version = clientIn.read()
            if (version != 5) { client.close(); return }
            val nMethods = clientIn.read()
            val methods = ByteArray(nMethods)
            clientIn.read(methods)
            clientOut.write(byteArrayOf(0x05, 0x00)) // 回应：无需认证
            clientOut.flush()

            // 解析请求头（严格读取 4 字节：VER, CMD, RSV, ATYP）
            val reqHeader = ByteArray(4)
            if (clientIn.read(reqHeader) != 4) { client.close(); return }
        
            val cmd = reqHeader[1].toInt()
            val atyp = reqHeader[3].toInt()

            if (cmd == 1) {
                val targetHost: String
                when (atyp) {
                    1 -> { // IPv4
                        val ipBuf = ByteArray(4)
                        clientIn.read(ipBuf)
                        targetHost = InetAddress.getByAddress(ipBuf).hostAddress ?: ""
                    }
                    3 -> { // 域名
                        val len = clientIn.read()
                        val hostBuf = ByteArray(len)
                        clientIn.read(hostBuf)
                        targetHost = String(hostBuf)
                    }
                    4 -> { // IPv6
                        val ipBuf = ByteArray(16)
                        clientIn.read(ipBuf)
                        targetHost = InetAddress.getByAddress(ipBuf).hostAddress ?: ""
                    }
                    else -> { client.close(); return }
                }

                val portBuf = ByteArray(2)
                clientIn.read(portBuf)
                val targetPort = ((portBuf[0].toInt() and 0xFF) shl 8) or (portBuf[1].toInt() and 0xFF)

                // 建立远端直连并双向对轰流量
                val remoteSocket = Socket(targetHost, targetPort)
                val remoteIn = remoteSocket.getInputStream()
                val remoteOut = remoteSocket.getOutputStream()

                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                clientOut.flush()

                val t1 = thread { forwardStream(clientIn, remoteOut) }
                val t2 = thread { forwardStream(remoteIn, clientOut) }
                t1.join()
                t2.join()

            } else if (cmd == 3) {
                when (atyp) {
                    1 -> clientIn.read(ByteArray(4))
                    3 -> {
                        val len = clientIn.read()
                        clientIn.read(ByteArray(len))
                    }
                    4 -> clientIn.read(ByteArray(16))
                    else -> { client.close(); return }
                }
                clientIn.read(ByteArray(2))

                // 移交 UDP 核心并发中转引擎
                handleUdpAssociate(client, clientIn, clientOut)
            } else {
                client.close()
                return
            }
        } catch (e: Exception) {
            // 忽略连接断开导致的异常
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
    
    private fun handleUdpAssociate(clientTcp: Socket, clientIn: InputStream, clientOut: OutputStream) {
        var udpServerSocket: DatagramSocket? = null
        try {
            udpServerSocket = DatagramSocket(0, InetAddress.getByName("192.168.49.1"))
            val bndPort = udpServerSocket.localPort

            val resp = ByteArray(10)
            resp[0] = 0x05 // VER
            resp[1] = 0x00 // SUCCESS
            resp[2] = 0x00 // RSV
            resp[3] = 0x01 // ATYP = IPv4
            // 绑定本地 P2P 网关 IP: 192.168.49.1
            resp[4] = 192.toByte(); resp[5] = 168.toByte(); resp[6] = 49.toByte(); resp[7] = 1.toByte()
            resp[8] = ((bndPort ushr 8) and 0xFF).toByte()
            resp[9] = (bndPort and 0xFF).toByte()
        
            clientOut.write(resp)
            clientOut.flush()

            val finalUdpSocket = udpServerSocket
            thread(name = "Socks5-UdpPump-$bndPort") {
                runUdpPumpEngine(finalUdpSocket)
            }

            val buffer = ByteArray(1024)
            while (clientIn.read(buffer) != -1) { }
        } catch (_: Exception) {
        } finally {
        // 只要 TCP 控线断开，UDP 瞬间无条件殉葬
            udpServerSocket?.close()
            try { clientTcp.close() } catch (_: Exception) {}
        }
    }
    
    private fun runUdpPumpEngine(udpServer: DatagramSocket) {
        val receiveBuffer = ByteArray(65507)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        val outboundSockets = ConcurrentHashMap<String, DatagramSocket>()
    
        var pcUdpAddress: InetAddress? = null
        var pcUdpPort: Int = -1

        try {
            while (!udpServer.isClosed) {
                udpServer.receive(packet)
            
                if (pcUdpAddress == null) {
                    pcUdpAddress = packet.address
                    pcUdpPort = packet.port
                }

                val dataLen = packet.length
                if (dataLen < 10 || receiveBuffer[2].toInt() != 0) continue // 过滤过短包或分片包

                val atyp = receiveBuffer[3].toInt()
                var headerLen = 10
                val targetHost: String
                val targetPort: Int

                // 剥离 SOCKS5 UDP 报头，提取外网真实目标
                when (atyp) {
                    1 -> { // IPv4
                        val ipBuf = ByteArray(4)
                        System.arraycopy(receiveBuffer, 4, ipBuf, 0, 4)
                        targetHost = InetAddress.getByAddress(ipBuf).hostAddress ?: ""
                        targetPort = ((receiveBuffer[8].toInt() and 0xFF) shl 8) or (receiveBuffer[9].toInt() and 0xFF)
                        headerLen = 10
                    }
                    3 -> { // 域名
                        val domainLen = receiveBuffer[4].toInt() and 0xFF
                        val domainBuf = ByteArray(domainLen)
                        System.arraycopy(receiveBuffer, 5, domainBuf, 0, domainLen)
                        targetHost = String(domainBuf)
                        val pIdx = 5 + domainLen
                        targetPort = ((receiveBuffer[pIdx].toInt() and 0xFF) shl 8) or (receiveBuffer[pIdx + 1].toInt() and 0xFF)
                        headerLen = 5 + domainLen + 2
                    }
                    4 -> { // IPv6
                        val ipBuf = ByteArray(16)
                        System.arraycopy(receiveBuffer, 4, ipBuf, 0, 16)
                        targetHost = InetAddress.getByAddress(ipBuf).hostAddress ?: ""
                        targetPort = ((receiveBuffer[20].toInt() and 0xFF) shl 8) or (receiveBuffer[21].toInt() and 0xFF)
                        headerLen = 22
                    }
                    else -> continue
                }

                val payloadLen = dataLen - headerLen
                if (payloadLen <= 0) continue
                val payload = ByteArray(payloadLen)
                System.arraycopy(receiveBuffer, headerLen, payload, 0, payloadLen)

                val mapKey = "$targetHost:$targetPort"
                var outboundSocket = outboundSockets[mapKey]
            
                if (outboundSocket == null || outboundSocket.isClosed) {
                    outboundSocket = DatagramSocket()
                    outboundSockets[mapKey] = outboundSocket

                    val finalPcAddr = pcUdpAddress
                    val finalPcPort = pcUdpPort
                    val finalOutbound = outboundSocket
                    val cachedHeader = ByteArray(headerLen)
                    System.arraycopy(receiveBuffer, 0, cachedHeader, 0, headerLen)

                    // 异步反向监听线程：外网回包 -> 重新打包 SOCKS5 报头 -> 原路炸回给电脑
                    thread(name = "Udp-Receiver-$mapKey") {
                        try {
                            val netBuffer = ByteArray(65507)
                            val netPacket = DatagramPacket(netBuffer, netBuffer.size)
                            while (!finalOutbound.isClosed) {
                                finalOutbound.receive(netPacket)
                            
                                val sendBackBuf = ByteArray(cachedHeader.size + netPacket.length)
                                System.arraycopy(cachedHeader, 0, sendBackBuf, 0, cachedHeader.size)
                                System.arraycopy(netBuffer, 0, sendBackBuf, cachedHeader.size, netPacket.length)

                                udpServer.send(DatagramPacket(sendBackBuf, sendBackBuf.size, finalPcAddr, finalPcPort))
                            }
                        } catch (_: Exception) {}
                    }
                }

                val outPacket = DatagramPacket(payload, payload.size, InetAddress.getByName(targetHost), targetPort)
                outboundSocket.send(outPacket)
            }
        } catch (_: Exception) {
        } finally {
            outboundSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
            outboundSockets.clear()
        }
    }
    
    private fun forwardStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var len: Int
        try {
            while (input.read(buffer).also { len = it } != -1) {
                output.write(buffer, 0, len)
                output.flush()
            }
        } catch (_: Exception) {}
    }
    
    fun startSocks5Proxy(port: Int, onLog: (String) -> Unit) {
        if (isProxyRunning) {
            onLog("[提示] SOCKS5 代理已经在运行中，请勿重复开启。")
            return
        }

        isProxyRunning = true
        thread(name = "Socks5-Main") {
            try {
                acquireHighPerformanceLocks()
                onLog("[锁控] 高性能 CPU 唤醒锁与低延迟 Wi-Fi 锁已成功加锁 🔐")

                proxyServerSocket = ServerSocket(port, 50, InetAddress.getByName("192.168.49.1"))
            
                onLog("[成功] 🚀 SOCKS5 代理已在 192.168.49.1:$port 成功顶起！")
                onLog("[提示] 电脑端可配置 SOCKS5 代理上网。100% 满血支持 TCP/UDP 双轨并发转发。")

                while (isProxyRunning) {
                    val clientSocket = proxyServerSocket?.accept() ?: break
                    thread(name = "Socks5-Client-${clientSocket.port}") {
                        handleSocks5Client(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isProxyRunning) {
                    onLog("[异常] SOCKS5 代理主服务崩溃: ${e.message}")
                    isProxyRunning = false
                }
            } finally {
                releasePerformanceLocks()
                onLog("[锁控] 高性能锁已安全释放，系统重回省电模式 🔓")
            }
        }
    }
    
    fun stopSocks5Proxy(onLog: (String) -> Unit) {
        if (!isProxyRunning) {
            onLog("[提示] 代理服务本就处于关闭状态。")
            return
        }
        isProxyRunning = false
        try {
            proxyServerSocket?.close()
            proxyServerSocket = null
            onLog("[安全] SOCKS5 代理服务已完全关闭。")
        } catch (e: Exception) {
            onLog("[错误] 关闭代理服务异常: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        kadbInstancePool.forEach { (_, instance) -> runCatching { instance.close() } }
        kadbInstancePool.clear()
        super.onDestroy()
    }
}
