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
import java.io.FileInputStream
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
                        appendLog("[系统] USB权限获取成功")
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
              Toast.makeText(this, "菜单项2", Toast.LENGTH_SHORT).show()
                true
           }
              R.id.action_main_4 -> {
              Toast.makeText(this, "菜单项3", Toast.LENGTH_SHORT).show()
                true
           }
             else -> super.onOptionsItemSelected(item)
        }
        
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
            authFailureCount = 0
            isAdbAuthorized = false
            startAdbReader()
            lifecycleScope.launch(Dispatchers.IO) {
                val banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,abb,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,openscreen_mdns,compression_zstd\u0000".toByteArray(Charsets.UTF_8)
                sendPacket(0x4e584e43, 0x01000000, 262144, banner)
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

    // --- 3. 业务执行逻辑 ---

    suspend fun executeCommandSync(command: String) = withContext(Dispatchers.IO) {
        val cleanCmd = command.removePrefix("fastboot ").trim()

        if (cleanCmd.startsWith("flash") || cleanCmd.startsWith("boot")) {
            handleFlashWorkflow(cleanCmd)
        } else {
            // 普通 getvar 或 erase 指令
            sendFastbootCommandDirect(cleanCmd.replace(" ", ":")) // 协议格式通常为 getvar:unlocked
            val result = waitForTerminalResponse()
            
            withContext(Dispatchers.Main) {
                result.allLines.forEach { appendLog("FB >> $it") }
                if (result.status == "FAIL") appendLog("[错误] 执行失败: ${result.payload}")
            }
        }
    }

    private suspend fun handleFlashWorkflow(fullCommand: String) {
        val parts = fullCommand.split(Regex("\\s+"))
        val fileName = parts.last()
        val imgFile = File(flashFolder, fileName)

        if (!imgFile.exists()) {
            withContext(Dispatchers.Main) { appendLog("[错误] 找不到镜像文件: $fileName") }
            return
        }

        val fileSize = imgFile.length()
        
        // 1. Download 阶段
        sendFastbootCommandDirect("download:${String.format("%08x", fileSize)}")
        val dataResp = waitForTerminalResponse(5000)
        if (dataResp.status != "DATA") throw Exception("设备拒绝接收数据")

        // 2. Data 传输
        val buffer = ByteArray(512 * 1024)
        FileInputStream(imgFile).use { input ->
            var totalSent = 0L
            while (totalSent < fileSize) {
                val read = input.read(buffer)
                if (read <= 0) break
                usbConn?.bulkTransfer(epOut, buffer, read, 60000)
                totalSent += read
            }
        }

        // 3. 确认 Download 完成
        if (waitForTerminalResponse(30000).status != "OKAY") throw Exception("镜像上传校验失败")

        // 4. Flash 写入物理分区阶段
        val partition = parts.drop(1).firstOrNull { it != fileName } ?: "boot"
        sendFastbootCommandDirect("flash:$partition")
        
        val flashResult = waitForTerminalResponse(180000) // 写入可能很慢
        withContext(Dispatchers.Main) {
            if (flashResult.status == "OKAY") appendLog("[成功] $partition 分区刷写完成")
            else appendLog("[失败] 写入中断: ${flashResult.payload}")
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
                                    val signature = keyManager.signAdbToken(payload!!, keyPair.private)
                                    sendPacket(0x48545541, 2, 0, signature)
                                    authFailureCount++
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
        var checksum = 0
        payload?.forEach { checksum += (it.toInt() and 0xFF) }

        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum).putInt(cmd xor -1)

        lifecycleScope.launch(Dispatchers.IO) {
            usbConn?.bulkTransfer(epOut, buffer.array(), 24, 1000)
            payload?.let { usbConn?.bulkTransfer(epOut, it, it.size, 1000) }
        }
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
