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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import javax.crypto.Cipher
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

data class AdbCommand(val description: String, val command: String)

class MainActivity : AppCompatActivity() {

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

    private var isUsbAttached = false
    private var isAdbAuthorized = false
    private var isFastbootMode = false 
    private var authFailureCount = 0
    
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
                    val response = String(buffer, 0, read).trim()
                    withContext(Dispatchers.Main) { appendLog("FB >> $response") }
                    responseChannel.trySend(response)
                }
            }
        }
    }

    private suspend fun waitResponse(timeout: Long = 30000): String {
        return withTimeoutOrNull(timeout) {
            responseChannel.receive()
        } ?: "TIMEOUT"
    }
    
    private suspend fun executeCommandSync(command: String) {
        val cleanCmd = command.removePrefix("fastboot ").trim()
        val parts = cleanCmd.split(Regex("\\s+"))

        // 判断是否是刷写本地文件
        if (parts.size >= 2 && (parts[0] == "flash" || parts[0] == "boot")) {
            val nonParamParts = parts.filter { !it.startsWith("-") }
            val fileName = nonParamParts.last()
            val imgFile = File(flashFolder, fileName)

            if (imgFile.exists()) {
                performAtomicFlash(cleanCmd, imgFile)
                return
            }
        }

        // 普通指令发送并等待 OKAY
        sendFastbootCommandDirect(cleanCmd)
        val resp = waitResponse(5000)
        if (resp.startsWith("FAIL")) throw Exception("设备拒绝指令: $resp")
    }
    
    private fun parseHexLimit(resp: String): Long? {
        return try {
            if (resp.startsWith("OKAY")) {
                val hex = resp.substring(4).removePrefix("0x")
                hex.toLong(16)
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun performAtomicFlash(fullCommand: String, file: File) {
        val fileSize = file.length()
    
        // 1. 获取设备支持的最大单次下载大小
        sendFastbootCommandDirect("getvar:max-download-size")
        val maxDownloadSizeResp = waitResponse(2000)
    
        // 解析结果，例如 "OKAY0x20000000" -> 512MB。如果获取失败，默认给一个保守值 64MB
        val maxLimit = parseHexLimit(maxDownloadSizeResp) ?: (64 * 1024 * 1024L)
    
        appendLog("[检查] 镜像大小: ${fileSize / 1024 / 1024}MB, 设备限制: ${maxLimit / 1024 / 1024}MB")

        if (fileSize <= maxLimit) {
            // --- 小镜像逻辑：直接刷写 ---
            executeSingleFlash(fullCommand, file, fileSize)
        } else {
            // --- 大镜像逻辑：自动切片 (此逻辑通常配合 Sparse 镜像或 Bootloader 分段) ---
            // 注意：标准 Fastboot 协议在大文件时建议使用 Sparse 格式
            // 这里提供一个基础的分块传输逻辑示意
            appendLog("[警告] 镜像超过限制，尝试分段刷写...")
            executeChunkedFlash(fullCommand, file, fileSize, maxLimit)
        }
    }
    
    // 确保方法前有 suspend
private suspend fun executeChunkedFlash(fullCommand: String, file: File, totalSize: Long, limit: Long) {
    val partitionName = buildFinalAction(fullCommand, file.name).removePrefix("flash:")
    val inputStream = file.inputStream()
    var offset = 0L
    val buffer = ByteArray(256 * 1024)

    inputStream.use { input ->
        // 修改点 1: 使用 currentCoroutineContext().isActive
        while (offset < totalSize && currentCoroutineContext().isActive) { 
            val currentChunkSize = Math.min(limit, totalSize - offset)
            
            sendFastbootCommandDirect("download:${String.format("%08x", currentChunkSize)}")
            if (!waitResponse(5000).startsWith("DATA")) throw Exception("分段下载失败")

            var bytesSentInChunk = 0L
            // 修改点 2: 使用 currentCoroutineContext().isActive
            while (bytesSentInChunk < currentChunkSize && currentCoroutineContext().isActive) {
                val bytesToRead = Math.min(buffer.size.toLong(), currentChunkSize - bytesSentInChunk).toInt()
                val read = input.read(buffer, 0, bytesToRead)
                if (read <= 0) break
                
                usbConn?.bulkTransfer(epOut, buffer, read, 30000)
                bytesSentInChunk += read
            }

            if (!waitResponse(180000).startsWith("OKAY")) throw Exception("数据校验失败")

            sendFastbootCommandDirect("flash:$partitionName")
            if (!waitResponse(180000).startsWith("OKAY")) throw Exception("分段写入失败")

            offset += currentChunkSize
        }
    }
}

/**
 * 基础刷写单元（Download -> Data -> OKAY -> Flash -> OKAY）
 */
    private suspend fun executeSingleFlash(fullCommand: String, file: File, size: Long) {
        sendFastbootCommandDirect("download:${String.format("%08x", size)}")
        if (!waitResponse(5000).startsWith("DATA")) throw Exception("设备拒绝传输")

        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (currentCoroutineContext().isActive) {
                val read = input.read(buffer)
                if (read <= 0) break
                usbConn?.bulkTransfer(epOut, buffer, read, 30000)
            }
        }

        // 关键：等待写入 Flash 的 OKAY，大镜像需长达 2-3 分钟
        if (!waitResponse(180000).startsWith("OKAY")) throw Exception("数据校验失败")

        val finalAction = buildFinalAction(fullCommand, file.name)
        sendFastbootCommandDirect(finalAction)
    
        if (!waitResponse(180000).startsWith("OKAY")) throw Exception("刷写分区失败")
    }
    
    private fun buildFinalAction(command: String, fileName: String): String {
        if (command.startsWith("boot")) return "boot"
        val parts = command.split(" ").filter { it != "fastboot" && it != fileName }
        val action = parts[0] // flash
        val others = parts.drop(1)
        val partition = others.lastOrNull() ?: ""
        val params = others.filter { it != partition }.joinToString(":")
        
        return if (params.isEmpty()) "$action:$partition" else "$action:$partition:$params"
    }
    
    private fun sendFastbootCommandDirect(command: String) {
        val data = command.toByteArray()
        usbConn?.bulkTransfer(epOut, data, data.size, 1000)
    }
    
    private fun handleManualSend() {
        val cmd = binding.appMainActivity.etCommand.text.toString().trim()
        if (cmd.isEmpty()) return
        binding.appMainActivity.etCommand.setText("")
        lifecycleScope.launch(Dispatchers.IO) {
            try { executeCommandSync(cmd) } catch (e: Exception) { withContext(Dispatchers.Main) { appendLog("[出错] $e") } }
        }
    }

    fun runFastbootScript() {
        val scriptFile = File(flashFolder, "fastboot_flash.txt")
        if (!scriptFile.exists()) { appendLog("[错误] 找不到脚本文件"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                scriptFile.readLines().forEach { line ->
                    val raw = line.trim()
                    if (raw.isNotEmpty() && !raw.startsWith("#")) {
                        withContext(Dispatchers.Main) { appendLog("[脚本执行] $raw") }
                        executeCommandSync(raw)
                        delay(200) // 每一行执行完后的微小缓冲
                    }
                }
                withContext(Dispatchers.Main) { appendLog("[完成] 脚本执行完毕") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { appendLog("[异常] 脚本中断: ${e.message}") }
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
                
                withContext(Dispatchers.Main) {
                    when (cmd) {
                        0x48545541 -> { // "AUTH"
                            if (arg0 == 1) { // Token from device
                                if (authFailureCount < 1) {
                                    appendLog("[Auth] 尝试私钥签名响应...")
                                    sendPacket(0x48545541, 2, 0, signAdbToken(payload!!))
                                    authFailureCount++
                                } else {
                                    appendLog("[Auth] 发送公钥申请授权...")
                                 //   val pub = "${keyManager.getPublicKeyBase64()} adb@kitty\u0000".toByteArray()
                                    val pub = (keyManager.getPublicKeyBase64() + "\u0000").toByteArray()
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
    
    private fun signAdbToken(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair!!.private)
        val data = ByteArray(256)
        System.arraycopy(viewModel.SIGNATURE_PADDING, 0, data, 0, viewModel.SIGNATURE_PADDING.size)
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
    }
}
