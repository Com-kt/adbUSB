package com.adb.kitty.compose

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.app.PendingIntent

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.app.ActivityCompat
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.system.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import okio.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.*
import org.json.*

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

@Keep
class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_WIFI_PERMISSION_CODE = 1001
        private const val PREFS_NAME = "adb_kitty_prefs"
        private const val KEY_DEVICE_LIST = "connected_devices"
    }
    private val viewModel: MainActivityViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private lateinit var keyManager: AdbKeyManager
    internal lateinit var inspector: RefreshRateInspector
    private val ACTION_USB_PERMISSION = "com.adb.kitty.compose.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var readerJob: Job? = null
    private var currentShellJob: Job? = null
    private var usbForwarder: UsbPortForwarder? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false
    private var isWifiEnabled: Boolean = false

    private val responseChannel = Channel<String>(Channel.CONFLATED)
    
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }
    
    val turbo by lazy { PerformanceTurbo(this) }
    
    var showDeviceListBottomSheet = mutableStateOf(false)
    var matchedDevicesList = mutableStateListOf<AdbDevice>()

    private var adbService: AdbSessionService? = null
    private var isServiceBound = false
    private var kadbInstance: Kadb?
        get() = if (isServiceBound) adbService?.globalKadbInstance else null
        set(value) { if (isServiceBound) adbService?.globalKadbInstance = value }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AdbSessionService.AdbBinder
            adbService = binder.getService()
            isServiceBound = true
            appendLog("[系统] 前台物理守护进程并网成功。")
            initWifiState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            adbService = null
        }
    }

    // 2. 桥接方法：将 Activity 内的所有日志无缝灌入 ViewModel
    fun appendLog(msg: String) {
        viewModel.appendLog(msg)
    }
    
    private val requestNetworkPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isWifiScanGranted = permissions[getWifiScanPermission()] ?: true
        val isLocalNetworkGranted = if (Build.VERSION.SDK_INT >= 37) {
            permissions["android.permission.ACCESS_LOCAL_NETWORK"] ?: false
        } else {
            true
        }
        if (isWifiScanGranted && isLocalNetworkGranted) {
            appendLog("[系统] Wi-Fi 所需权限已授予，正在激活无线链路...")
            initWifiState()
        } else {
            appendLog("[系统] 🔴 权限被拒绝，无法自动扫描 Wi-Fi SSID")
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        appendLog("[系统] USB 调试设备权限获取成功")
                        connectToInterface(device)
                    }
                } else {
                    appendLog("[系统] 用户拒绝了 USB 权限申请")
                }
            }
        }
    }

    private val usbStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    isUsbAttached = true
                    findHostDevice() // 自动盘卡物理硬件
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    isUsbAttached = false
                    isAdbAuthorized = false
                    isFastbootMode = false
                    readerJob?.cancel()
                    usbConn?.close()
                    appendLog("[系统] USB 设备已断开")
                }
            }
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                val oldState = isWifiEnabled
                when (wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> {
                        isWifiEnabled = true
                        appendLog("[系统] ⏳ WLAN 已开启")
                        if (!oldState) handleWifiConnectionFlow()
                    }
                    WifiManager.WIFI_STATE_DISABLED -> {
                        isWifiEnabled = false
                        appendLog("[系统] ⏳ WLAN 已关闭")
                        handleWifiConnectionFlow()
                    }
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            )
        )
        ensureFlashDirExists()
        setContent {
            CenterAlignedTopAppBarExample(
                viewModel = viewModel,
                activity = this@MainActivity,
                onExecuteCommand = { cmd -> 
                    dispatchCommandRoute(cmd)
                }
            )
            
            if (showDeviceListBottomSheet.value) {
                DeviceSelectionBottomSheet(
                    wifiName = getCurrentWifiSsid(),
                    devices = matchedDevicesList,
                    onDeviceSelected = { selectedDevice ->
                        appendLog("[系统] 用户从底栏选择了设备: ${selectedDevice.ip}:${selectedDevice.port}")
                        lifecycleScope.launch(Dispatchers.IO) {
                            handleLocalAdbConnect("adb connect ${selectedDevice.ip}:${selectedDevice.port}")
                        }
                        showDeviceListBottomSheet.value = false
                    },
                    onDismiss = { showDeviceListBottomSheet.value = false }
                )
            }
        }
        
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        keyManager = AdbKeyManager(this)

        if (checkAndRequestNotificationPermission()) {
            val intent = Intent(this, AdbSessionService::class.java)
            startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), exportFlag)
        registerReceiver(usbStateReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }, exportFlag)

        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(wifiReceiver, filter)
        }

        inspector = RefreshRateInspector(this, this) { logText -> appendLog(logText) }
        
        // 首次冷启动探测有线
        findHostDevice()
    }
    
    private fun dispatchCommandRoute(cmdInput: String) {
        val cmd = cmdInput.trim()
        if (cmd.isEmpty()) return
        
        when (cmd) {
            "userkitty-log-export" -> {
                exportLogToFlashFolder()
                return
            }
            "ip-test" -> {
                appendLog("[系统] 扩展指令(由app提供) >> $cmd")
                startIpNetworkTest()
                return
            }
            "usb-host" -> {
                appendLog("[系统] 扩展指令(由app提供) >> $cmd")
                findHostDevice()
                return
            }
            "root-rate" -> {
                appendLog("[系统] 扩展指令 >> $cmd")
                appendLog("[系统] 正在尝试启动 Root 特权帧率服务, 该指令由 app 提供")
                inspector.bindRootService { isConnected ->
                    if (isConnected) {
                        inspector.start()
                    } else {
                        appendLog("[错误] Root 特权服务绑定失败！请确认设备已获得 Magisk/Apatch/KernelSU 完整授权！")
                    }
                }
                return
            }
        }

        if (isFastbootMode) {
            if (cmd == "usb-selinux") {
                appendLog("[发送] FB >> $cmd")
                appendLog("[系统] 正在尝试设置 SeLinux 为宽容模式, 该指令由 app 提供")
                FbSeLinuxCmd()
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) { appendLog("[发送] FB >> $cmd") }
                    viewModel.runCommand(cmd) // 完美交付给 ViewModel 统一调度
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                }
            }
        } else {
            if (!isAdbAuthorized && !cmd.startsWith("adb pair") && !cmd.startsWith("adb connect")) {
                Toast.makeText(this, "设备未就绪或未授权", Toast.LENGTH_SHORT).show()
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                when {
                    cmd.startsWith("adb pair") -> handleLocalAdbPair(cmd)
                    cmd.startsWith("adb connect") -> handleLocalAdbConnect(cmd)
                    cmd.startsWith("adb push") -> handleLocalAdbPush(cmd)
                    cmd.startsWith("adb pull") -> handleLocalAdbPull(cmd)
                    cmd.startsWith("adb install") -> handleLocalAdbInstall(cmd)
                    cmd.startsWith("adb uninstall") -> handleLocalAdbUninstall(cmd)
                    else -> sendAdbShell(cmd)
                }
            }
        }
    }
    
    fun FbSeLinuxCmd() {
        if (!isFastbootMode) {
             Toast.makeText(this, "当前不是 Fastboot 模式", Toast.LENGTH_SHORT).show()
           return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val cmds = listOf(
                  "oem set-gpu-preemption 0 androidboot.selinux=permissive",
                  "continue"
            )
            for (cmd in cmds) {
               // 1. 先把要发的命令打印出来
               withContext(Dispatchers.Main) { 
                  appendLog("[发送] FB >> $cmd") 
               }
               // 2. 发送原始指令 (调用临时执行方法)
               viewModel.runCommand(cmd)
               // 3. 等待设备响应（如果有）
                delay(500) 
            }
        }
    }
    
    fun findHostDevice() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            appendLog("未发现 USB 设备")
            return
        }

        for (device in devices.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                appendLog("设备: ${device.productName ?: "unknown"}")
                appendLog("制造商: ${device.manufacturerName ?: "unknown"}")
                appendLog("版本号: ${device.version}")
                // 在遍历 interface 的循环内
                appendLog("接口名称: ${intf.name ?: "unknown"}")
                // USB设备信息
                appendLog("VID: ${device.vendorId} | PID: ${device.productId}")
                appendLog("检查接口 $i: Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}")
                
                // 遍历端点 (Endpoint)
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    
                    // 解析端点方向：最高位为 1 代表 IN (设备到手机)，0 代表 OUT (手机到设备)
                    val isInput = (ep.address and 0x80) != 0
                    val direction = if (isInput) "IN (设备->手机)" else "OUT (手机->设备)"
                    
                    // 解析端点编号：低 4 位代表编号
                    val epNumber = ep.address and 0x0F
                    
                    appendLog("端点 $j: 地址=${ep.address} (方向: $direction, 编号: $epNumber), 最大包大小=${ep.maxPacketSize}")
                }
                
                appendLog("--- 通过USB连接输出 ---")
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66) {
                    isFastbootMode = (intf.interfaceProtocol == 3)
                    isUsbAttached = true
                    
                    val modeName = if (isFastbootMode) "Fastboot" else "ADB"
                    // 匹配要求：同行显示 VID/PID 十进制
                    appendLog("--- 检测到 $modeName 兼容设备 ---")

                    if (!usbManager.hasPermission(device)) {
                        // --- 修复 Android 14 崩溃的关键点 ---
                        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                           PendingIntent.FLAG_MUTABLE 
                        } else {
                            0
                        }
                        // 必须明确 setPackage，将隐式 Intent 变为显式 Intent
                        val intent = Intent(ACTION_USB_PERMISSION).apply {
                            setPackage(packageName) 
                        }
                        val pi = PendingIntent.getBroadcast(this, 0, intent, flags)
                        usbManager.requestPermission(device, pi)
                        
                    } else {
                        appendLog("[Serial] 硬件序列号: ${device.serialNumber ?: "unknown"}")
                        connectToInterface(device)
                    }
                    return
                }
            }
        }
        appendLog("发现设备但无 ADB/Fastboot 接口")
    }
 
    private fun connectToInterface(device: UsbDevice) {
        val protocolTarget = if (isFastbootMode) 3 else 1
        val intf = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == 255 && it.interfaceSubclass == 66 && it.interfaceProtocol == protocolTarget } ?: return

        val conn = usbManager.openDevice(device) ?: return
        conn.claimInterface(intf, true)
        
        for (j in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        usbConn = conn
        
        if (isFastbootMode) {
            setupFastboot()
            appendLog("[系统] Fastboot 物理信道就绪")
        } else {
            isAdbAuthorized = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    usbForwarder?.stop()
                    usbForwarder = UsbPortForwarder(conn, epIn!!, epOut!!)
                    val localVirtualPort = usbForwarder!!.startBridge()

                    withContext(Dispatchers.Main) {
                        appendLog("[Auth] 正在向环回端口 [$localVirtualPort] 发起握手与撞门机制...")
                    }

                    kadbInstance = Kadb.create(host = "127.0.0.1", port = localVirtualPort)
                    val isConnected = kadbInstance!!.connectionCheck()

                    withContext(Dispatchers.Main) {
                        if (isConnected) {
                            isAdbAuthorized = true
                            appendLog(">>> ADB 有线授权成功，物理总线全面并网！ <<<")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        appendLog("[Error] KADB 物理有线握手崩溃: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun setupFastboot() {
        viewModel.initFastboot(
            usbConn = usbConn!!, 
            epOut = epOut!!, 
            epIn = epIn!!,
            responseChannel = responseChannel,
            flashFolder = flashFolder
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.fastbootManager?.logFlow?.collect { msg ->
                    appendLog(msg) 
                }
            }
        }
    }
    
    private fun executeAutoWifiConnect() {
        val currentWifi = getCurrentWifiSsid()
        val allHistory = getAllSavedDevices()
        val matchedDevices = allHistory.filter { it.wifiSsid == currentWifi }
        when {
            matchedDevices.isEmpty() -> {
                appendLog("[系统] 💡 当前 WiFi [$currentWifi] 无历史记录，等待手动输入")
            }
            matchedDevices.size == 1 -> {
                val target = matchedDevices.first()
                appendLog("[系统] 📡 侦测到 WiFi [$currentWifi] 唯一历史设备，正在无感回连...")
                lifecycleScope.launch(Dispatchers.IO) {
                    handleLocalAdbConnect("adb connect ${target.ip}:${target.port}")
                }
            }
            else -> {
                matchedDevicesList.clear()
                matchedDevicesList.addAll(matchedDevices)
                showDeviceListBottomSheet.value = true
            }
        }
    }
    
    private fun exportLogToFlashFolder() {
        val logContent = viewModel.logs.joinToString("\n")
        if (logContent.isEmpty()) {
            appendLog("[提示] 当前控制台日志空空如也")
            return
        }

        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val timeStamp = LocalDateTime.now().format(formatter)
        val fileName = "Log_$timeStamp.txt"
        val targetFile = File(flashFolder, fileName)

        try {
            FileWriter(targetFile).use { writer -> writer.write(logContent) }
            appendLog("[系统] 🎉 日志已成功安全写入文件：${targetFile.absolutePath}")
        } catch (e: Exception) {
            appendLog("[错误] ❌ 写入文件时发生异常: ${e.message}")
        }
    }
    
    private fun handleLocalAdbInstall(command: String) {
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb install [本地路径/文件名]")
            return
        }

        val pathInput = parts[2]
        val file = if (pathInput.startsWith("/")) File(pathInput) else File(flashFolder, pathInput)

        if (!file.exists()) {
            appendLog("[错误] 找不到文件或路径: ${file.absolutePath}")
            return
        }

        // 1. 判断是单 APK 还是 多 APK (目录 或 apks/xapk 后缀)
        val ext = file.extension.lowercase()
        val isMultiple = file.isDirectory || ext == "apks" || ext == "xapk"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
            
                if (isMultiple) {
                    // 2. 多文件安装逻辑
                    appendLog("[Install] 检测到多文件模式 (Split APKs)...")
                
                    // 如果是目录，列出目录下所有的 apk
                    val apkList = if (file.isDirectory) {
                        file.listFiles { _, name -> name.lowercase().endsWith(".apk") }?.toList() ?: emptyList()
                    } else {
                        // 注意：如果用户直接给了一个 .apks/.xapk 文件，Kadb 的 installMultiple 需要的是 List<File>
                        // 如果它是压缩包，你可能需要先解压。这里假设它是一个包含多个 APK 的逻辑集合或你可以直接处理的列表
                        listOf(file) 
                    }

                    if (apkList.isEmpty()) {
                        withContext(Dispatchers.Main) { appendLog("[错误] 目录下未找到 .apk 文件") }
                        return@launch
                    }

                    kadb.installMultiple(apkList)
                    withContext(Dispatchers.Main) { appendLog("[成功] 多组件安装完成") }
                } else {
                    // 3. 单文件安装逻辑
                    appendLog("[Install] 正在安装: ${file.name}")
                    kadb.install(file)
                    withContext(Dispatchers.Main) { appendLog("[成功] 安装完成: ${file.name}") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[安装失败] ${e.message}") 
                }
            }
        }
    }
    
    private fun handleLocalAdbUninstall(command: String) {
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb uninstall [包名]")
            return
        }

        val packageName = parts[2]
        appendLog("[Uninstall] 正在尝试卸载: $packageName")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
            
                // 🌟 直接调用 Kadb 内置的 uninstall
                kadb.uninstall(packageName)
            
                withContext(Dispatchers.Main) { 
                    appendLog("[成功] 已卸载: $packageName") 
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[卸载失败] 无法完成: ${e.message}") 
                }
            }
        }
    }
    
    private suspend fun sendAdbShell(command: String) {
        withContext(Dispatchers.Main) {
            isAdbAuthorized = true
            appendLog("ADB >> $command")
        }
        // 1. 如果有旧任务正在运行，先停止它
        if (currentShellJob?.isActive == true) {
            currentShellJob?.cancel()
            appendLog("[系统] 停止了上一个任务...")
        }

        // 2. 启动新任务并保存 Job
        currentShellJob = lifecycleScope.launch(Dispatchers.IO) {
            val cleanCmd = command.removePrefix("adb shell ").trim()
            val isLongRunning = cleanCmd.contains("logcat") || cleanCmd.contains("top") || cleanCmd.contains("dumpsys") || cleanCmd.contains("cat /proc/")
        
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("通道连接未就绪")
            
                if (isLongRunning) {
                    handleStreamingCommand(kadb, cleanCmd)
                } else {
                    handleBufferedCommand(kadb, cleanCmd, 30_000L)
                }
            } catch (e: CancellationException) {
                // 协程被取消时会走到这里
                withContext(Dispatchers.Main) { appendLog("[系统] 任务已手动停止") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    appendLog("[Shell 异常] ${e.message}")
                    if (e is java.io.IOException || e.message?.contains("closed") == true) {
                        kadbInstance = null
                        isAdbAuthorized = false
                        appendLog("连接已断开")
                    }
                }
            }
        }
    }
    
    fun stopCurrentCommand() {
        if (currentShellJob?.isActive == true) {
            currentShellJob?.cancel()
            appendLog("[系统] 正在停止...")
        } else {
            appendLog("[系统] 当前没有运行中的任务")
        }
    }
    
    private suspend fun handleBufferedCommand(kadb: Kadb, command: String, timeout: Long) {
        val response = withContext(Dispatchers.IO) {
            withTimeout(timeout) {
                kadb.shell(command)
            }
        }
        withContext(Dispatchers.Main) {
            if (response.allOutput.isNotBlank()) appendLog(response.allOutput.trim())
            else appendLog("[系统] 执行完成，无输出")
        }
    }
    
    private suspend fun handleStreamingCommand(kadb: Kadb, command: String) {
        withContext(Dispatchers.IO) {
            // kadb.openShell() 返回的就是 AdbShellStream
            kadb.openShell(command).use { shellStream ->
            
                // 简单的批处理缓冲，避免高频刷新 UI
                val outputBuffer = StringBuilder()
                var lastUpdate = System.currentTimeMillis()

                try {
                    while (true) {
                        // 直接调用库自带的 read() 方法，它是阻塞的，非常适合协程
                        val packet = shellStream.read()
                    
                        val content = when (packet) {
                            is AdbShellPacket.StdOut -> String(packet.payload)
                            is AdbShellPacket.StdError -> "[Error] " + String(packet.payload)
                            is AdbShellPacket.Exit -> {
                                // 收到 Exit 包，任务结束，跳出循环
                                withContext(Dispatchers.Main) { 
                                    appendLog("[系统] 命令执行结束，退出码: ${packet.payload[0].toUByte()}") 
                                }
                                break
                            }
                        }

                        // 实时追加到缓冲
                        outputBuffer.append(content)

                        // 性能优化：每 500ms 或数据块积累足够多时才刷新 UI
                        if (System.currentTimeMillis() - lastUpdate > 500) {
                            val snapshot = outputBuffer.toString()
                            outputBuffer.clear()
                            lastUpdate = System.currentTimeMillis()
                        
                            withContext(Dispatchers.Main) {
                                appendLog(snapshot)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 捕获协程取消或其他异常
                    withContext(Dispatchers.Main) {
                        if (e is CancellationException) {
                            appendLog("[系统] 用户已手动终止任务")
                        } else {
                            appendLog("[Shell 异常] $e")
                        }
                    }
                }
            }
        }
    }
    
    private fun handleLocalAdbPair(command: String) {
        appendLog("[配对] 执行 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 4) {
            appendLog("[错误] 请使用: adb pair [IP:配对端口] [配对码]")
            return
        }

        val target = parts[2] 
        val pairingCode = parts[3] 
        val hostPort = target.split(":")
        if (hostPort.size != 2) {
            appendLog("[错误] 格式不正确，应为 IP:端口")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { appendLog("[配对] 正在向远端电视注入 TLS 配对验证...") }
                
                Kadb.pair(host = hostPort[0], port = hostPort[1].toInt(), pairingCode = pairingCode)
                
                withContext(Dispatchers.Main) { 
                    appendLog("[成功] 🎉 配对凭证握手存盘成功！")
                    appendLog("[提示] ⚠️ 请查看电视上的【无线调试端口】，输入 adb connect [IP:端口] 唤醒数据总线。")
                    usbForwarder?.stop() // 断开有线转发，准备迎接纯无线
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[配对失败] 异常: ${e.message}") }
            }
        }
    }
    
    private suspend fun handleLocalAdbConnect(command: String) {
        withContext(Dispatchers.Main) {
            appendLog("[无线] 执行 >> $command")
        }
    
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            withContext(Dispatchers.Main) { appendLog("[错误] 请使用: adb connect [IP:无线调试端口]") }
            return
        }
        val target = parts[2]
        val hostPort = target.split(":")
        if (hostPort.size != 2) {
            withContext(Dispatchers.Main) { appendLog("[错误] IP与端口格式错误") }
            return
        }
        val ip = hostPort[0]
        val port = hostPort[1].toInt()

        // 🌟 强力清道夫：用你原有的变量名执行物理释放
        kadbInstance?.let {
            withContext(Dispatchers.Main) { appendLog("[无线] 正在强行释放旧的物理 Socket 链路...") }
            runCatching { it.close() }
        }
        kadbInstance = null

        try {
            withContext(Dispatchers.Main) { appendLog("[无线] 正在唤醒远端网络数据通道...") }
        
            // 切到 IO 线程池创建 Socket
            val instance = withContext(Dispatchers.IO) {
                usbForwarder?.stop() // 掐断有线桥接
                Kadb.create(host = ip, port = port)
            }
        
            withContext(Dispatchers.Main) { appendLog("[无线] 正在向网络通道发射探路信号...") }
        
            // 挂起等待远端握手响应
            val response = withContext(Dispatchers.IO) {
                instance.shell("echo 1")
            }

            // 时序安全赋值：echo 1 没返回前，绝对不会走到这一步
            if (response.exitCode == 0 && response.allOutput.trim() == "1") {
            
                // 🌟 探路成功，直接赋值给你原来的全局变量
                kadbInstance = instance 
            
                withContext(Dispatchers.Main) {
                    isAdbAuthorized = true
                    appendLog(">>> 👍 无线调试通道连通成功！支持命令与推拉。 <<<")
                    saveConnectedDevice(ip, port)
                }
            } else {
                runCatching { instance.close() }
                withContext(Dispatchers.Main) { appendLog("[警告] 远端响应握手信号失败，退出通道") }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("[连接失败] 远端网络拒绝建立链路: ${e.message}")
                appendLog("[无线提示] 如果没有自动触发 adb connect 连接，那就点击菜单项上的 “触发无线调试扫描” 来触发，正常情况下，WLAN 开启/关闭都会扫描一次")
            }
        }
    }
    
    private fun handleLocalAdbPush(command: String) {
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 4) {
            appendLog("[错误] 请使用: adb push [本地文件名] [远端路径]")
            return
        }

        val localInput = parts[2]
        val remotePath = parts[3]
        val localFile = if (localInput.startsWith("/")) File(localInput) else File(flashFolder, localInput)

        if (!localFile.exists()) {
            appendLog("[错误] 找不到本地物理文件: ${localFile.absolutePath}")
            return
        }

        val finalRemotePath = if (remotePath.endsWith("/")) remotePath + localFile.name else remotePath
        appendLog("[Sync] 正在安全推送: ${localFile.name} -> $finalRemotePath")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
                val totalBytes = localFile.length()
            
                // 1. 开启高级同步通道
                val syncService = kadb.openSync() 
                val startTime = System.currentTimeMillis()
            
                var bytesTransferred = 0L
                val bufferSize = 1024 * 256 // 🌟 优化：采用 256KB 大缓冲区提升传输速度
                val buffer = ByteArray(bufferSize)
            
                // 打开本地输入流
                FileInputStream(localFile).use { inputStream ->
                    // 通过 syncService 开启远端写入流（视具体库的 API 命名，通常为 send 或 writeStream）
                    syncService.openWriteStream(finalRemotePath).use { outputStream ->
                        var bytesRead: Int
                        var lastUpdateTime = System.currentTimeMillis()
                    
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                        
                            val currentTime = System.currentTimeMillis()
                            // 🌟 控制刷新频率：每 300 毫秒在 UI 上更新一次实时状态，防止频繁刷新卡死 UI
                            if (currentTime - lastUpdateTime >= 300 || bytesTransferred == totalBytes) {
                                val durationMs = currentTime - startTime
                                val speedStr = calculateSpeed(bytesTransferred, durationMs)
                                val progress = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes).toInt() else 0
                            
                                withContext(Dispatchers.Main) {
                                    // 提示：实际开发中可以通过特定更新机制，让此行日志在固定区域“原地刷新”而不用一直 append 刷屏
                                    appendLog("[实时] 进度: $progress% | 已传: ${bytesTransferred / 1024 / 1024}MB | 实时速度: $speedStr | 已耗时: ${durationMs / 1000.0}s")
                                }
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                }
            
                // 关闭通道
                syncService.close()
            
                val totalDurationMs = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    appendLog("[成功] 文件已被推入远端: $finalRemotePath")
                    appendLog("[总体性能] 总耗时: ${totalDurationMs / 1000.0}s | 平均速度: ${calculateSpeed(totalBytes, totalDurationMs)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Push 失败] 传输崩塌: ${e.message}") }
            }
        }
    }
    
    private fun handleLocalAdbPull(command: String) {
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb pull [远端路径] (可选本地落地名)")
            return
        }

        val remotePath = parts[2]
        val localInput = if (parts.size >= 4) parts[3] else remotePath.substringAfterLast("/")
        val localFile = if (localInput.startsWith("/")) File(localInput) else File(flashFolder, localInput)

        appendLog("[Sync] 正在拉取远端数据: $remotePath -> ${localFile.absolutePath}")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
            
                // 1. 开启高级同步通道
                val syncService = kadb.openSync()
                // 🌟 尝试获取远端文件的大小以便计算进度百分比（部分 ADB 库支持 statSync）
                val totalBytes = try { syncService.stat(remotePath).size } catch (e: Exception) { -1L }
            
                val startTime = System.currentTimeMillis()
                var bytesTransferred = 0L
                val bufferSize = 1024 * 256
                val buffer = ByteArray(bufferSize)
            
                FileOutputStream(localFile).use { outputStream ->
                    // 通过 syncService 开启远端读取流
                    syncService.openReadStream(remotePath).use { inputStream ->
                        var bytesRead: Int
                        var lastUpdateTime = System.currentTimeMillis()
                    
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            bytesTransferred += bytesRead
                        
                            val currentTime = System.currentTimeMillis()
                            // 🌟 每 300 毫秒刷新一次 UI
                            if (currentTime - lastUpdateTime >= 300) {
                                val durationMs = currentTime - startTime
                                val speedStr = calculateSpeed(bytesTransferred, durationMs)
                                val progressStr = if (totalBytes > 0L) "${(bytesTransferred * 100 / totalBytes)}%" else "未知"
                            
                                withContext(Dispatchers.Main) {
                                    appendLog("[实时] 进度: $progressStr | 已下载: ${bytesTransferred / 1024 / 1024}MB | 实时速度: $speedStr | 已耗时: ${durationMs / 1000.0}s")
                                }
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                }
            
                syncService.close()
            
                val totalDurationMs = System.currentTimeMillis() - startTime
                withContext(Dispatchers.Main) {
                    appendLog("[成功] 数据已沉淀至本地: ${localFile.absolutePath}")
                    appendLog("[总体性能] 总耗时: ${totalDurationMs / 1000.0}s | 平均速度: ${calculateSpeed(bytesTransferred, totalDurationMs)}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Pull 失败] 提取中止: ${e.message}") }
            }
        }
    }
    
    private fun calculateSpeed(bytes: Long, durationMs: Long): String {
        if (durationMs <= 0 || bytes <= 0) return "0 KB/s"
    
        // 秒数 = 毫秒 / 1000
        val seconds = durationMs / 1000.0
        // 每秒传输的字节数
        val bytesPerSecond = bytes / seconds
    
        return when {
            // 如果达到 MB/s 级别 (大于等于 1024 * 1024 字节)
            bytesPerSecond >= 1048576 -> {
                val mbps = bytesPerSecond / 1048576.0
                String.format(java.util.Locale.US, "%.2f MB/s", mbps)
            }
            // 如果是 KB/s 级别
            bytesPerSecond >= 1024 -> {
                val kbps = bytesPerSecond / 1024.0
                String.format(java.util.Locale.US, "%.2f KB/s", kbps)
            }
            // 极小文本下的 B/s 级别
            else -> {
                String.format(java.util.Locale.US, "%.0f B/s", bytesPerSecond)
            }
        }
    }
    
    private fun saveConnectedDevice(ip: String, port: Int) {
        val currentWifi = getCurrentWifiSsid()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceList = getAllSavedDevices().toMutableList()
        
        deviceList.removeAll { it.ip == ip && it.wifiSsid == currentWifi }
        deviceList.add(0, AdbDevice(ip, port, currentWifi, System.currentTimeMillis()))
        
        val trimmedList = if (deviceList.size > 10) deviceList.subList(0, 10) else deviceList
        val jsonArray = JSONArray()
        for (dev in trimmedList) {
            val obj = JSONObject().apply {
                put("ip", dev.ip)
                put("port", dev.port)
                put("wifiSsid", dev.wifiSsid)
                put("lastConnectedTime", dev.lastConnectedTime)
            }
            jsonArray.put(obj)
        }
        prefs.edit {
            putString(KEY_DEVICE_LIST, jsonArray.toString())
        }
    }

    private fun getAllSavedDevices(): List<AdbDevice> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DEVICE_LIST, null) ?: return emptyList()
        val list = mutableListOf<AdbDevice>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    AdbDevice(
                        ip = obj.getString("ip"),
                        port = obj.getInt("port"),
                        wifiSsid = obj.getString("wifiSsid"),
                        lastConnectedTime = obj.getLong("lastConnectedTime")
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }
    
    private fun getCurrentWifiSsid(): String {
        try {
            val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 🌟 1. Android 10+ (API 29+) 现代标准非废弃写法
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    // 直接从网络能力承载体中安全提取网络传输信息
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    if (wifiInfo != null) {
                        val ssid = wifiInfo.ssid.replace("\"", "")
                        if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                            return ssid
                        }
                    }
                }
            }
            @Suppress("DEPRECATION")
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            if (info != null) {
                val ssid = info.ssid.replace("\"", "")
                if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                    return ssid
                }
            }
        } catch (e: Exception) {
            Log.e("adbKitty", "获取无线SSID受限", e)
        }
        return "DEFAULT_WIFI"
    }

    fun initWifiState() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        isWifiEnabled = wifiManager.isWifiEnabled
        appendLog("[系统] 🚀 初始 WLAN 状态: isWifiEnabled = $isWifiEnabled")
        handleWifiConnectionFlow()
    }

    private fun handleWifiConnectionFlow() {
        if (checkAndRequestWifiPermission()) {
            if (isWifiEnabled) executeAutoWifiConnect()
            else appendLog("[系统] 📡 自动回连已跳过：手机 WLAN 开关当前未开启。")
        }
    }

    private fun checkAndRequestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2027)
                return false
            }
        }
        return true
    }
    
    private fun getWifiScanPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }
    
    private fun checkAndRequestWifiPermission(): Boolean {
        val permissionsToRequest = mutableListOf<String>()
        val wifiPermission = getWifiScanPermission()
        if (ContextCompat.checkSelfPermission(this, wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(wifiPermission)
        }

        if (Build.VERSION.SDK_INT >= 37) {
            val localNetPermission = "android.permission.ACCESS_LOCAL_NETWORK"
            if (ContextCompat.checkSelfPermission(this, localNetPermission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(localNetPermission)
            }
        }

        return if (permissionsToRequest.isNotEmpty()) {
            requestNetworkPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
            false
        } else {
            true
        }
    }

    fun startIpNetworkTest() {
        // 合并为一个统一的 IO 协程流，确保控制台输出的时序绝对工整不乱序
        lifecycleScope.launch(Dispatchers.Main) {
            appendLog("[网络探针] 正在唤醒底层网络数据透视...")

            val ipManager = IpManager()
            
            // 1. 抓取本地物理与虚拟网卡快照
            val localIp = withContext(Dispatchers.IO) { ipManager.getAllLocalIpAddresses() }
            appendLog("[本地 Wi-Fi 网卡] IPv4: ${localIp.wifiIpv4 ?: "未连接"}")
            appendLog("[本地 Wi-Fi 网卡] IPv6: ${localIp.wifiIpv6 ?: "无IPv6"}")
            appendLog("[本地移动网卡] IPv4: ${localIp.mobileIpv4 ?: "未开启"}")
            appendLog("[本地移动网卡] IPv6: ${localIp.mobileIpv6 ?: "无IPv6"}")
            appendLog("[本地 VPN 网卡] IPv4: ${localIp.vpnIpv4 ?: "未创建"}")
            
            // 2. 串行测试全球 IPv4 出口（多节点测绘防御单点崩溃）
            appendLog("[探针] 正在向全球 IPv4 节点发射探测信标...")
            val vpnOuterIpv4 = fetchIpFromWeb("https://api.ipify.org")
            appendLog("[外网出口] 测试 IPv4 (api.ipify.org) -> ${vpnOuterIpv4 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv41 = fetchIpFromWeb("https://v4.ident.me")
            appendLog("[外网出口] 测试 IPv4 (v4.ident.me) -> ${vpnOuterIpv41 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv42 = fetchIpFromWeb("https://ipv4.icanhazip.com")
            appendLog("[外网出口] 测试 IPv4 (ipv4.icanhazip.com) -> ${vpnOuterIpv42 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv43 = fetchIpFromWeb("https://myip.dnsomatic.com")
            appendLog("[外网出口] 测试 IPv4 (myip.dnsomatic.com) -> ${vpnOuterIpv43 ?: "连接失败(可能无v4网络或代理断开)"}")
            
            val vpnOuterIpv44 = fetchIpFromWeb("https://api-ipv4.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv4 (api-ipv4.ip.sb/ip) -> ${vpnOuterIpv44 ?: "连接失败(可能无v4网络或代理断开)"}")

            // 3. 串行测试全球 IPv6 出口
            appendLog("[探针] 正在向全球 IPv6 节点发射探测信标...")
            val vpnOuterIpv6 = fetchIpFromWeb("https://api6.ipify.org")
            appendLog("[外网出口] 测试 IPv6 (api6.ipify.org) -> ${vpnOuterIpv6 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv61 = fetchIpFromWeb("https://v6.ident.me")
            appendLog("[外网出口] 测试 IPv6 (v6.ident.me) -> ${vpnOuterIpv61 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv62 = fetchIpFromWeb("https://ipv6.icanhazip.com")
            appendLog("[外网出口] 测试 IPv6 (ipv6.icanhazip.com) -> ${vpnOuterIpv62 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv63 = fetchIpFromWeb("https://api-ipv6.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv6 (api-ipv6.ip.sb/ip) -> ${vpnOuterIpv63 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            // 4. 双通道直连骨干网测试
            appendLog("[探针] 正在评测全球骨干网连通度...")
            val isV4Ok = verifyGoogleOutbound("https://ipv4.google.com/generate_204")
            val isV6Ok = verifyGoogleOutbound("https://ipv6.google.com/generate_204")
            appendLog("[Google通道] 物理/VPN IPv4 直连状态: ${if(isV4Ok) "🟢 畅通" else "🔴 阻塞"}")
            appendLog("[Google通道] 物理/VPN IPv6 直连状态: ${if(isV6Ok) "🟢 畅通" else "🔴 阻塞"}")
            
            // 5. 抓取本地虚拟 VPN 网卡底层的 IPv6 状态
            val vpnIpManager = VpnIpManager()
            val localVpnIpv6 = withContext(Dispatchers.IO) { vpnIpManager.getLocalVpnIpv6(applicationContext) }
            if (localVpnIpv6 != null) {
                appendLog("[本地 VPN 网卡] 成功抓取本地 VPN IPv6 地址: $localVpnIpv6")
            } else {
                appendLog("[本地 VPN 网卡] 未检测到本地 VPN 的 IPv6 地址 (VPN未开启，或该VPN软件底层未分配IPv6虚拟网卡)")
            }
            
            appendLog("[系统] === 全网环境深度检测结束 ===")
        }
    }
    
    private suspend fun verifyGoogleOutbound(urlString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            return@withContext conn.responseCode == 204
        } catch (e: Exception) {
            return@withContext false
        }
    }
    
    private suspend fun fetchIpFromWeb(urlString: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    reader.readLine()?.trim()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
        readerJob?.cancel()
        usbConn?.close()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbStateReceiver)
        unregisterReceiver(wifiReceiver)
        inspector.unbindRootService()
        usbForwarder?.stop()
        runCatching { kadbInstance?.close() }
    }
}

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBarExample(
    viewModel: MainActivityViewModel,
    activity: MainActivity,
    onExecuteCommand: (String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showMenu by remember { mutableStateOf(false) }
    
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { 
        mutableStateOf(TextFieldValue("")) 
    }
    val filteredItems by remember(query.text) {
        derivedStateOf {
            if (query.text.isEmpty()) emptyList() 
            else {
                viewModel.items.filter { 
                    it.command.contains(query.text, ignoreCase = true) || 
                    it.description.contains(query.text, ignoreCase = true)
                }
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    
    val logs = viewModel.logs

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (query.text.isNotBlank()) {
                        onExecuteCommand(query.text)
                        query = TextFieldValue("")
                        expanded = false
                    }
                },
                icon = { 
                    Icon(
                        imageVector = wand_stars,
                        contentDescription = "Wand Stars"
                    )
                },
                text = { 
                    Text(
                        text = stringResource(R.string.push_cmd_name)
                    ) 
                },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.app_bar_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                         /* do something */ 
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Localized description"
                        )
                    }
                },
                actions = {
                    // 2. 使用 Box 包裹图标和菜单
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        
                        // 3. 定义下拉菜单
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_dellog)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { 
                                    viewModel.clearLogs()
                                    showMenu = false 
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_help)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    viewModel.appendLog("\n" +viewModel.warnMessage)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_log_export)
                                    )
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, null) },
                                onClick = {
                                    onExecuteCommand("userkitty-log-export")
                                    showMenu = false 
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_usb)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Hub, null) },
                                onClick = {
                                    activity.findHostDevice()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_wifi)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    activity.initWifiState()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_iptest)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                onClick = {
                                    activity.startIpNetworkTest()
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_selinux)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.LockOpen, null) },
                                onClick = {
                                    showMenu = false
                                    activity.FbSeLinuxCmd()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_rate)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                onClick = {
                                    showMenu = false
                                    activity.inspector.bindRootService { isConnected ->
                                        if (isConnected) {
                                            activity.inspector.start()
                                        } else {
                                            activity.appendLog("[错误] Root 特权服务绑定失败！请确认设备已获得 Magisk/Apatch/KernelSU 完整授权！")
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_rate_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Stop, null) },
                                onClick = {
                                    showMenu = false
                                    activity.inspector.stop()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.enterTurboMode()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.exitTurboMode()
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_shell_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                onClick = {
                                    showMenu = false
                                    activity.stopCurrentCommand()
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
            
            Box(modifier = Modifier.wrapContentHeight()) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { 
                            query = it
                            expanded = it.text.isNotEmpty()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                        label = {
                            Text(
                                stringResource(R.string.action_menu_sospl)
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                
                    ExposedDropdownMenu(
                        expanded = expanded && filteredItems.isNotEmpty(), 
                        onDismissRequest = { expanded = false }
                    ) {
                        filteredItems.forEach { item ->
                            DropdownMenuItem(
                                text = { 
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 4.dp)
                                            .padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = item.command,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                        
                                    Spacer(modifier = Modifier.height(2.dp))
                        
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                        Text(
                                            text = when {
                                                item.isApp -> "[APP]"
                                                item.isAdb -> "[ADB]"
                                                else -> "[Fastboot]"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                item.isApp -> MaterialTheme.colorScheme.secondary
                                                item.isAdb -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.error
                                            },
                                            modifier = Modifier.wrapContentWidth()
                                        )
                                    }
                                },
                                onClick = {
                                    val targetCommand = item.command
                                    query = TextFieldValue(
                                        text = targetCommand,
                                        selection = TextRange(targetCommand.length) 
                                    )
                                    expanded = false // 点击后收起菜单
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
            LogSection(
                logs = logs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 75.dp)
            )
        }
    }
}

@Keep
@Composable
fun LogSection(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val globalHorizontalScrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            val lastIndex = logs.size - 1
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            
            if (lastIndex - lastVisibleIndex < 5) {
                listState.scrollToItem(lastIndex) 
            } else {
                if (lastIndex - lastVisibleIndex < 20) {
                    listState.animateScrollToItem(lastIndex)
                } else {
                    listState.scrollToItem(lastIndex)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .horizontalScroll(globalHorizontalScrollState)
            .padding(8.dp)
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth() 
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionBottomSheet(
    wifiName: String,
    devices: List<AdbDevice>,
    onDeviceSelected: (AdbDevice) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📡 检测到当前 WiFi [$wifiName] 下有多个历史设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { dev ->
                    ListItem(
                        headlineContent = { Text("📺 IP: ${dev.ip}:${dev.port}") },
                        supportingContent = { Text("上次并网时间: ${getRelativeTime(dev.lastConnectedTime)}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(dev) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Keep
private fun getRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val minutes = diff / 1000 / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        else -> "${hours}小时前"
    }
}
