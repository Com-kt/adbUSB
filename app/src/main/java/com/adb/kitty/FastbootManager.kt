/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import com.adb.kitty.R

import android.*
import android.util.*
import android.content.pm.*
import android.app.*
import android.graphics.*
import android.animation.*

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
import androidx.core.app.*
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
import org.json.*

data class FastbootConfig(
    val abPartitions: Set<String> = setOf(
        "boot", "abl", "xbl", "xbl_config", "cpucp_dtb", "shrm", 
        "aop", "aop_config", "tz", "devcfg", "featenabler", "hyp", 
        "uefi", "uefisecapp", "spuservice", "modem", "modemfirmware", 
        "bluetooth", "dsp", "keymaster", "qupfw", "multiimgoem", 
        "multiimgqti", "cpucp", "xbl_ramdump", "imagefv", 
        "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system",
        "recovery"
    ),
    val bootPartitions: Set<String> = setOf(".img", ".elf", ".bin", ".mbn")
)

data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

class FastbootManager(
    private val scope: CoroutineScope,
    private val usbConn: UsbDeviceConnection,
    private val epOut: UsbEndpoint,
    private val epIn: UsbEndpoint,
    private val responseChannel: Channel<String>,
    private val flashFolder: File,
    private val config: FastbootConfig = FastbootConfig()
) {
    private var readerJob: Job? = null
    
    private val _logFlow = MutableSharedFlow<String>()
    val logFlow = _logFlow.asSharedFlow()

    private suspend fun log(msg: String) {
        _logFlow.emit(msg)
    }
    
    fun startFastbootReader() {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            while (isActive) {
                val read = usbConn?.bulkTransfer(epIn, buffer, buffer.size, 1000) ?: -1
                if (read > 0) {
                    val response = String(buffer, 0, read).trim()
                    withContext(Dispatchers.Main) { log("FB >> $response") }
                    responseChannel.trySend(response)
                }
            }
        }
    }

    private suspend fun waitForTerminalResponse(
        timeout: Long = 10000, 
        onInfoReceived: suspend (String) -> Unit
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
    
    suspend fun executeCommandSync(command: String) = withContext(Dispatchers.IO) {
        val cleanCmd = command.removePrefix("fastboot ").trim()
        if (cleanCmd.isEmpty()) return@withContext
        val parts = cleanCmd.split(Regex("\\s+"))
        val action = parts[0].lowercase()

        // 🌟 2. 【分流路由】先处理需要复杂逻辑的“高阶”命令
        when (action) {
            "flash" -> {
                if (parts.size >= 3) {
                    performFlash(parts[1], parts[2])
                } else {
                    withContext(Dispatchers.Main) { log("❌ 格式错误: flash <分区> <文件名>") }
                }
                return@withContext
            }
            "boot" -> {
                if (parts.size >= 2) {
                    performBoot(parts[1])
                } else {
                    withContext(Dispatchers.Main) { log("❌ 格式错误: boot <文件名>") }
                }
                return@withContext
            }
        }

    // 🔌 3. 【原生协议转换】处理标准 Fastboot 指令
    // 只有走到这里的指令，才会进入 USB 协议发送流程
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
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            "format" -> {
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            "set_active" -> {
                if (parts.size >= 2) "$action:${parts[1]}" else ""
            }
            else -> {
                cleanCmd
            }
        }

        withContext(Dispatchers.Main) {
            log("🚀 [USB直连] 发送: $protocolCmd")
        }

        // 4. 发送指令并等待响应
        sendFastbootCommandDirect(protocolCmd)

        val result = waitForTerminalResponse(10000) { infoText ->
            log("FB << (bootloader) $infoText")
        }

        // 5. 状态结算
        withContext(Dispatchers.Main) {
            when (result.status) {
                "OKAY" -> log("FB << OKAY [执行成功] ${result.payload}")
                "FAIL" -> log("❌ [错误] 手机拒绝了该指令: ${result.payload}")
                "TIMEOUT" -> log("⚠️ [超时] ${result.payload}")
            }
        }
    }
    
    suspend fun performFlash(partition: String, inputPath: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanFileName = inputPath.removePrefix("/")
        val file = File(flashFolder, cleanFileName)
        if (!file.exists()) {
            withContext(Dispatchers.Main) { 
                log("❌ 错误: 找不到镜像文件 -> $file.absolutePath") 
            }
            return@withContext
        }
        
        val activeSlot = getActiveSlot()
        val hasManualSuffix = partition.endsWith("_a", ignoreCase = true) || 
                              partition.endsWith("_b", ignoreCase = true) || 
                              partition.endsWith("_ab", ignoreCase = true)
        val targetPartition = if (hasManualSuffix) {
            // 用户指定了具体插槽，直接使用用户的输入
            partition 
        } else {
            // 用户没指定，使用你的自动映射逻辑
            getTargetPartition(partition, activeSlot)
        }
        
        withContext(Dispatchers.Main) { 
            log("📂 即将刷入: ${file.name} -> 目标: $targetPartition")
            log("📱 计算目标: $partition -> $targetPartition (Active Slot: ${activeSlot.ifEmpty { "N/A" }})")
        }
        
        // 1. 预处理：判断是否需要特殊处理 (Sparse Image)
        // 如果是 Sparse Image 且设备不支持直接刷写，此处可插入转换逻辑
        val isSparse = isSparseImage(file)
        withContext(Dispatchers.Main) { log("ℹ️ 格式识别: ${if (isSparse) "Sparse Image" else "Raw Image"}") }

        // 2. 握手阶段: download:<size>
        // 协议要求：size 必须是 8 位十六进制
        val sizeHex = String.format("%08x", file.length())
        withContext(Dispatchers.Main) { log("🚀 开始下载: $partition (大小: ${file.length()} bytes)") }
    
        sendFastbootCommandDirect("download:$sizeHex")
    
        // 等待设备响应 DATA (只有收到 DATA 才能开始传数据)
        val handshake = waitForTerminalResponse(10000) { }
        if (handshake.status != "DATA") {
            withContext(Dispatchers.Main) { log("❌ 拒绝下载: ${handshake.payload}") }
            return@withContext
        }

        // 3. 数据传输阶段 (流式循环)
        withContext(Dispatchers.Main) { log("⏳ 正在传输数据，请勿断开连接...") }
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
            withContext(Dispatchers.Main) { log("❌ 传输数据失败: ${e.message}") }
            return@withContext
        }

        // 4. 等待下载确认 (OKAY)
        val downloadConfirm = waitForTerminalResponse(30000) { }
        if (downloadConfirm.status != "OKAY") {
            withContext(Dispatchers.Main) { log("❌ 下载被拒绝: ${downloadConfirm.payload}") }
            return@withContext
        }

        // 5. 触发刷写阶段: flash:<partition>
        withContext(Dispatchers.Main) { log("⚡ 触发刷写: flash:$targetPartition") }
        sendFastbootCommandDirect("flash:$targetPartition")
    
        // 6. 最终结算 (长超时)
        // 刷写过程设备会频繁返回 INFO，我们通过回调实时打印
        val flashResult = waitForTerminalResponse(120000) { info ->
            withContext(Dispatchers.Main) { log("FB << (bootloader) $info") }
        }
        
        val endTime = System.currentTimeMillis()
        val durationSeconds = (endTime - startTime) / 1000.0
        val thresholdBytes = 512 * 1024 // 512KB

        withContext(Dispatchers.Main) {
            if (flashResult.status == "OKAY") {
                val logMessage = StringBuilder()
                logMessage.append("✅ [成功] 分区 $targetPartition 刷写完成\n")
                logMessage.append("⏱️ 耗时: ${"%.2f".format(durationSeconds)}秒")
            
                if (file.length() >= thresholdBytes && durationSeconds > 0) {
                    val fileSizeMB = file.length() / (1024.0 * 1024.0)
                    val speedMbps = fileSizeMB / durationSeconds
                    logMessage.append(" | 平均速度: ${"%.2f".format(speedMbps)} MB/s")
                } else {
                    logMessage.append("刷写的分区过小，因此不展示传输速度")
                }
                log(logMessage.toString())
            } else {
                log("❌ [失败] 分区 $partition 刷写失败: ${flashResult.payload} (已耗时: ${"%.2f".format(durationSeconds)}秒)")
            }
        }
    }
    
    suspend fun performBoot(fileName: String) = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val file = File(flashFolder, fileName)
        val extension = "." + fileName.substringAfterLast(".", "").lowercase()

        if (fileName.endsWith(".xml", true) || fileName.endsWith(".txt", true) || fileName.endsWith(".py", true)) {
            withContext(Dispatchers.Main) { 
                log("❌ 错误: 该文件类型无法引导 (XML/TXT/PY)") 
            }
            return@withContext
        }
        
        if (!config.bootPartitions.contains(extension)) {
            withContext(Dispatchers.Main) { 
                log("⚠️ 警告: 文件后缀 $extension 可能无法被设备引导，将尝试发送...") 
            }
        }
        
        if (!file.exists()) {
            withContext(Dispatchers.Main) { 
                log("❌ 错误: 找不到文件 -> ${file.absolutePath}") 
            }
            return@withContext
        }

        withContext(Dispatchers.Main) { 
            log("🚀 准备启动 (RAM Boot): ${file.name}") 
        }

        try {
            // 3. 下载到内存
            val sizeHex = String.format("%08x", file.length())
            sendFastbootCommandDirect("download:$sizeHex")
        
            val handshake = waitForTerminalResponse(10000) { }
            if (handshake.status != "DATA") {
                withContext(Dispatchers.Main) { log("❌ 拒绝下载: ${handshake.payload}") }
                return@withContext
            }

            val buffer = ByteArray(65536)
            FileInputStream(file).use { fis ->
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    val written = usbConn?.bulkTransfer(epOut, buffer, bytesRead, 5000) ?: -1
                    if (written != bytesRead) throw Exception("USB 传输中断")
                }
            }

            val downloadConfirm = waitForTerminalResponse(30000) { }
            if (downloadConfirm.status != "OKAY") {
                withContext(Dispatchers.Main) { log("❌ 下载被拒绝: ${downloadConfirm.payload}") }
                return@withContext
            }

            // 4. 触发 Boot
            withContext(Dispatchers.Main) { log("⚡ 发送 boot 指令…") }
            sendFastbootCommandDirect("boot")

            val bootResult = waitForTerminalResponse(30000) { }
            val duration = (System.currentTimeMillis() - startTime) / 1000.0

            withContext(Dispatchers.Main) {
                if (bootResult.status == "OKAY") {
                    log("✅ [成功] 已发送 boot 指令 (耗时: ${"%.2f".format(duration)}秒)")
                } else {
                    log("❌ [失败] Boot 指令被拒绝: ${bootResult.payload}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { log("❌ 异常: ${e.message}") }
        }
    }
    
    private suspend fun getActiveSlot(): String {
        // 1. 发送查询命令
        sendFastbootCommandDirect("getvar:current-slot")
        val response = waitForTerminalResponse(5000) { /* 可以在这里打印日志调试 */ }
    
        // 将整个接收到的 payload 转为小写，统一处理，避免大小写导致的识别错误
        val fullResponse = response.payload.lowercase()
    
        // 3. 直接通过特征字符串进行判断
        return when {
            // 优先匹配“粘包”情况
            fullResponse.contains("okayb") -> "b"
            fullResponse.contains("okaya") -> "a"
        
            // 兼容处理：如果设备响应正常，没有粘包，而是标准格式
            fullResponse.contains("current-slot: b") -> "b"
            fullResponse.contains("current-slot: a") -> "a"
        
            // 兼容处理：有些设备直接返回单独的 a 或 b
            fullResponse.contains("slot: b") || fullResponse.endsWith(" b") -> "b"
            fullResponse.contains("slot: a") || fullResponse.endsWith(" a") -> "a"
        
            // 如果都匹配不到，说明无法识别，返回空
            else -> ""
        }
    }
    
    private fun getTargetPartition(partition: String, activeSlot: String): String {
    // 只有在白名单内的分区，且我们确实获取到了活跃插槽时，才拼接后缀
        return if (config.abPartitions.contains(partition) && activeSlot.isNotEmpty()) {
            "${partition}_$activeSlot"
        } else {
            // 对于 misc, metadata, userdata, super 等，保持原样
            partition
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
}
