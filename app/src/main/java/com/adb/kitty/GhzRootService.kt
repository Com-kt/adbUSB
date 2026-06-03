/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.Intent
import android.os.IBinder
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GhzRootService : RootService() {

    private val validThermalZones = ArrayList<Pair<String, File>>()
    private var gpuFreqPath: File? = null
    private var gpuTempPath: File? = null
    
    // 全局异步生命周期作用域
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 🧬 核心科技：全线程安全物理数据内存缓冲区（把探头索引作为 key，实时温度作为 value）
    private val thermalBuffer = ConcurrentHashMap<Int, Double>()

    override fun onCreate() {
        super.onCreate()
        probeAllPhysicalHardwareNodes()
        
        // 🚀 核心点火：后台特权传送带立刻全速开动，死循环无死角收割硬件
        startThermalPipeline()
    }

    /**
     * 🔄【后台纯异步传送带】有多少吃多少，排队也要全部吃完，绝不熔断！
     */
    private fun startThermalPipeline() {
        serviceScope.launch {
            val size = validThermalZones.size
            while (isActive) {
                // 利用多线程高并发同时轰炸这 100 多个节点
                val jobs = List(size) { index ->
                    launch {
                        try {
                            // 坚决不加 Timeout 熔断！哪怕耗时，也必须全量捞出数据！
                            val raw = validThermalZones[index].second.readText().trim().toDouble()
                            val finalTemp = if (raw > 1000) raw / 1000.0 else raw
                            // 塞入内存缓冲区
                            thermalBuffer[index] = finalTemp
                        } catch (e: Exception) {
                            thermalBuffer[index] = 0.0
                        }
                    }
                }
                // 排队死等全量探头安全生还
                jobs.joinAll()
                
                // 刷完一轮后，稍微喘口气（休息 100 毫秒），立刻开启下一轮无死角物理大普查
                delay(100)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return object : ICpuBinder.Stub() {
            
            override fun getAllCpuFreqData(core: Int): DoubleArray {
                val data = DoubleArray(6) { 0.0 }
                val nodeLabels = arrayOf(
                    "cpuinfo_cur_freq", "cpuinfo_max_freq", "cpuinfo_min_freq",
                    "scaling_max_freq", "scaling_min_freq", "scaling_cur_freq"
                )
                for (i in 0..5) {
                    try {
                        val path = "/sys/devices/system/cpu/cpu$core/cpufreq/${nodeLabels[i]}"
                        val rawVal = File(path).readText().trim().toDouble()
                        data[i] = if (rawVal > 10000) rawVal / 1000000.0 else rawVal
                    } catch (e: Exception) { data[i] = 0.0 }
                }
                return data
            }

            override fun getHardwareSnapshots(): DoubleArray {
                val data = DoubleArray(3) { 0.0 }
                try {
                    val batRaw = File("/sys/class/power_supply/battery/temp").readText().trim().toDouble()
                    data[0] = if (batRaw > 1000) batRaw / 1000.0 else (if (batRaw > 100) batRaw / 10.0 else batRaw)

                    if (gpuFreqPath != null && gpuFreqPath!!.exists()) {
                        data[1] = gpuFreqPath!!.readText().trim().toDouble() / 1000000000.0
                    }
                    if (gpuTempPath != null && gpuTempPath!!.exists()) {
                        val gpuTempRaw = gpuTempPath!!.readText().trim().toDouble()
                        data[2] = if (gpuTempRaw > 1000) gpuTempRaw / 1000.0 else gpuTempRaw
                    } else {
                        val zone0Raw = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toDouble()
                        data[2] = if (zone0Raw > 1000) zone0Raw / 1000.0 else zone0Raw
                    }
                } catch (e: Exception) { }
                return data
            }

            /**
             * ⏱️【0延迟高刷反击】前台来要数据时，直接从内存缓冲区吐出最新快照，耗时为 0ms！
             */
            override fun getRawThermalTemps(): DoubleArray {
                val size = validThermalZones.size
                val temps = DoubleArray(size)
                for (i in 0 until size) {
                    // 如果由于冷启动某节点还没刷出来，默认返回 0.0，刷出来后实时更新
                    temps[i] = thermalBuffer[i] ?: 0.0
                }
                return temps
            }

            override fun getRawThermalTypes(): Array<String> {
                return Array(validThermalZones.size) { i -> validThermalZones[i].first }
            }
        }
    }

    private fun probeAllPhysicalHardwareNodes() {
        try {
            validThermalZones.clear()
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { _, name -> name.startsWith("thermal_zone") } ?: return
            zones.sortBy { it.name.replace("thermal_zone", "").toIntOrNull() ?: 0 }

            for (zone in zones) {
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (typeFile.exists() && tempFile.exists()) {
                    val rawType = typeFile.readText().trim()
                    if (rawType.isNotEmpty() && rawType != "unknown") {
                        validThermalZones.add(Pair(rawType, tempFile))
                        val lowerType = rawType.lowercase()
                        if (gpuTempPath == null && (lowerType.contains("gpu") || lowerType.contains("kgsl") || lowerType.contains("msm_gpu"))) {
                            gpuTempPath = tempFile
                        }
                    }
                }
            }

            val commonGpuFreqPaths = arrayOf(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"
            )
            for (path in commonGpuFreqPaths) {
                if (File(path).exists()) {
                    gpuFreqPath = File(path)
                    break
                }
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        serviceScope.cancel() 
        super.onDestroy()
    }
}
