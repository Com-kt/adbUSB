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
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.Menu
import android.view.MenuItem
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbAccessory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
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
import kotlin.ExperimentalUnsignedTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import javax.crypto.Cipher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class AdbCommand(val description: String, val command: String)

data class FbCommand(val description: String, val command: String)

data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

class MainActivity : AppCompatActivity(), OnPairingListener {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager
    private lateinit var keyManager: AdbKeyManager
    private val ACTION_USB_PERMISSION = "com.adb.kitty.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var rsaKeyPair: KeyPair? = null
    private var readerJob: Job? = null
    private var mLocalId = 1
    private var accessoryPfd: ParcelFileDescriptor? = null

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false 
    private var authFailureCount = 0
    
    private var adbClient: AdbWifiClient? = null
    private var nsdManager: NsdManager? = null
    private var localChannelId = 1
    private var isAdbWifiAuthorized = false
    
    private val responseChannel = Channel<String>(Channel.CONFLATED)
    
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
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
                        connectToAccessory(accessory) // 指向处理配件的新逻辑
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
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            appendLog("[权限] 附近 Wi-Fi 设备权限已授予，开始扫描服务...")
            startScanningForAdbConnect()
        } else {
            appendLog("[警告] 用户拒绝了附近设备权限，无法自动发现无线调试服务！")
            Toast.makeText(this, "需要该权限才能自动连接无线调试", Toast.LENGTH_LONG).show()
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
        
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager
        ensureFlashDirExists()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        keyManager = AdbKeyManager(this)

        lifecycleScope.launch(Dispatchers.IO) {
            rsaKeyPair = keyManager.getKeys()
        }

        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), exportFlag)
        registerReceiver(usbStateReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }, exportFlag)

        binding.appMainActivity.btnConnect.setOnClickListener { findDevice() }
        /*
        binding.appMainActivity.btnSend.setOnClickListener {
            val cmd = binding.appMainActivity.etCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            binding.appMainActivity.etCommand.setText("")

            if (isFastbootMode) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
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
        }
        */
        binding.appMainActivity.btnSend.setOnClickListener {
            val cmd = binding.appMainActivity.etCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            binding.appMainActivity.etCommand.setText("")

            if (isFastbootMode) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        executeCommandSync(cmd)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { appendLog("[错误] ${e.message}") }
                    }
                }
            } else if (isAdbAuthorized) {
                sendAdbShell(cmd)
            } else if (isAdbWifiAuthorized) {
                sendAdbWifiShell(cmd)
            } else {
                Toast.makeText(this, "设备未就绪或未授权", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.appMainActivity.fbSelinux.setOnClickListener {
            if (!isFastbootMode) {
                 Toast.makeText(this, "当前不是 Fastboot 模式", Toast.LENGTH_SHORT).show()
               return@setOnClickListener
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
              val dialog = AdbPairingDialogFragment()
              dialog.setOnPairingListener(this)
              dialog.show(supportFragmentManager, "AdbPairingDialog")
                true
           }
              R.id.action_main_4 -> {
              WarngApps()
                true
           }
             else -> super.onOptionsItemSelected(item)
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
    /* 
    private fun findDevice() {
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
    */
    /**
     * 核心：双路扫描（同时查找设备和配件）
     */
    private fun findDevice() {
        // 1. 扫描 Host 模式 (手机控别人)
        val devices = usbManager.deviceList
        for (device in devices.values) {
            appendLog("设备: ${device.productName ?: "未知"}")
            appendLog("制造商: ${device.manufacturerName ?: "未知"}")
            appendLog("版本号: ${device.version}")
            appendLog("VID: ${device.vendorId} | PID: ${device.productId}")
            if (usbManager.hasPermission(device)) {
                if (device.vendorId == 6353 && device.productId in 0x2D00..0x2D05) {
                    processAccessoryMode(device)
                    if (device.productId % 2 != 0) processHostMode(device)
                } else {
                    processHostMode(device)
                }
            }
        }
        // 2. 扫描 Accessory 模式 (手机连电脑/被控)
        val accessories = usbManager.accessoryList
        accessories?.forEach { accessory ->
            if (usbManager.hasPermission(accessory)) {
                appendLog("检测到已授权的配件: ${accessory.model}")
                connectToAccessory(accessory)
            }
        }
        
        if (devices.isEmpty() && accessories.isNullOrEmpty()) {
            updateStatus("未发现 USB 连接")
        }
    }
    /**
     * 原有逻辑：主机模式 (ADB/Fastboot)
      */
    private fun processHostMode(device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            appendLog("接口名称: ${intf.name ?: "无描述"}")
            appendLog("检查接口 $i: Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Protocol=${intf.interfaceProtocol}")

            // 遍历端点 (Endpoint) - 保持原有输出
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                val isInput = (ep.address and 0x80) != 0
                val direction = if (isInput) "IN (设备->手机)" else "OUT (手机->设备)"
                val epNumber = ep.address and 0x0F
                appendLog("端点 $j: 地址=${ep.address} (方向: $direction, 编号: $epNumber), 最大包大小=${ep.maxPacketSize}")
            }
            appendLog("--- 通过USB连接输出 ---")
        
            // 核心匹配逻辑
            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66) {
                isFastbootMode = (intf.interfaceProtocol == 3)
                isUsbAttached = true

                val modeName = if (isFastbootMode) "Fastboot" else "ADB"
                appendLog("--- 检测到 $modeName 兼容设备 ---")
                appendLog("--- ADB 模式下需要开启USB调试 (安全设置) ---")

                if (!usbManager.hasPermission(device)) {
                    // --- 修复 Android 14 崩溃的关键点 (保持不动) ---
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
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
                return // 找到匹配接口后退出
            }
        }
        updateStatus("发现设备但无 ADB/Fastboot 接口")
    }
    /**
     * 专项处理：输出 USB 配件模式 (AOA) 的所有关键身份信息
     */
    private fun processAccessoryMode(device: UsbDevice) {
        appendLog(">>> [检测到 USB 配件模式 (AOA)] <<<")
        // 1. 输出 AOA 握手定义的核心身份信息
        // 当手机进入 AOA 模式，这些字段会显示为你发送给手机的握手字符串
        appendLog("【AOA 身份标识】")
        appendLog(" -> 制造商 (Manufacturer): ${device.manufacturerName ?: "未提供"}")
        appendLog(" -> 型号 (Model): ${device.productName ?: "未提供"}")
        appendLog(" -> 协议版本 (Version): ${device.version.ifBlank { "未知" }}")

        // 2. 根据 PID 细化模式描述
        val aoaType = when (device.productId) {
            0x2D00 -> "Accessory (仅配件)"
            0x2D01 -> "Accessory + ADB (复合模式)"
            0x2D02 -> "Audio (仅音频)"
            0x2D03 -> "Audio + ADB"
            0x2D04 -> "Accessory + Audio"
            0x2D05 -> "Accessory + Audio + ADB"
            else -> "未知 AOA 状态"
        }
        appendLog("【当前模式】$aoaType (PID: 0x${Integer.toHexString(device.productId).uppercase()})")

        // 3. 遍历接口，识别 AOA 特有通道
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            when {
                intf.interfaceClass == 255 && intf.interfaceSubclass == 255 -> {
                    appendLog("  └ [接口 $i] 关键通道: AOA Accessory Bulk Data")
                }
                intf.interfaceClass == 1 -> {
                    appendLog("  └ [接口 $i] 音频通道: USB Digital Audio Out")
                }
                intf.interfaceClass == 255 && intf.interfaceSubclass == 66 -> {
                    appendLog("  └ [接口 $i] 调试通道: ADB Tunnel over AOA")
                }
            }
        }
        appendLog(">>> [AOA 诊断输出完毕] <<<")
    }
    /**
     * 新增：配件模式连接逻辑 (FileDescriptor 模式)
     */
    private fun connectToAccessory(accessory: UsbAccessory) {
        try {
            accessoryPfd = usbManager.openAccessory(accessory)
            accessoryPfd?.let { pfd ->
                val inputStream = FileInputStream(pfd.fileDescriptor)
                val outputStream = FileOutputStream(pfd.fileDescriptor)
                
                appendLog("[系统] 配件模式流已开启")
                
                // 启动异步线程处理 ADB 协议数据流
                Thread {
                    val buffer = ByteArray(16384)
                    try {
                        while (isUsbAttached) {
                            val len = inputStream.read(buffer)
                            if (len > 0) {
                                // 这里接入你的 AdbKeyManager 签名或协议解析逻辑
                                appendLog("收到配件模式数据: $len bytes")
                            }
                        }
                    } catch (e: IOException) {
                        appendLog("配件流读取关闭")
                    }
                }.start()
            }
        } catch (e: Exception) {
            appendLog("开启配件模式失败: ${e.message}")
        }
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
            isAdbAuthorized = true 
            refreshUiText()
            startFastbootReader()
            appendLog("[系统] Fastboot 链路已就绪")
        } else {
            isAdbAuthorized = false
            startAdbReader()
            lifecycleScope.launch(Dispatchers.IO) {
                val banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,openscreen_mdns,compression_zstd\u0000".toByteArray(Charsets.UTF_8)
                sendPacket(0x4e584e43, 0x01000001, 262144, banner)
            }
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
    private suspend fun waitForTerminalResponse(timeout: Long = 10000): FastbootResponse {
        val lines = mutableListOf<String>()
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val resp = withTimeoutOrNull(2000) { responseChannel.receive() } ?: continue
            lines.add(resp)
            
            if (resp.startsWith("OKAY") || resp.startsWith("FAIL")) {
                val status = resp.substring(0, 4)
                val payload = if (resp.length > 4) resp.substring(4) else ""
                return FastbootResponse(status, payload, lines)
            }
        }
        return FastbootResponse("TIMEOUT", "", lines)
    }

    private fun sendFastbootCommandDirect(command: String) {
        val data = command.toByteArray()
        usbConn?.bulkTransfer(epOut, data, data.size, 1000)
    }
    
    suspend fun executeCommandSync(command: String) = withContext(Dispatchers.IO) {
        // 1. 预处理：移除前缀并按空格拆分参数
        val cleanCmd = command.removePrefix("fastboot ").trim()
        val parts = cleanCmd.split(Regex("\\s+"))
        if (parts.isEmpty()) return@withContext

        // 提取动作（如 flash, getvar, erase 等）
        val action = parts[0].lowercase()

        // 2. 分流处理
        if (action == "flash" || action == "boot") {
            // 针对刷写流程，直接进入 Workflow
            // handleFlashWorkflow 内部会通过 parts[1] 提取分区名并拼接成 "flash:分区名"
            handleFlashWorkflow(cleanCmd)
        } else {
            // 3. 普通指令处理逻辑
            // 按照协议格式：第一个空格转为冒号，后续保持原样
            // 例如 "getvar version-bootloader" -> "getvar:version-bootloader"
            // 例如 "reboot" -> "reboot"
            val protocolCmd = if (parts.size >= 2) {
                "${parts[0]}:${parts.drop(1).joinToString(" ")}"
            } else {
                parts[0]
            }

            sendFastbootCommandDirect(protocolCmd)
        
            // 等待响应，并在 UI 实时回显所有 INFO 和最终结果
            val result = waitForTerminalResponse(10000)
        
            withContext(Dispatchers.Main) {
                // 打印所有返回行（确保连续的 INFO 包不被遗漏）
                result.allLines.forEach { line ->
                    appendLog("FB << $line")
                }
            
                if (result.status == "FAIL") {
                    appendLog("❌ [错误] 执行失败: ${result.payload}")
                }
            }
        }
    }
/*
    private suspend fun handleFlashWorkflow(fullCommand: String) {
        // 1. 解析命令：预估格式为 "flash <partition> <fileName>"
        val parts = fullCommand.trim().split(Regex("\\s+"))
        if (parts.size < 3) {
            withContext(Dispatchers.Main) { 
                appendLog("❌ [错误] 命令格式不全。示例: flash <分区名> <文件名>") 
            }
            return
        }

        val partition = parts[1]        // 获取分区名
        val fileName = parts.last()     // 获取文件名
        val imgFile = File(flashFolder, fileName)

        if (!imgFile.exists()) {
            withContext(Dispatchers.Main) { appendLog("❌ [错误] 找不到文件: $fileName") }
            return
        }

        val fileSize = imgFile.length()
        val fileSizeMB = fileSize / 1024 / 1024

        try {
            // --- 第一阶段: Download (握手与数据传输) ---
            // 发送 download 指令告知设备即将传输的数据大小
            val downloadCmd = "download:${String.format("%08x", fileSize)}"
            sendFastbootCommandDirect(downloadCmd)
        
            // 等待设备返回 DATA 包（确认已准备好接收指定大小的数据）
            val dataResp = waitForTerminalResponse(10000)
            if (dataResp.status != "DATA") {
                throw Exception("设备拒绝接收数据 (响应: ${dataResp.status} ${dataResp.payload})")
            }
            // 开始 Bulk 传输二进制文件
            val buffer = ByteArray(512 * 1024) // 512KB 缓冲区
            FileInputStream(imgFile).use { input ->
                var totalSent = 0L
                while (totalSent < fileSize) {
                    val readSize = input.read(buffer)
                    if (readSize <= 0) break
                
                    val result = usbConn?.bulkTransfer(epOut, buffer, readSize, 60000)
                    if (result == -1) throw Exception("USB 传输中断，请检查线缆连接")
                
                    totalSent += readSize
                    // 每 5MB 或结束时更新一次进度
                    if (totalSent % (5 * 1024 * 1024) == 0L || totalSent == fileSize) {
                        val progress = (totalSent * 100 / fileSize).toInt()
                        withContext(Dispatchers.Main) {
                            appendLog("传输进度: $progress% ($fileSizeMB MB)")
                        }
                    }
                }
            }
            // 关键：传输完成后，必须等待设备返回 OKAY 确认数据已完整存入内存
            val uploadConfirm = waitForTerminalResponse(Math.max(30000L, (fileSizeMB / 100) * 5000L))
            if (uploadConfirm.status != "OKAY") {
                throw Exception("镜像上传校验失败: ${uploadConfirm.payload}")
            }
            // --- 第二阶段: Flash (物理写入) ---
            // 使用解析出的 partition 动态拼接指令
            sendFastbootCommandDirect("flash:$partition")
            withContext(Dispatchers.Main) { appendLog("正在写入物理分区 [$partition]，请勿断开连接...") }

            // 根据文件大小动态计算写入超时（最慢写入速度预估为 10MB/s + 60s 缓冲）
            val flashTimeout = (fileSizeMB / 10) * 1000L + 60000L
            val flashResult = waitForTerminalResponse(flashTimeout)

            // 统一处理 INFO 消息回显并判定结果
            withContext(Dispatchers.Main) {
                // 将过程中所有 INFO 包内容输出
                flashResult.allLines.filter { it.startsWith("INFO") }.forEach { line ->
                    appendLog("[设备] ${if (line.length > 4) line.substring(4) else ""}")
                }

                if (flashResult.status == "OKAY") {
                    appendLog("✅ [成功] $partition 分区刷写完成")
                } else {
                    appendLog("❌ [失败] 写入中断: ${flashResult.payload}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("⚠️ [异常] 流程终止: ${e.message}")
            }
        }
    }
    */
    private suspend fun handleFlashWorkflow(fullCommand: String) {
        // 1. 解析命令：预估格式为 "flash <partition> <fileName>" 或 "boot <fileName>"
        val parts = fullCommand.trim().split(Regex("\\s+"))
    
        // 基础动作提取 (flash 或 boot)
        val action = parts[0].lowercase()

        // 参数数量校验
        if (action == "flash" && parts.size < 3) {
            withContext(Dispatchers.Main) { appendLog("❌ [错误] flash 格式不全。示例: flash <分区名> <文件名>") }
            return
        }
        if (action == "boot" && parts.size < 2) {
            withContext(Dispatchers.Main) { appendLog("❌ [错误] boot 格式不全。示例: boot <文件名>") }
            return
        }

        val partition = if (action == "flash") parts[1] else "" // 只有 flash 需要分区名
        val fileName = parts.last() // 无论哪种模式，最后一个参数通常是文件名
        val imgFile = File(flashFolder, fileName)

        if (!imgFile.exists()) {
            withContext(Dispatchers.Main) { appendLog("❌ [错误] 找不到文件: $fileName") }
            return
        }

        val fileSize = imgFile.length()
        val fileSizeMB = fileSize / 1024 / 1024

        try {
            // --- 第一阶段: Download (握手与数据传输) ---
            val downloadCmd = "download:${String.format("%08x", fileSize)}"
            sendFastbootCommandDirect(downloadCmd)
    
            val dataResp = waitForTerminalResponse(10000)
            if (dataResp.status != "DATA") {
                throw Exception("设备拒绝接收数据 (响应: ${dataResp.status} ${dataResp.payload})")
            }

            // 数据 Bulk 传输逻辑 (保持不变)
            val buffer = ByteArray(512 * 1024)
            FileInputStream(imgFile).use { input ->
                var totalSent = 0L
                while (totalSent < fileSize) {
                    val readSize = input.read(buffer)
                    if (readSize <= 0) break
                    val result = usbConn?.bulkTransfer(epOut, buffer, readSize, 60000)
                    if (result == -1) throw Exception("USB 传输中断")
                    totalSent += readSize
                    if (totalSent % (5 * 1024 * 1024) == 0L || totalSent == fileSize) {
                        val progress = (totalSent * 100 / fileSize).toInt()
                        withContext(Dispatchers.Main) { appendLog("传输进度: $progress% ($fileSizeMB MB)") }
                    }
                }
            }
            // 等待传输确认
            val uploadConfirm = waitForTerminalResponse(Math.max(30000L, (fileSizeMB / 100) * 5000L))
            if (uploadConfirm.status != "OKAY") {
                throw Exception("镜像上传校验失败: ${uploadConfirm.payload}")
            }
            // --- 第二阶段: 关键改进 - 执行 Action ---
            // 严格根据 action 类型分流，不再有硬编码的 ":boot"
            if (action == "boot") {
                sendFastbootCommandDirect("boot")
                withContext(Dispatchers.Main) { appendLog("数据已就绪，正在执行临时引导 [boot]...") }
            } else {
            // 只有 action 为 flash 时，才拼接分区名
                sendFastbootCommandDirect("flash:$partition")
                withContext(Dispatchers.Main) { appendLog("正在写入物理分区 [$partition]，请勿断开连接...") }
            }
            // 根据文件大小计算写入/校验超时
            val flashTimeout = (fileSizeMB / 10) * 1000L + 60000L
            val flashResult = waitForTerminalResponse(flashTimeout)

            // 统一处理 INFO 消息回显并判定结果
            withContext(Dispatchers.Main) {
                flashResult.allLines.filter { it.startsWith("INFO") }.forEach { line ->
                    appendLog("[设备] ${if (line.length > 4) line.substring(4) else ""}")
                }

                if (flashResult.status == "OKAY") {
                    val successText = if (action == "boot") "引导指令已发出" else "$partition 分区刷写完成"
                    appendLog("✅ [成功] $successText")
                } else {
                    appendLog("❌ [失败] ${action.uppercase()} 失败: ${flashResult.payload}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                appendLog("⚠️ [异常] 流程终止: ${e.message}")
            }
        }
    }

    private fun startAdbReader() {
        readerJob?.cancel()
        readerJob = lifecycleScope.launch(Dispatchers.IO) {
            val header = ByteArray(24)
           // var stepAuthSent = false
            while (isActive) {
                val read = usbConn?.bulkTransfer(epIn, header, 24, 2000) ?: -1
                if (read < 24) continue
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int; val arg0 = bb.int; val arg1 = bb.int; val len = bb.int
                val payload = if (len > 0) ByteArray(len).also { usbConn?.bulkTransfer(epIn, it, len, 2000) } else null
                val keyPair = keyManager.getKeys()
                
                withContext(Dispatchers.Main) {
                    when (cmd) {
                        0x48545541 -> {
                            if (arg0 == 1) {
                                if (authFailureCount < 1) {
                                    appendLog("[Auth] 尝试私钥签名响应...")
                                    payload?.let { token ->
                                        val signature = keyManager.signAdbToken(token, keyPair.private)
                                        sendPacket(0x48545541, 2, 0, signature)
                                        authFailureCount++
                                    } ?: appendLog("[Error] 收到 AUTH TOKEN 但 payload 为空")
                                } else {
                                    appendLog("[Auth] 发送公钥申请授权...")
                                    val pubPayload = keyManager.getAdbAuthPayload()
                                    sendPacket(0x48545541, 3, 0, pubPayload)
                                }
                            }
                        }
                        0x4e584e43 -> { 
                            isAdbAuthorized = true
                            authFailureCount = 0
                            refreshUiText()
                            appendLog(">>> ADB 授权成功，链路就绪 <<<")
                        }
                        0x45545257 -> { 
                            appendLog(String(payload ?: byteArrayOf()))
                            sendPacket(0x59414b4f, arg1, arg0, null) 
                        }
                        0x45534c43 -> { // CLSE: 提醒流结束
                            appendLog("[流结束]")
                            sendPacket(0x45534c43, arg1, arg0, null)
                        }
                    }
                }
            }
        }
    }

    private fun sendAdbShell(command: String) {
        appendLog("ADB >> $command")
        lifecycleScope.launch(Dispatchers.IO) {
            val cleanCmd = command.removePrefix("adb shell ").trim()
            val data = "shell:$cleanCmd\u0000".toByteArray()
            sendPacket(0x4e45504f, mLocalId++, 0, data)
        }
    }
    
    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val len = payload?.size ?: 0
        val checksum = 0
        
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum).putInt(cmd xor -1)

        lifecycleScope.launch(Dispatchers.IO) {
            usbConn?.bulkTransfer(epOut, buffer.array(), 24, 1000)
            if (len > 0 && payload != null) {
                usbConn?.bulkTransfer(epOut, payload, len, 1000)
            }
        }
    }
    /**
     * 新扩展的无线调试 ADB 发送命令机制（网络 Socket 流）
     */
    private fun sendAdbWifiShell(cmd: String) {
        val client = adbClient
        if (client == null || !isAdbWifiAuthorized) {
            appendLog("[提示] 无线 ADB 客户端不可用或未授权")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val payload = "shell:$cmd\u0000".toByteArray(Charsets.UTF_8)
                val currentChannelId = localChannelId++

                withContext(Dispatchers.Main) {
                    appendLog("[无线] adb shell $cmd")
                }

                // 写入 OPEN 通道协议数据
                client.sendPacket(
                    command = 0x4e45504f, 
                    arg0 = currentChannelId,
                    arg1 = 0,
                    payload = payload
                )
                Log.d(TAG, "无线命令子通道 [$currentChannelId] 投递成功")

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[无线错误] 发送失败: ${e.localizedMessage}")
                }
            }
        }
    }
    
    override fun onPairingSuccess() {
        runOnUiThread {
            Toast.makeText(this, "配对成功，检查权限并检索服务...", Toast.LENGTH_SHORT).show()
        }
        // 核心修改：配对成功后不再盲目扫描，先进行权限安全检查
        checkWifiPermissionAndScan()
    }

    override fun onPairingError(error: String) {
        Log.e(TAG, "配对失败: $error")
        runOnUiThread { appendLog("[配对错误] $error") }
    }
    /**
     * 针对 Android 13+ 的附近 Wi-Fi 设备权限检查与申请闭环
     */
    private fun checkWifiPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)+
            when {
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED -> {
                    // 已有权限，直接启动服务发现
                    startScanningForAdbConnect()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.NEARBY_WIFI_DEVICES) -> {
                    appendLog("[提示] 需要附近设备权限来扫描局域网内的 ADB 连接端口")
                    requestPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                else -> {
                    // 动态发起申请
                    requestPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }
        } else {
            // Android 12 及以下系统，无需动态申请该隐私权限，直接进入扫描
            startScanningForAdbConnect()
        }
    }
    /**
     * 启动基于 mDNS 协议的无线调试正式服务扫描
     */
    private fun startScanningForAdbConnect() {
        nsdManager?.discoverServices(
            "_adb-tls-connect._tcp.", 
            NsdManager.PROTOCOL_DNS_SD, 
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "mDNS 扫描已启动: $regType")
                    appendLog("mDNS 扫描已启动: $regType")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "寻找到无线调试服务端: ${serviceInfo.serviceName}")
                    appendLog("寻找到无线调试服务端: ${serviceInfo.serviceName}")
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                            val hostAddress = resolvedInfo.host.hostAddress
                            val port = resolvedInfo.port
                            Log.d(TAG, "服务解析成功 -> $hostAddress:$port")
                            appendLog("服务解析成功 -> $hostAddress:$port")
                            
                            // 正式发起底层 Socket 握手
                            connectToAdbServer(hostAddress, port)
                        }

                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "无线调试服务解析失败，错误码: $errorCode")
                            appendLog("无线调试服务解析失败，错误码: $errorCode")
                        }
                    })
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(regType: String) {}
                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
            }
        )
    }
    /**
     * 建立正式的无线 Socket 连接并绑定回调
     */
    private fun connectToAdbServer(host: String, port: Int) {
        val app = application as MyApp
        
        adbClient = AdbWifiClient(
            host = host,
            port = port,
            keyManager = app.adbKeyManager,
            privateKey = app.keyPair.private,
            // 异步接收数据块回显
            onLogReceived = { dataChunk ->
                lifecycleScope.launch(Dispatchers.Main) { 
                    appendLog(dataChunk) 
                }
            },
            // 无线调试授权置高
            onAuthSuccess = {
                isAdbWifiAuthorized = true
                lifecycleScope.launch(Dispatchers.Main) { 
                    appendLog("[系统] 无线网络链路认证成功，就绪。") 
                }
            },
            // 断开重置状态
            onConnectionClosed = {
                isAdbWifiAuthorized = false
                lifecycleScope.launch(Dispatchers.Main) { 
                    appendLog("[断开] 无线调试链路已切断。") 
                }
            }
        )
        adbClient?.connect()
    }

    private fun refreshUiText() {
        runOnUiThread {
            // 匹配要求：状态：USB 已连接，XXX
            val status = when {
                isAdbWifiAuthorized -> "状态：WiFi 无线调试已连接"
                isFastbootMode -> "状态：USB 已连接，Fastboot模式"
                isUsbAttached && isAdbAuthorized -> "状态：USB 已连接，ADB已授权"
                isUsbAttached -> "状态：USB 已连接，ADB未授权"
                else -> "状态：未连接"
            }
            binding.tvStatus.text = status
            binding.tvStatus.setTextColor(if (isAdbAuthorized || isFastbootMode) Color.GREEN else Color.RED)
            binding.appMainActivity.fbSelinux.isEnabled = isFastbootMode
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
        super.onDestroy()
        readerJob?.cancel()
        usbConn?.close()
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbStateReceiver)
        adbClient?.disconnect()
    }
}
