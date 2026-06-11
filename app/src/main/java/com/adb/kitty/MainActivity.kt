/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import com.adb.kitty.databinding.ActivityMainBinding
import com.adb.kitty.R

import android.Manifest
import android.util.Log
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Bitmap

import android.view.View
import android.view.ViewGroup
import android.view.Menu
import android.view.MenuItem
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbAccessory

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.text.method.ScrollingMovementMethod
import android.widget.*

import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat

import com.google.android.material.bottomsheet.BottomSheetDialog

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin

import kotlin.ExperimentalUnsignedTypes
import kotlin.coroutines.resume
import kotlin.math.roundToInt

import java.io.*
import java.nio.*

import java.security.KeyPair
import java.security.Signature
import javax.crypto.Cipher
import java.text.SimpleDateFormat

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.Socket

import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import okio.Buffer
import com.flyfishxu.kadb.Kadb
import org.json.JSONArray
import org.json.JSONObject

data class AdbCommand(val description: String, val command: String)

data class FbCommand(val description: String, val command: String)

data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

data class AdbDevice(val ip: String, val port: Int, val wifiSsid: String, val lastConnectedTime: Long)

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private lateinit var keyManager: AdbKeyManager
    private lateinit var inspector: RefreshRateInspector
    private val ACTION_USB_PERMISSION = "com.adb.kitty.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var rsaKeyPair: KeyPair? = null
    private var readerJob: Job? = null
    private var mLocalId = 1
    private var accessoryPfd: ParcelFileDescriptor? = null
  //  private var kadbInstance: Kadb? = null
    private var usbForwarder: UsbPortForwarder? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false
    private var isFirstTryInThisSession = true
    private var isWifiEnabled: Boolean = false

    private val responseChannel = Channel<String>(Channel.CONFLATED)
    private val turbo by lazy { PerformanceTurbo(this) }
    
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }
    
    private var adbService: AdbSessionService? = null
    private var isServiceBound = false
    private var kadbInstance: Kadb?
        get() = if (isServiceBound) adbService?.globalKadbInstance else null
        set(value) {
            if (isServiceBound) {
                adbService?.globalKadbInstance = value
            }
        }
        
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
    
    private val REQUEST_WIFI_PERMISSION_CODE = 99
    private val PREFS_NAME = "AdbMultiDevicePrefs"
    private val KEY_DEVICE_LIST = "device_list"
    
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE, 
                    WifiManager.WIFI_STATE_UNKNOWN
                )

                // 记录旧的状态，用来判断状态是否真的改变了
                val oldState = isWifiEnabled

                when (wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> {
                        isWifiEnabled = true
                        appendLog("[系统] ⏳ WLAN 已开启")
                        
                        // 状态从关闭变为开启时，自动触发你的连接逻辑
                        if (!oldState) {
                            handleWifiConnectionFlow()
                        }
                    }
                    WifiManager.WIFI_STATE_DISABLED -> {
                        isWifiEnabled = false
                        appendLog("[系统] ⏳ WLAN 已关闭")
                        
                        // 状态变为关闭时，触发你的提示逻辑
                        handleWifiConnectionFlow()
                    }
                    WifiManager.WIFI_STATE_ENABLING -> {
                        appendLog("[系统] ⏳ WLAN 可能正在开启中……")
                    }
                    WifiManager.WIFI_STATE_DISABLING -> {
                        appendLog("[系统] ⏳ WLAN 可能正在关闭中……")
                    }
                }
            }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    // 1. 尝试获取 Device (Host 模式)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    // 2. 尝试获取 Accessory (配件模式)
                    val accessory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }
                    // 分别处理
                    if (device != null) {
                        appendLog("[系统] USB 调试设备权限获取成功")
                        connectToInterface(device) // 保持你原有的逻辑
                    } else if (accessory != null) {
                        appendLog("[系统] USB 配件模式权限获取成功")
                    }
                } else {
                    updateStatus("用户拒绝了 USB 权限申请")
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
                    refreshUiText()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    isUsbAttached = false
                    isAdbAuthorized = false
                    isFastbootMode = false
                    readerJob?.cancel()
                    usbConn?.close()
                    refreshUiText()
                    appendLog("[系统] USB 设备已断开")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
           // 状态栏：透明背景，图标颜色随系统主题自适应
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            // 导航栏：透明背景，手势线/图标颜色随系统主题自适应
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false) // 隐藏默认标题
        }
        // 将 view 改为 binding.root
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
             val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
             v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
             insets
        }
        
        ensureFlashDirExists()
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
        
        inspector = RefreshRateInspector(this, this) { logText ->
            appendLog(logText)
        }
        
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(wifiReceiver, filter)
        }
        
        binding.appMainActivity.btnConnect.setOnClickListener { findHostDevice() }
        
        binding.appMainActivity.ipTest.setOnClickListener { IpTestWork() }
        
        binding.appMainActivity.flashAll.setOnClickListener {  }
        
        binding.appMainActivity.btnSend.setOnClickListener {
            val cmd = binding.appMainActivity.etCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            binding.appMainActivity.etCommand.setText("")

            if (isFastbootMode) {
                // Fastboot 特权管道保持你原有的逻辑
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) {
                           appendLog("[发送] FB >> $cmd")
                        }
                        executeCommandSync(cmd)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                    }
                }
            } else {
                // ADB 特权管道：智能解构高级指令
                if (!isAdbAuthorized && !cmd.startsWith("adb pair") && !cmd.startsWith("adb connect")) {
                    Toast.makeText(this, "设备未就绪或未授权", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            lifecycleScope.launch(Dispatchers.IO) {
                when {
                    // 1. 拦截无线配对
                    cmd.startsWith("adb pair") -> handleLocalAdbPair(cmd)
                    
                    // 2. 拦截无线建链连接
                    cmd.startsWith("adb connect") -> handleLocalAdbConnect(cmd)
                    
                    // 3. 拦截有线/无线特权文件推送
                    cmd.startsWith("adb push") -> handleLocalAdbPush(cmd)
                    
                    // 4. 拦截有线/无线特权文件提取
                    cmd.startsWith("adb pull") -> handleLocalAdbPull(cmd)
                    
                    // 5. 默认兜底：其余命令全部走纯正的 adb shell
                    else -> sendAdbShell(cmd)
                    }
                }
            }
        }
      /*  binding.appMainActivity.btnSend.setOnClickListener {
            val cmd = binding.appMainActivity.etCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            binding.appMainActivity.etCommand.setText("")

            if (isFastbootMode) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) {
                           appendLog("[发送] FB >> $cmd")
                        }
                        executeCommandSync(cmd)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                    }
                }
            } else if (isAdbAuthorized) {
                sendAdbShell(cmd)
            } else {
                Toast.makeText(this, "设备未就绪或未授权", Toast.LENGTH_SHORT).show()
            }
        }*/
        refreshUiText()
    }
    
    override fun onCreateOptionsMenu(menu: Menu?):   Boolean {
          menuInflater.inflate(R.menu.menu_main, menu)
    
         return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
           return when (item.itemId) {
              R.id.action_main_1 -> {
              showAdbCommandDialog()
               true
           }
              R.id.action_main_2 -> {
              showFbCommandDialog()
                true
           }
              R.id.action_main_3 -> {
              initWifiState()
                true
           }
              R.id.action_main_4 -> {
              WarngApps()
                true
           }
              R.id.action_main_5 -> {
              inspector.bindRootService { isConnected ->
                  if (isConnected) {
                      inspector.start()
                      updateStatus("正在进行帧率测试")
                  } else {
                      appendLog("[错误] Root 特权服务绑定失败！请确认设备已获得 Magisk/Apatch/KernelSU 完整授权！")
                  }
              }
                true
           }
              R.id.action_main_6 -> {
              inspector.stop()
                true
           }
              R.id.action_main_7 -> {
              exportLogToFlashFolder()
                true
           }
              R.id.action_main_8 -> {
              lifecycleScope.launch(Dispatchers.IO) {
                  turbo.enterTurboMode()
              }
                true
           }
              R.id.action_main_9 -> {
              lifecycleScope.launch(Dispatchers.IO) {
                  turbo.exitTurboMode()
              }
                true
           }
              R.id.action_main_10 -> {
              FbSeLinuxCmd()
                true
           }
             else -> super.onOptionsItemSelected(item)
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
    
    private fun initWifiState() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        isWifiEnabled = wifiManager.isWifiEnabled
        appendLog("[系统] 🚀 初始 WLAN 状态: isWifiEnabled = $isWifiEnabled")
        
        // 刚进入 App 时执行一次检查
        handleWifiConnectionFlow()
    }
    
    private fun handleWifiConnectionFlow() {
        if (checkAndRequestWifiPermission()) {
            if (isWifiEnabled) {
                executeAutoWifiConnect()
            } else {
                appendLog("[系统] 📡 自动回连已跳过：手机 WLAN (Wi-Fi) 开关当前未开启。")
            }
        }
    }
    
    private fun FbSeLinuxCmd() {
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
               sendFastbootCommandDirect(cmd)
               // 3. 等待设备响应（如果有）
                delay(500) 
            }
        }
    }
    
    private fun exportLogToFlashFolder() {
        val logContent = binding.appMainActivity.tvLog.text.toString().trim()
        if (logContent.isEmpty() || logContent == "日志输出…") {
            appendLog("[提示] 当前控制台日志空空如也")
            return
        }

        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val timeStamp = LocalDateTime.now().format(formatter)
        val fileName = "Log_$timeStamp.txt"
        val targetFile = File(flashFolder, fileName)

        try {
            FileWriter(targetFile).use { writer ->
                writer.write(logContent)
            }
            appendLog("[系统] 🎉 日志已成功安全写入文件：${targetFile.absolutePath}")
        } catch (e: Exception) {
            appendLog("[错误] ❌ 写入文件时发生异常: ${e.message}")
        }
    }
    
    private fun IpTestWork() {
        val ipManager = IpManager()
        lifecycleScope.launch {
            val localIp = ipManager.getAllLocalIpAddresses()
            updateStatus("正在进行IP地址测试")
            appendLog("[本地 Wi-Fi 网卡] IPv4: ${localIp.wifiIpv4 ?: "未连接"}")
            appendLog("[本地 Wi-Fi 网卡] IPv6: ${localIp.wifiIpv6 ?: "无IPv6"}")
            appendLog("[本地移动网卡] IPv4: ${localIp.mobileIpv4 ?: "未开启"}")
            appendLog("[本地移动网卡] IPv6: ${localIp.mobileIpv6 ?: "无IPv6"}")
            appendLog("[本地 VPN 网卡] IPv4: ${localIp.vpnIpv4 ?: "未创建"}")
            
            // 测试 VPN 的 IPv4 出口
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

            // 测试 VPN 的 IPv6 出口
            val vpnOuterIpv6 = fetchIpFromWeb("https://api6.ipify.org")
            appendLog("[外网出口] 测试 IPv6 (api6.ipify.org) -> ${vpnOuterIpv6 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv61 = fetchIpFromWeb("https://v6.ident.me")
            appendLog("[外网出口] 测试 IPv6 (v6.ident.me) -> ${vpnOuterIpv61 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv62 = fetchIpFromWeb("https://ipv6.icanhazip.com")
            appendLog("[外网出口] 测试 IPv6 (ipv6.icanhazip.com) -> ${vpnOuterIpv62 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            val vpnOuterIpv63 = fetchIpFromWeb("https://api-ipv6.ip.sb/ip")
            appendLog("[外网出口] 测试 IPv6 (api-ipv6.ip.sb/ip) -> ${vpnOuterIpv63 ?: "连接失败(可能代理不支持v6或网络无v6)"}")
            
            appendLog("[系统] === 检测结束 ===")
        }
        
        lifecycleScope.launch {
            val isV4Ok = verifyGoogleOutbound("https://ipv4.google.com/generate_204")
            val isV6Ok = verifyGoogleOutbound("https://ipv6.google.com/generate_204")

            appendLog("[Google通道] 物理/VPN IPv4 直连状态: ${if(isV4Ok) "🟢 畅通" else "🔴 阻塞"}")
            appendLog("[Google通道] 物理/VPN IPv6 直连状态: ${if(isV6Ok) "🟢 畅通" else "🔴 阻塞"}")
        }
        
        val vpnIpManager = VpnIpManager()
        val localVpnIpv6 = vpnIpManager.getLocalVpnIpv6(applicationContext)

        if (localVpnIpv6 != null) {
            appendLog("[本地 VPN 网卡] 成功抓取本地 VPN IPv6 地址: $localVpnIpv6")
        } else {
            appendLog("[本地 VPN 网卡] 未检测到本地 VPN 的 IPv6 地址 (VPN未开启，或该VPN软件底层未分配IPv6虚拟网卡)")
        }
    }
    
    suspend fun verifyGoogleOutbound(urlString: String): Boolean = withContext(Dispatchers.IO) {
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
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val ip = reader.readLine()
                reader.close()
                ip?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun WarngApps() {
        val warnTitle = "注意事项"
        val warnMessage = "1.ADB授权之后就直接进入了adb shell，命令里也就不需要加上adb shell \n2.fastboot通信链路如果有问题，请第一时间前往GitHub提交问题 \n3.GitHub地址：https://github.com/Com-kt/adbUSB \n4.应用自身没有签名校验机制，随时都有可能会被寡改 \n5.如果您认为此应用程序Fastboot的实现不能进行线刷，那么您就不要线刷 \n6.免责声明：开发者没有任何义务对所有人进行服务 \n7.线刷文件夹路径：/storage/emulated/0/Android/data/com.adb.kitty/files/flash/"
    
        val builder = AlertDialog.Builder(this)
            .setTitle(warnTitle)
            .setMessage(warnMessage)
            .setPositiveButton("ok") { _, _ ->
                Toast.makeText(this, "好的呢，知道啦！", Toast.LENGTH_SHORT).show()
            }
        val dialog = builder.create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }
    
    private fun showFbCommandDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("快捷发送 Fastboot 命令")
        val fbCommands = viewModel.fbCommands
        val adapter = object : ArrayAdapter<FbCommand>(this, android.R.layout.simple_list_item_2, android.R.id.text1, fbCommands) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
            
                val item = getItem(position)
                text1.text = item?.description
                text2.text = item?.command
                return view
            }
        }
        
        builder.setAdapter(adapter) { _, which ->
            if (isFastbootMode) {
                val fcmd = fbCommands[which].command
                // 推荐使用 Kotlinx 协程的方法去执行
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        // 1. 先把要发的命令打印出来
                        withContext(Dispatchers.Main) {
                           appendLog("[发送] FB >> $fcmd") 
                        }
                        executeCommandSync(fcmd)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                    }
                }
            } else {
                Toast.makeText(this, "Fastboot 未授权", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }
    
    private fun showAdbCommandDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("快捷发送 ADB 命令")
        val adbCommands = viewModel.adbCommands
        val adapter = object : ArrayAdapter<AdbCommand>(this, android.R.layout.simple_list_item_2, android.R.id.text1, adbCommands) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val text1 = view.findViewById<TextView>(android.R.id.text1)
                val text2 = view.findViewById<TextView>(android.R.id.text2)
            
                val item = getItem(position)
                text1.text = item?.description
                text2.text = item?.command
                return view
            }
        }
        
        builder.setAdapter(adapter) { _, which ->
            if (isAdbAuthorized) {
                val selectedCommand = adbCommands[which].command
                lifecycleScope.launch(Dispatchers.IO) {
                    sendAdbShell(selectedCommand)
                }
            } else {
                Toast.makeText(this, "ADB 未授权", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }
    
    private fun findHostDevice() {
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            updateStatus("未发现 USB 设备")
            return
        }

        for (device in devices.values) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                appendLog("设备: ${device.productName ?: "未知"}")
                appendLog("制造商: ${device.manufacturerName ?: "未知"}")
                appendLog("版本号: ${device.version}")
                // 在遍历 interface 的循环内
                appendLog("接口名称: ${intf.name ?: "无描述"}")
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
                        refreshUiText()
                        
                    } else {
                        appendLog("[Serial] 硬件序列号: ${device.serialNumber ?: "未提供"}")
                        connectToInterface(device)
                    }
                    return
                }
            }
        }
        updateStatus("发现设备但无 ADB/Fastboot 接口")
    }
    /**
     * 🚀 物理接口鉴权与连接：通过本地 TCP 环回桥接绕过 KADB 物理限制
     */
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
            isAdbAuthorized = true 
            refreshUiText()
            startFastbootReader()
            appendLog("[系统] Fastboot 物理信道就绪")
        } else {
            // kadb 库可能只有在发送 adb shell 命令时才会使用 adb shell，故此 USB 授权之后就默认 adb 已授权，以此开放 adb shell 命令发送
            isAdbAuthorized = true
            appendLog("[系统] 正在建立虚拟本地有线网络转发桥接...")
            appendLog("--- 可能需要开启USB调试 (安全设置) ---")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    usbForwarder?.stop()
                    usbForwarder = UsbPortForwarder(conn, epIn!!, epOut!!)
                    val localVirtualPort = usbForwarder!!.startBridge()

                    withContext(Dispatchers.Main) {
                        appendLog("[Auth] 正在向环回端口 [$localVirtualPort] 发起握手与撞门机制...")
                    }

                    // 创建有线专用本地环回 KADB 实例
                    kadbInstance = Kadb.create(host = "127.0.0.1", port = localVirtualPort)
                    val isConnected = kadbInstance!!.connectionCheck()

                    withContext(Dispatchers.Main) {
                        if (isConnected) {
                            isAdbAuthorized = true
                            refreshUiText()
                            updateStatus("USB链路: adb shell 首次授权成功")
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
    /**
     * 🛰️ 100% 对齐 2.x 的高级特权 Shell 命令单步发射
     */
    private suspend fun sendAdbShell(command: String) {
        // 1. 在主线程先行回显用户输入的命令
        withContext(Dispatchers.Main) {
            isAdbAuthorized = true
            appendLog("ADB >> $command")
        }
    
        val cleanCmd = command.removePrefix("adb shell ").trim()
    
        try {
            // 🌟 核心防空：直接安全读取当前本地持有的实例，若为空则直接抛出
            val kadb = kadbInstance ?: throw IllegalStateException("通道连接未就绪")
        
            // 🌟 2. 挂起等待：切到 IO 线程执行命令，在远端响应返回前，后面的代码绝对不会偷跑
            val response = withContext(Dispatchers.IO) {
                kadb.shell(cleanCmd)
            }
        
            // 3. 切回主线程打印全量回显
            withContext(Dispatchers.Main) {
                // 如果远端返回的内容不为空则打印，否则给个友好提示
                if (response.allOutput.isNotBlank()) {
                    appendLog(response.allOutput.trim())
                } else {
                    appendLog("[系统] 命令已执行，远端无标准输出回显")
                }
                appendLog("[流结束]")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("[Shell 异常] 传输阻断: ${e.message}")
                // 如果发生了物理断开（如 Pipe broken/Socket closed），及时将全局句柄归零置空
                if (e is java.io.IOException || e.message?.contains("closed") == true) {
                    kadbInstance = null
                    isAdbAuthorized = false
                    updateStatus("连接已断开")
                }
            }
        }
    }
    /**
     * 🛰️ 智能处理 adb pair 无线配对命令 (第一生命周期)
     */
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
                    updateStatus("无线调试配对成功")
                    appendLog("[提示] ⚠️ 请查看电视上的【无线调试端口】，输入 adb connect [IP:端口] 唤醒数据总线。")
                    usbForwarder?.stop() // 断开有线转发，准备迎接纯无线
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[配对失败] 异常: ${e.message}") }
            }
        }
    }
    /**
     * 🛰️ 智能处理 adb connect 无线建链网络传输命令 (完全基于 KADB 2.1.1 规范)
     */
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
                    refreshUiText()
                    updateStatus("无线调试已连接")
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
    /**
     * 🚀 智能处理 adb push 命令 (带闪存文件夹自动补全)
     */
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

        appendLog("[Sync] 正在安全推送: ${localFile.name} -> $remotePath")
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("数据通道未建立")
                val finalRemotePath = if (remotePath.endsWith("/")) remotePath + localFile.name else remotePath

                // 🌟 2.x 升级：顶级类直接托管全周期 push
                kadb.push(src = localFile, remotePath = finalRemotePath)
                withContext(Dispatchers.Main) { appendLog("[成功] 文件已被推入远端: $finalRemotePath") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Push 失败] 传输崩塌: ${e.message}") }
            }
        }
    }
    /**
     * 📥 智能处理 adb pull 命令 (带闪存文件夹自动归档)
     */
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
                
                // 🌟 2.x 升级：顶级类直接托管全周期 pull
                kadb.pull(dst = localFile, remotePath = remotePath)
                withContext(Dispatchers.Main) { appendLog("[成功] 数据已沉淀至本地: ${localFile.absolutePath}") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[Pull 失败] 提取中止: ${e.message}") }
            }
        }
    }
    /**
     * 🔒 核心持久化存储：保存设备并与 WiFi 进行多路指纹绑定
     */
    private fun saveConnectedDevice(ip: String, port: Int) {
        val currentWifi = getCurrentWifiSsid()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
    /**
     * 🛰️ 全兼容环境 WiFi SSID 获取算法（完美适配 NEARBY_WIFI_DEVICES 权限）
     */
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

    private fun checkAndRequestWifiPermission(): Boolean {
        val targetPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, targetPermission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(targetPermission), REQUEST_WIFI_PERMISSION_CODE)
            return false
        }
        return true
    }
    
    private fun executeAutoWifiConnect() {
        val currentWifi = getCurrentWifiSsid()
        val allHistory = getAllSavedDevices()
        // 过滤出属于当前 WiFi 名字的所有历史机器
        val matchedDevices = allHistory.filter { it.wifiSsid == currentWifi }
        when {
            // 情况 1：无历史记录
            matchedDevices.isEmpty() -> {
                appendLog("[系统] 💡 当前 WiFi [$currentWifi] 无历史记录，等待手动输入")
            }
            // 情况 2：仅有一台 -> 依旧执行酷炫的后台无感秒连
            matchedDevices.size == 1 -> {
                val target = matchedDevices.first()
                appendLog("[系统] 📡 侦测到 WiFi [$currentWifi] 唯一历史设备，正在无感回连...")
                lifecycleScope.launch(Dispatchers.IO) {
                    handleLocalAdbConnect("adb connect ${target.ip}:${target.port}")
                }
            }
            // 情况 3：有多台 -> 🌟 华丽升级：拉起底部抽屉面板
            else -> {
                showDeviceSelectionBottomSheet(currentWifi, matchedDevices)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIFI_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (isWifiEnabled) {
                executeAutoWifiConnect()
            }
        }
    }
    
    private fun showDeviceSelectionBottomSheet(wifiName: String, devices: List<AdbDevice>) {
        // 1. 创建底部的 Dialog 实例
        val bottomSheetDialog = BottomSheetDialog(this)
        // 2. 动态构建一个垂直排列的根布局
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 3. 顶部的标题文字
        val titleView = TextView(this).apply {
            text = "📡 检测到当前 WiFi [$wifiName] 下有多个历史设备"
            textSize = 16f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 48) // 与下方列表隔开
        }
        rootLayout.addView(titleView)
        // 4. 准备列表展示的数据
        val items = devices.map { dev ->
            "📺 IP: ${dev.ip}:${dev.port}   (${getRelativeTimeString(dev.lastConnectedTime)})"
        }
        // 5. 构建列表组件
        val listView = ListView(this).apply {
            // 使用系统自带的简单列表样式
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, items)
            // 关键：点击某一项后触发无线连接，并关闭抽屉
            setOnItemClickListener { _, _, position, _ ->
                val selectedDevice = devices[position]
                appendLog("[系统] 用户从底部抽屉选择了设备: ${selectedDevice.ip}:${selectedDevice.port}")
                lifecycleScope.launch(Dispatchers.IO) {
                    handleLocalAdbConnect("adb connect ${selectedDevice.ip}:${selectedDevice.port}")
                }
                bottomSheetDialog.dismiss()
            }
        }
        rootLayout.addView(listView)
        // 6. 装载布局并优雅滑出
        bottomSheetDialog.setContentView(rootLayout)
        bottomSheetDialog.setCancelable(true)
        bottomSheetDialog.show()
    }
    /**
     * 🕒 辅助工具：将时间戳转换为人性化的相对时间提示（例如：刚刚、5分钟前）
     */
    private fun getRelativeTimeString(timeMs: Long): String {
        val diff = System.currentTimeMillis() - timeMs
        val minutes = diff / 1000 / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            else -> "${days}天前"
        }
    }
    
    private fun startFastbootReader() {
        readerJob?.cancel()
        readerJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive) {
                val read = usbConn?.bulkTransfer(epIn, buffer, buffer.size, 1000) ?: -1
                if (read > 0) {
                    val response = String(buffer, 0, read).trim()
                    withContext(Dispatchers.Main) { appendLog("FB >> $response") }
                    responseChannel.trySend(response)
                }
            }
        }
    }
    /**
     * 等待设备返回终端符号 (OKAY 或 FAIL)
     * Fastboot 协议中，INFO 包会连续发送，必须全部接收直到 OKAY
     */
    private suspend fun waitForTerminalResponse(
        timeout: Long = 10000, 
        onInfoReceived: (String) -> Unit
    ): FastbootResponse {
        val lines = mutableListOf<String>() // 🌟 建立全量日志收集箱
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val resp = withTimeoutOrNull(2000) { responseChannel.receive() } ?: continue
            lines.add(resp) // 🌟 每一行进来的原始数据都老老实实存进去
        
            if (resp.startsWith("OKAY") || resp.startsWith("FAIL")) {
                val status = resp.substring(0, 4)
                val payload = if (resp.length > 4) resp.substring(4) else ""
                return FastbootResponse(status, payload, lines) // 🌟 返回全量集合
            } else if (resp.startsWith("DATA")) {
                val payload = if (resp.length > 4) resp.substring(4) else ""
                return FastbootResponse("DATA", payload, lines) // 🌟 返回全量集合
            } else if (resp.startsWith("INFO")) {
                val infoPayload = if (resp.length > 4) resp.substring(4) else ""
                onInfoReceived(infoPayload) // 依旧保持实时的实时回调
            } else {
                onInfoReceived(resp)
            }
        }
        return FastbootResponse("TIMEOUT", "等待设备响应超时", lines)
    }

    private fun sendFastbootCommandDirect(command: String) {
        val data = command.toByteArray()
        usbConn?.bulkTransfer(epOut, data, data.size, 1000)
    }
    /**
     * 核心融合方法：执行任意 Fastboot 命令
     */
    suspend fun executeCommandSync(command: String) = withContext(Dispatchers.IO) {
        // 清理并拆分命令
        val cleanCmd = command.removePrefix("fastboot ").trim()
        if (cleanCmd.isEmpty()) return@withContext
        val parts = cleanCmd.split(Regex("\\s+"))
        val action = parts[0].lowercase()

        // 🌟 1. 【核心路由分流】判断是否需要通过 libfastboot.so 可执行文件来完成
        val requiresBinary = when {
            // 情况 A：参数以 "-" 开头（例如 -help, --version, -s 等参数），直接走二进制文件
            action.startsWith("-") -> true
            
            // 情况 B：你已经完美适配好协议的纯文本直连指令，走物理 USB 通道
            action == "getvar" || action == "oem" || action == "reboot" || action == "erase" || action == "flash" -> false
            
            // 情况 C：其余复杂的刷机、文件操作或未适配的命令（flash, boot, reboot, devices ），默认全部走二进制文件
            else -> true
        }

        if (requiresBinary) {
            // 🚀 【走二进制可执行文件独立进程分支】
           // executeViaFastbootBinary(parts)
            return@withContext
        }

        // 🔌 2. 【走原生的 USB 协议转换直连分支】（保持你原有的精良设计）
        val protocolCmd = when (action) {
            "getvar" -> {
                if (parts.size >= 2) "${parts[0]}:${parts.drop(1).joinToString(" ")}" else parts[0]
            }
            "oem" -> {
                cleanCmd 
            }
            "reboot" -> {
                cleanCmd 
            }
            "erase" -> {
                if (parts.size >= 2) "${parts[0]}:${parts.drop(1).joinToString(" ")}" else parts[0]
            }
            "flash" -> {
                if (parts.size >= 3) {
                    performFlash(parts[1], parts[2]) // 使用：fastboot flash boot /sdcard/boot.img
                } else {
                    appendLog("❌ 格式错误: flash <分区> <路径>")
                }
            }
            else -> {
                cleanCmd
            }
        }

        withContext(Dispatchers.Main) {
            appendLog("🚀 [USB直连] 发送指令: $protocolCmd")
        }

        sendFastbootCommandDirect(protocolCmd)

        // 3. 传入实时回调，刷新前台 UI
        val result = waitForTerminalResponse(10000) { infoText ->
            appendLog("FB << (bootloader) $infoText")
        }
        
        // 4. 原生通道最终状态结算
        withContext(Dispatchers.Main) {
            when (result.status) {
                "OKAY" -> appendLog("FB << OKAY [执行成功] ${result.payload}")
                "FAIL" -> appendLog("❌ [错误] 手机拒绝了该指令: ${result.payload}")
                "TIMEOUT" -> appendLog("⚠️ [超时] ${result.payload}")
            }
        }
    }
    /**
     * 执行完整的 Flash 事务
     * @param partition 分区名称 (例如 "boot", "system")
     * @param filePath 本地文件路径
     */
    suspend fun performFlash(partition: String, filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            withContext(Dispatchers.Main) { appendLog("❌ 错误: 找不到镜像文件 -> $filePath") }
            return@withContext
        }

        // 1. 预处理：判断是否需要特殊处理 (Sparse Image)
        // 如果是 Sparse Image 且设备不支持直接刷写，此处可插入转换逻辑
        val isSparse = isSparseImage(file)
        withContext(Dispatchers.Main) { appendLog("ℹ️ 格式识别: ${if (isSparse) "Sparse Image" else "Raw Image"}") }

        // 2. 握手阶段: download:<size>
        // 协议要求：size 必须是 8 位十六进制
        val sizeHex = String.format("%08x", file.length())
        withContext(Dispatchers.Main) { appendLog("🚀 开始下载: $partition (大小: ${file.length()} bytes)") }
    
        sendFastbootCommandDirect("download:$sizeHex")
    
        // 等待设备响应 DATA (只有收到 DATA 才能开始传数据)
        val handshake = waitForTerminalResponse(10000) { }
        if (handshake.status != "DATA") {
            withContext(Dispatchers.Main) { appendLog("❌ 拒绝下载: ${handshake.payload}") }
            return@withContext
        }

        // 3. 数据传输阶段 (流式循环)
        withContext(Dispatchers.Main) { appendLog("⏳ 正在传输数据，请勿断开连接...") }
        val buffer = ByteArray(65536) // 64KB 缓冲区
        try {
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    // 使用 bulkTransfer 循环发送
                    val written = usbConn?.bulkTransfer(epOut, buffer, bytesRead, 5000) ?: -1
                    if (written != bytesRead) {
                        throw Exception("USB 传输中断 (发送字节数不匹配)")
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { appendLog("❌ 传输数据失败: ${e.message}") }
            return@withContext
        }

        // 4. 等待下载确认 (OKAY)
        val downloadConfirm = waitForTerminalResponse(30000) { }
        if (downloadConfirm.status != "OKAY") {
            withContext(Dispatchers.Main) { appendLog("❌ 下载被拒绝: ${downloadConfirm.payload}") }
            return@withContext
        }

        // 5. 触发刷写阶段: flash:<partition>
        withContext(Dispatchers.Main) { appendLog("⚡ 触发刷写: flash:$partition") }
        sendFastbootCommandDirect("flash:$partition")
    
        // 6. 最终结算 (长超时)
        // 刷写过程设备会频繁返回 INFO，我们通过回调实时打印
        val flashResult = waitForTerminalResponse(120000) { info ->
            withContext(Dispatchers.Main) { appendLog("FB << (bootloader) $info") }
        }

        withContext(Dispatchers.Main) {
            if (flashResult.status == "OKAY") {
                appendLog("✅ [成功] 分区 $partition 刷写完成")
            } else {
                appendLog("❌ [失败] 分区 $partition 刷写失败: ${flashResult.payload}")
            }
        }
    }

    private fun isSparseImage(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val SPARSE_HEADER_MAGIC = 0xED26FF3A.toInt() // 小端序 Magic

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buffer = ByteArray(4)
                raf.readFully(buffer)
                val magic = ByteBuffer.wrap(buffer)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                magic == SPARSE_HEADER_MAGIC
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun refreshUiText() {
        runOnUiThread {
            // 匹配要求：状态：USB 已连接，XXX
            val status = when {
            //    isAdbWifiAuthorized -> "状态：WiFi 无线调试已连接"
                isFastbootMode -> "状态：USB 已连接，Fastboot模式"
                isUsbAttached && isAdbAuthorized -> "状态：USB 已连接，ADB已授权"
                isUsbAttached -> "状态：USB 已连接，ADB未授权"
                else -> "状态：未连接"
            }
            binding.tvStatus.text = status
            val statusColor = if (isAdbAuthorized || isFastbootMode) {
                ContextCompat.getColor(this, R.color.status_connected_green)
            } else {
                ContextCompat.getColor(this, R.color.status_disconnected_red)
            }
            binding.tvStatus.setTextColor(statusColor)
            binding.appMainActivity.flashAll.isEnabled = isFastbootMode
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = "状态: $msg"; appendLog("[系统] $msg") }
    }

    private fun appendLog(msg: String) {
        val current = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
        val time = current.format(formatter)
        runOnUiThread {
            binding.appMainActivity.tvLog.append(time + "\u0020" + msg + "\n")
            binding.appMainActivity.scrollView.post {
            // fullScroll 会直接滑动到最底部，确保你能看到最新的 [流结束] 或命令输出
            binding.appMainActivity.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
            val offset = binding.appMainActivity.tvLog.lineCount * binding.appMainActivity.tvLog.lineHeight
            if (offset > binding.appMainActivity.tvLog.height) binding.appMainActivity.tvLog.scrollTo(0, offset - binding.appMainActivity.tvLog.height)
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
