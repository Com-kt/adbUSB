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

import android.Manifest
import android.util.Log
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.TextView
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
import kotlin.ExperimentalUnsignedTypes
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import java.io.File
import java.io.FileWriter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private var kadbInstance: Kadb? = null
    private var usbForwarder: UsbPortForwarder? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false 
    private var isFirstTryInThisSession = true

    private val responseChannel = Channel<String>(Channel.CONFLATED)
    private val turbo by lazy { PerformanceTurbo(this) }
    
    @Volatile
    private var activeBinaryProcess: Process? = null
    
    private val logPipeline = Channel<String>(Channel.UNLIMITED)
    
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }
    
    private val REQUEST_WIFI_PERMISSION_CODE = 99
    private val PREFS_NAME = "AdbMultiDevicePrefs"
    private val KEY_DEVICE_LIST = "device_list"

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
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
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
        
        if (checkAndRequestWifiPermission()) {
            executeAutoWifiConnect()
        }
        
        binding.appMainActivity.btnConnect.setOnClickListener { findHostDevice() }
        
        binding.appMainActivity.ipTest.setOnClickListener { IpTestWork() }
        
        binding.appMainActivity.flashAll.setOnClickListener { startDualChannelFlashEngine() }
        
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
              val qrText = "adb-test:qr_code_data_here"
              val pairingCode = "123456"
              val dialogFragment = AdbQrDialogFragment.newInstance(qrText, pairingCode)
              dialogFragment.show(supportFragmentManager, "AdbQrDialog")
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
                sendAdbShell(selectedCommand)
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
                    appendLog("--- 需要开启USB调试 (安全设置) ---")

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
            isAdbAuthorized = false
            appendLog("[系统] 正在建立虚拟本地有线网络转发桥接...")

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
    fun sendAdbShell(command: String) {
        appendLog("ADB >> $command")
        val cleanCmd = command.removePrefix("adb shell ").trim()
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val kadb = kadbInstance ?: throw IllegalStateException("通道连接未就绪")
                
                // 🌟 2.x 核心升级：直接通过高阶内聚函数获取全量响应，无需处理 source
                val response = kadb.shell(cleanCmd)
                
                withContext(Dispatchers.Main) {
                    appendLog(response.allOutput)
                    appendLog("[流结束]")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[Shell 异常] 传输阻断: ${e.message}")
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
                    appendLog("[提示] ⚠️ 请查看电视上的【无线调试端口】，输入 adb connect [IP:端口] 唤醒数据总线。")
                    usbForwarder?.stop() // 断开有线转发，准备迎接纯无线
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[配对失败] 异常: ${e.message}") }
            }
        }
    }
    /**
     * 🛰️ 智能处理 adb connect 无线建链网络传输命令 (第二生命周期)
     */
    private fun handleLocalAdbConnect(command: String) {
        appendLog("[无线] 执行 >> $command")
        val parts = command.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (parts.size < 3) {
            appendLog("[错误] 请使用: adb connect [IP:无线调试端口]")
            return
        }

        val target = parts[2]
        val hostPort = target.split(":")
        if (hostPort.size != 2) {
            appendLog("[错误] IP与端口格式错误")
            return
        }

        val ip = hostPort[0]
        val port = hostPort[1].toInt()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { appendLog("[无线] 正在唤醒远端网络数据通道...") }
                usbForwarder?.stop()

                // 创建纯网络形式的 KADB 总线实例
                kadbInstance = Kadb.create(host = ip, port = port)
                val isConnected = kadbInstance!!.connectionCheck()

                withContext(Dispatchers.Main) {
                    if (isConnected) {
                        isAdbAuthorized = true
                        refreshUiText()
                        appendLog(">>> 👍 无线调试通道连通成功！支持命令与推拉。 <<<")
                        
                        // 归档至当前 WiFi 专属存储列表
                        saveConnectedDevice(ip, port)
                    } else {
                        appendLog("[警告] 数据通道连接返回异常状态")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[连接失败] 远端网络拒绝建立链路: ${e.message}")
                }
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
        prefs.edit().putString(KEY_DEVICE_LIST, jsonArray.toString()).apply()
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiInfo = capabilities.transportInfo as? WifiInfo
                    if (wifiInfo != null) {
                        val ssid = wifiInfo.ssid.replace("\"", "")
                        if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) return ssid
                    }
                }
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo.ssid.replace("\"", "")
            if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) return ssid
        } catch (e: Exception) { Log.e("adbKitty", "获取无线SSID受限", e) }
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
        val matchedDevices = allHistory.filter { it.wifiSsid == currentWifi }
        
        if (matchedDevices.isNotEmpty()) {
            val targetDevice = matchedDevices.first()
            appendLog("[系统] 📡 侦测到 WiFi [$currentWifi] 历史专属设备: ${targetDevice.ip}:${targetDevice.port}")
            appendLog("[系统] 🚀 正在执行智能无感自动回连...")
            handleLocalAdbConnect("adb connect ${targetDevice.ip}:${targetDevice.port}")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIFI_PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            executeAutoWifiConnect()
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
            action == "getvar" || action == "oem" || action == "erase" -> false
            
            // 情况 C：其余复杂的刷机、文件操作或未适配的命令（flash, boot, reboot, devices ），默认全部走二进制文件
            else -> true
        }

        if (requiresBinary) {
            // 🚀 【走二进制可执行文件独立进程分支】
            executeViaFastbootBinary(parts)
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
            "erase" -> {
                if (parts.size >= 2) "${parts[0]}:${parts.drop(1).joinToString(" ")}" else parts[0]
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
     * 🌟 混合驱动核心：调用打包在 jniLibs 中的 libfastboot.so 执行未适配或复杂的命令
     */
    private suspend fun executeViaFastbootBinary(args: List<String>) = withContext(Dispatchers.IO) {
        val nativeDir = applicationInfo.nativeLibraryDir
        val fastbootFile = File(nativeDir, "libfastboot.so")

        // 安全检查
        if (!fastbootFile.exists()) {
            withContext(Dispatchers.Main) {
                appendLog("❌ 错误：未找到 libfastboot.so 可执行文件。")
            }
            return@withContext
        }

        // 再次确保工作目录是安全的
        ensureFlashDirExists()

        // 组装要在 Linux 进程里跑的命令
        val command = mutableListOf<String>().apply {
            add(fastbootFile.absolutePath)
            addAll(args)
        }

        withContext(Dispatchers.Main) {
            appendLog("📂 [工作目录]: ${flashFolder.absolutePath}")
            appendLog("🚀 [独立进程] 执行命令: fastboot ${args.joinToString(" ")}")
        }

        try {
            val processBuilder = ProcessBuilder(command)
            processBuilder.directory(flashFolder)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            // 实时读取进程产生的每一行输出并回传给 UI 界面
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line
                if (currentLine != null) {
                    withContext(Dispatchers.Main) {
                        appendLog("FB(Bin) << $currentLine")
                    }
                }
            }

            // 阻塞等待进程彻底完成并获取退出状态码
            val exitCode = process.waitFor()
            withContext(Dispatchers.Main) {
                if (exitCode == 0) {
                    appendLog("🏁 [进程结束] 执行成功 (code: $exitCode)")
                } else {
                    appendLog("⚠️ [进程结束] 执行可能失败或被中断 (code: $exitCode)")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                appendLog("💥 [独立进程异常]: ${e.message}")
            }
        }
    }
    
    fun startDualChannelFlashEngine() {
        // 通道 1：UI 消费端（全速从无限队列中抓取日志，安全地在主线程渲染）
        lifecycleScope.launch(Dispatchers.Main) {
            appendLog("⏳ [全速通道开启] 正在初始化原生日志流监听...")
            for (logLine in logPipeline) {
                appendLog(logLine) 
            }
        }

        // 通道 2：独立进程生产端（专心线刷，全速读流，只在分区完毕时做物理休眠）
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                flashAllEngine()
            } finally {
                logPipeline.close() 
            }
        }
    }
    /**
     * 🌟 纯粹的线刷核心引擎（零阻碍日志吞吐，分区级物理减压）
     */
    private suspend fun flashAllEngine() {
        val nativeDir = applicationInfo.nativeLibraryDir
        val sourceFastbootFile = File(nativeDir, "libfastboot.so")
        val scriptFile = File(flashFolder, "flash_all.sh")
        val targetFastbootFile = File(flashFolder, "fastboot")

        if (!sourceFastbootFile.exists() || !scriptFile.exists()) {
            logPipeline.send("❌ [线刷失败]: 缺少必需的依赖文件。")
            return
        }

        try {
            if (!targetFastbootFile.exists()) {
                sourceFastbootFile.copyTo(targetFastbootFile, overwrite = true)
                Runtime.getRuntime().exec("chmod 700 ${targetFastbootFile.absolutePath}").waitFor()
            }
            Runtime.getRuntime().exec("chmod 700 ${scriptFile.absolutePath}").waitFor()

            val inlineCommand = "alias fastboot='${targetFastbootFile.absolutePath}'; sh ${scriptFile.absolutePath}"
            val processBuilder = ProcessBuilder(listOf("sh", "-c", inlineCommand)).apply {
                directory(flashFolder)
                redirectErrorStream(true)
            }

            logPipeline.send("⚙️ [引擎启动] 正在唤醒底层 Linux 独立进程...")
            val process = processBuilder.start()
            activeBinaryProcess = process

            // 1KB 高速缓冲区
            val reader = BufferedReader(InputStreamReader(process.inputStream, "UTF-8"), 1024)
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue

                // 🌟 核心行为：秒读秒发！由于通道无限制，send() 永远不会在这里发生任何阻塞
                logPipeline.send("SH_SCRIPT >> $currentLine")

                // 🌟 保留核心物理防线：当遇到官方脚本的 "OKAY" 时，说明上一个分区写入成功了
                // 在这个空档，我们主动给硬件和进程 15ms 的休眠时间。
                // 这不是为了限流日志，而是为了防止持续刷大文件导致物理 USB 缓存过载或手机主板过热。
                if (currentLine.contains("OKAY")) {
                    logPipeline.send("♻️ [分区刷写完毕] 物理链路短暂挂起降温...")
                    delay(15) 
                    System.out.println() // 触发一次轻量底层释放
                }
            }

            val exitCode = process.waitFor()
            if (exitCode == 0) {
                logPipeline.send("🎉 【线刷大成功】: 官方 flash_all.sh 脚本已全面顺利跑通！")
            } else {
                logPipeline.send("🛑 【线刷中断】: 底层返回了非零状态码: $exitCode")
            }

        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                logPipeline.send("⚠️ [生命周期安全介入]: 线刷任务已被外部物理切断。")
            }
        } catch (e: Exception) {
            logPipeline.send("💥 [进程异常]: ${e.message}")
        } finally {
            cleanupActiveProcess()
            System.gc()
        }
    }
    
    private fun cleanupActiveProcess() {
        activeBinaryProcess?.let { process ->
            try { process.destroy() } catch (e: Exception) { e.printStackTrace() }
            activeBinaryProcess = null
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
            binding.tvStatus.setTextColor(if (isAdbAuthorized || isFastbootMode) Color.GREEN else Color.RED)
            binding.appMainActivity.flashAll.isEnabled = isFastbootMode
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = "状态：$msg"; appendLog("[系统] $msg") }
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
        cleanupActiveProcess()
        super.onDestroy()
        readerJob?.cancel()
        usbConn?.close()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbStateReceiver)
        inspector.unbindRootService()
        usbForwarder?.stop()
        runCatching { kadbInstance?.close() }
    }
}
