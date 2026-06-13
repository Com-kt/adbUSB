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
import java.util.concurrent.Executors

class GhzRootService : RootService() {

    private val validThermalZones = ArrayList<Pair<String, File>>()
    private var gpuFreqPath: File? = null
    private var gpuTempPath: File? = null
    
    // 🧬 专属定制：直接建立一个专门用于硬件盲扫的线程池，确保 100 多个探头并发时线程绝对够用
    private val thermalExecutor = Executors.newCachedThreadPool()
    private val thermalDispatcher = thermalExecutor.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(thermalDispatcher + SupervisorJob())
    
    private val thermalBuffer = ConcurrentHashMap<Int, Double>()

    override fun onCreate() {
        super.onCreate()
        probeAllPhysicalHardwareNodes()
        startThermalPipeline()
    }

    private fun startThermalPipeline() {
        serviceScope.launch {
            val size = validThermalZones.size
            while (isActive) {
                // 在专属的独立线程池里并发轰炸，绝不占用公共 IO 线程池
                val jobs = List(size) { index ->
                    launch {
                        try {
                            val raw = validThermalZones[index].second.readText().trim().toDouble()
                            thermalBuffer[index] = if (raw > 1000) raw / 1000.0 else raw
                        } catch (e: Exception) {
                            thermalBuffer[index] = 0.0
                        }
                    }
                }
                jobs.joinAll() // 一个都不能少，全部排队死等收割完毕
                
                delay(100) // 歇 100ms 立刻开始下一轮普查
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return object : ICpuBinder.Stub() {
            
            override fun getAllCpuFreqData(core: Int): DoubleArray {
                val data = DoubleArray(6)
                val nodeLabels = arrayOf(
                    "cpuinfo_cur_freq", "cpuinfo_max_freq", "cpuinfo_min_freq",
                    "scaling_max_freq", "scaling_min_freq", "scaling_cur_freq"
                )
                for (i in 0..5) {
                    try {
                        val path = "/sys/devices/system/cpu/cpu$core/cpufreq/${nodeLabels[i]}"
                        val rawVal = File(path).readText().trim().toDouble()
                        data[i] = if (rawVal > 1000) rawVal / 1000000.0 else rawVal
                    } catch (e: Exception) { data[i] = 0.0 }
                }
                return data
            }

            override fun getHardwareSnapshots(): DoubleArray {
                val data = DoubleArray(3)
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

            override fun getRawThermalTemps(): DoubleArray {
                val size = validThermalZones.size
                val temps = DoubleArray(size)
                for (i in 0 until size) {
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
        thermalExecutor.shutdown() // 彻底释放线程池
        super.onDestroy()
    }
}
