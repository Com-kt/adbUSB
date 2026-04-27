package com.adb.kitty

import com.adb.kitty.databinding.ActivityMainBinding

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
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.ExperimentalUnsignedTypes
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import javax.crypto.Cipher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

data class AdbCommand(val description: String, val command: String)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var keyManager: AdbKeyManager
    private val ACTION_USB_PERMISSION = "com.adb.kitty.USB_PERMISSION"

    private var usbConn: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var rsaKeyPair: KeyPair? = null
    private var readerJob: Job? = null
    private var mLocalId = 1

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false 
    private var authFailureCount = 0
    
    private val flashFolder by lazy { File(getExternalFilesDir(null), "flash") }
    
    private fun ensureFlashDirExists() {
        if (!flashFolder.exists()) {
            flashFolder.mkdirs()
        }
    }
    
// 这是一个 256 字节的 RSA-2048 填充块
@OptIn(ExperimentalUnsignedTypes::class)
private val SIGNATURE_PADDING = byteArrayOf(
    0x00, 0x01, 
    // 接下来是 202 个 0xFF
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
    0xff.toByte(), 0xff.toByte(), 
    // 填充结束
    0x00, 
    // SHA-1 OID 标识
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
)

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let {
                        appendLog("[权限] USB权限获取成功")
                        connectToInterface(it)
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    isUsbAttached = true
                    refreshUiText()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    isUsbAttached = false
                    isAdbAuthorized = false
                    isFastbootMode = false
                    readerJob?.cancel()
                    usbConn?.close()
                    refreshUiText()
                    appendLog("\n[系统] USB 设备已断开")
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
           // 如果背景很亮，强制状态栏图标显示为深色
           // statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
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

        lifecycleScope.launch(Dispatchers.IO) {
            rsaKeyPair = keyManager.getKeys()
        }
        
        AutoDismissDialogFragment().show(supportFragmentManager, "AutoDismissDialog")

        val exportFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), exportFlag)
        registerReceiver(usbStateReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, exportFlag)

        binding.appMainActivity.btnConnect.setOnClickListener { findDevice() }
        
        binding.appMainActivity.btnSend.setOnClickListener {
            val cmd = binding.appMainActivity.etCommand.text.toString().trim()
            if (cmd.isEmpty()) return@setOnClickListener
            
            binding.appMainActivity.etCommand.setText("")

            if (isFastbootMode) {
                sendFastbootCommand(cmd)
            } else if (isAdbAuthorized) {
                sendAdbShell(cmd)
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
                     appendLog("[发送] FB << $cmd") 
                   }
            
                 // 2. 发送原始指令
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
              Toast.makeText(this, "菜单项1", Toast.LENGTH_SHORT).show()
                true
           }
              R.id.action_main_3 -> {
                if (isFastbootMode) {
                    runFastbootScript()
                } else {
                    Toast.makeText(this, "当前不是 Fastboot 模式", Toast.LENGTH_SHORT).show()
                }
                true
           }
              R.id.action_main_4 -> {
              Toast.makeText(this, "菜单项3", Toast.LENGTH_SHORT).show()
                true
           }
             else -> super.onOptionsItemSelected(item)
        }
        
    }
    
    private val adbCommands = listOf(
        AdbCommand("查看 adbd 用户组", "id"),
        AdbCommand("查看SeLinux状态", "getenforce"),
        AdbCommand("重启", "reboot"),
        AdbCommand("重启到系统", "reboot system"),
        AdbCommand("重启到 Recovery", "reboot recovery"),
        AdbCommand("重启到 Bootloader", "reboot bootloader"),
        AdbCommand("重启到 FastbootD", "reboot fastboot"),
        AdbCommand("纯净系统无任何用户激活 Dhizuku (需要安装Dhizuku)", "dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver"),
        AdbCommand("激活 Sence (需要安装Sence)", "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"),
        AdbCommand("激活 AppManager (需要安装AppManager)", "sh /storage/emulated/0/Android/data/io.github.muntashirakon.AppManager/cache/run_server.sh 60001 wasp-lurk-ripen"),
        AdbCommand("查询系统信息", "uname -a"),
        AdbCommand("查看内核版本", "cat /proc/version"),
        AdbCommand("查看su版本", "su -v"),
        AdbCommand("查看基带版本", "getprop gsm.version.baseband"),
        AdbCommand("查看安卓系统版本", "getprop ro.build.version.release"),
        AdbCommand("查看设备编译版本", "getprop ro.build.display.id"),
        AdbCommand("查看安全补丁级别", "getprop ro.build.version.security_patch"),
        AdbCommand("查看主板型号", "getprop ro.product.board"),
        AdbCommand("查看屏幕分辨率 (随设置而变化)", "getprop persist.sys.miui_resolution"),
        AdbCommand("卸载系统更新", "pm uninstall --user 0 com.android.updater"),
        AdbCommand("恢复系统更新", "pm install-existing --user 0 com.android.updater"),
        AdbCommand("强行启用小米堆叠桌面 (已支持勿用)", "settings put global task_stack_view_layout_style 2 "),
        AdbCommand("强行停止小米系统桌面", "am force-stop com.miui.home")
    )
    
    private fun showAdbCommandDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("快捷发送 ADB 命令")
        // 使用系统自带双行布局：text1 为描述，text2 为具体命令
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
                // 只有在授权状态下才调用你的方法
                sendAdbShell(selectedCommand)
            } else {
                Toast.makeText(this, "ADB未授权", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }
    
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
                
                appendLog("----- 通过USB连接输出 -----")
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66) {
                    isFastbootMode = (intf.interfaceProtocol == 3)
                    isUsbAttached = true
                    
                    val modeName = if (isFastbootMode) "Fastboot" else "ADB"
                    // 匹配要求：同行显示 VID/PID 十进制
                    appendLog("\n--- 检测到 $modeName 兼容设备 ---")
                    appendLog("--- 需要开启USB调试 (安全设置) ---")
                //    appendLog("设备: ${device.productName ?: "未知"}")
                //    appendLog("VID: ${device.vendorId} | PID: ${device.productId}")

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
                        // --------------------------------
                    
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
            startAdbReader()
            lifecycleScope.launch(Dispatchers.IO) {
                val banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,openscreen_mdns,compression_zstd\u0000".toByteArray()
                sendPacket(0x4e584e43, 0x01000000, 1048576, banner)
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
                    val response = String(buffer, 0, read)
                    withContext(Dispatchers.Main) { appendLog("FB >> $response") }
                }
            }
        }
    }

    private fun sendFastbootCommand(command: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cleanCmd = command.removePrefix("fastboot ").trim()
            val parts = cleanCmd.split(Regex("\\s+"))

            if (parts.size >= 2 && (parts[0] == "flash" || parts[0] == "boot")) {
                val fileName = parts.last()
                val imgFile = File(flashFolder, fileName)

                if (imgFile.exists()) {
                    withContext(Dispatchers.Main) { 
                        appendLog("[匹配] 发现本地镜像: $fileName (${imgFile.length() / 1024 / 1024} MB)") 
                    }
                    performAtomicFlash(cleanCmd, imgFile)
                    return@launch
                } else {
                    withContext(Dispatchers.Main) { 
                        appendLog("[提醒] 动作 '${parts[0]}' 缺少本地镜像 $fileName") 
                    }
                }
            }
            withContext(Dispatchers.Main) { appendLog("FB << $cleanCmd") }
            sendFastbootCommandDirect(cleanCmd)
        }
    }

    private fun runFastbootScript() {
        val scriptFile = File(flashFolder, "fastboot_flash.txt")
        if (!scriptFile.exists()) {
            appendLog("[错误] 目录下未找到 fastboot_flash.txt")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scriptFile.readLines().forEach { line ->
                    val rawLine = line.trim()
                    if (rawLine.isNotEmpty() && !rawLine.startsWith("#")) {
                        sendFastbootCommand(rawLine)
                        delay(600) 
                    }
                }
                withContext(Dispatchers.Main) { appendLog("[完成] 所有脚本指令处理完毕") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[异常] $e") }
            }
        }
    }

    private suspend fun performAtomicFlash(command: String, file: File) {
        val size = file.length()
        val parts = command.split(" ")
        withContext(Dispatchers.Main) { appendLog("[刷写] 准备写入 ${if(parts[0]=="boot") "内存引导" else parts[1]} ...") }

        sendFastbootCommandDirect("download:${String.format("%08x", size)}")
        delay(100)

        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                usbConn?.bulkTransfer(epOut, buffer, read, 30000)
            }
        }
        withContext(Dispatchers.Main) { appendLog("[传输] 数据发送完毕 (100%)") }
        delay(150)

        val finalAction = if (parts[0] == "boot") "boot" else "flash:${parts[1]}"
        sendFastbootCommandDirect(finalAction)
    }

    private fun sendFastbootCommandDirect(command: String) {
        val data = command.toByteArray()
        usbConn?.bulkTransfer(epOut, data, data.size, 1000)
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
                
                withContext(Dispatchers.Main) {
                    when (cmd) {
                      /*  0x48545541 -> {
                            if (!stepAuthSent) {
                             //   rsaKeyPair?.let { sendPacket(0x48545541, 2, 0, sign(payload!!)); stepAuthSent = true }
                                rsaKeyPair?.let { sendPacket(0x48545541, 2, 0, signAdbToken(payload!!)); stepAuthSent = true; android.util.Log.d("ADB_DEBUG", "首次握手：已发送签名并置位 true") }
                            } else {
                                android.util.Log.e("ADB_DEBUG", "握手异常：设备拒绝了签名，正在强制发送公钥")
                                val pub = "${keyManager.getPublicKeyBase64()} adb@client\u0000".toByteArray()
                                sendPacket(0x48545541, 3, 0, pub)
                            }
                        }
                        0x48545541 -> { // AUTH
                            val authType = arg0
                          when (authType) {
                             1 -> { 
                             if (!stepAuthSent || authFailureCount < MAX_FAILURE_THRESHOLD) {
                                 // 尝试签名
                                 appendLog("[Auth] 尝试签名，计数: $authFailureCount")
                                 val signature = signAdbToken(payload!!)
                                 sendPacket(0x48545541, 2, 0, signature)
                                 stepAuthSent = true
                                 authFailureCount++ // 记录失败次数
                            } else {
                                 // 达到阈值，强制发送公钥，不再无限重连
                                 appendLog("[Critical] 签名多次失败，切换至公钥认证模式")
                                 val pub = "${keyManager.getPublicKeyBase64()} adb@kitty\u0000".toByteArray()
                                 sendPacket(0x48545541, 3, 0, pub)
                                 authFailureCount = 0 // 重置计数
                               }
                            }
                            3 -> { // 请求公钥 (处理设备主动索要的情况)
                                 val pub = "${keyManager.getPublicKeyBase64()} adb@kitty\u0000".toByteArray()
                                  sendPacket(0x48545541, 3, 0, pub)
                                }
                            }
                        }  */
                        0x48545541 -> { // "AUTH"
                            if (arg0 == 1) { // Token from device
                                if (authFailureCount < 1) {
                                    appendLog("[Auth] 尝试私钥签名响应...")
                                    sendPacket(0x48545541, 2, 0, signAdbToken(payload!!))
                                    authFailureCount++
                                } else {
                                    appendLog("[Auth] 发送二进制公钥申请授权...")
                                    val pub = "${keyManager.getPublicKeyBase64()} adb@kitty\u0000".toByteArray()
                                  sendPacket(0x48545541, 3, 0, pub)
                                }
                            }
                        }
                        0x4e584e43 -> { 
                            isAdbAuthorized = true
                            authFailureCount = 0
                            refreshUiText()
                            appendLog("\n>>> ADB 授权成功，链路就绪 <<<")
                        }
                        0x45545257 -> { 
                            appendLog(String(payload ?: byteArrayOf()))
                            sendPacket(0x59414b4f, arg1, arg0, null) 
                        }
                        0x45534c43 -> { // CLSE: 提醒流结束
                            appendLog("\n[流结束]")
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
        var checksum = 0
        payload?.forEach { checksum += (it.toInt() and 0xFF) }

        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum).putInt(cmd xor -1)

        lifecycleScope.launch(Dispatchers.IO) {
            usbConn?.bulkTransfer(epOut, buffer.array(), 24, 1000)
            payload?.let { usbConn?.bulkTransfer(epOut, it, it.size, 1000) }
        }
    }
    
/*
    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val len = payload?.size ?: 0
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len)
        var sum = 0; payload?.forEach { sum += (it.toInt() and 0xFF) }
        buffer.putInt(sum).putInt(cmd xor -1)
        usbConn?.bulkTransfer(epOut, buffer.array(), 24, 1000)
        payload?.let { usbConn?.bulkTransfer(epOut, it, it.size, 1000) }
    }

    private fun sign(token: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(rsaKeyPair!!.private); signer.update(token)
        return signer.sign()
    }
    */
    private fun signAdbToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair!!.private)
        val data = ByteArray(256)
        System.arraycopy(SIGNATURE_PADDING, 0, data, 0, SIGNATURE_PADDING.size)
        System.arraycopy(token, 0, data, 256 - 20, 20)
        return cipher.doFinal(data)
    }

    private fun refreshUiText() {
        runOnUiThread {
            // 匹配要求：状态：USB 已连接，XXX
            val status = when {
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
        runOnUiThread {
            binding.appMainActivity.tvLog.append(msg + "\n")
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
        unregisterReceiver(usbPermissionReceiver)
        unregisterReceiver(usbStateReceiver)
    }
}
