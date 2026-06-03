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
import java.io.File

class GhzRootService : RootService() {

    // 动态存储全系统抓取到的所有物理热敏探头节点与别名
    private val validThermalZones = ArrayList<Pair<String, File>>()
    private var gpuFreqPath: File? = null
    private var gpuTempPath: File? = null

    override fun onCreate() {
        super.onCreate()
        probeAllPhysicalHardwareNodes()
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
                    } catch (e: Exception) {
                        data[i] = 0.0
                    }
                }
                return data
            }

            override fun getHardwareSnapshots(): DoubleArray {
                val data = DoubleArray(3) { 0.0 } // 电池温, GPU频, GPU温
                try {
                    // 1. 电池物理温度
                    val batRaw = File("/sys/class/power_supply/battery/temp").readText().trim().toDouble()
                    data[0] = if (batRaw > 1000) batRaw / 1000.0 else (if (batRaw > 100) batRaw / 10.0 else batRaw)

                    // 2. GPU 实时频率
                    if (gpuFreqPath != null && gpuFreqPath!!.exists()) {
                        val gpuClkRaw = gpuFreqPath!!.readText().trim().toDouble()
                        data[1] = gpuClkRaw / 1000000000.0
                    }

                    // 3. GPU 专属物理温度
                    if (gpuTempPath != null && gpuTempPath!!.exists()) {
                        val gpuTempRaw = gpuTempPath!!.readText().trim().toDouble()
                        data[2] = if (gpuTempRaw > 1000) gpuTempRaw / 1000.0 else gpuTempRaw
                    } else {
                        // 没捞到专属GPU探头，用 zone0 盲批兜底
                        val zone0Raw = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toDouble()
                        data[2] = if (zone0Raw > 1000) zone0Raw / 1000.0 else zone0Raw
                    }
                } catch (e: Exception) { }
                return data
            }

            override fun getRawThermalTemps(): DoubleArray {
                val temps = DoubleArray(validThermalZones.size)
                for (i in validThermalZones.indices) {
                    try {
                        val raw = validThermalZones[i].second.readText().trim().toDouble()
                        temps[i] = if (raw > 1000) raw / 1000.0 else raw
                    } catch (e: Exception) {
                        temps[i] = 0.0
                    }
                }
                return temps
            }

            override fun getRawThermalTypes(): Array<String> {
                return Array(validThermalZones.size) { i -> validThermalZones[i].first }
            }
        }
    }

    /**
     * 🚀【暴力盲扫描算法】直接全盘清剿底层 Linux 物理热链路，不管它怎么伪装
     */
    private fun probeAllPhysicalHardwareNodes() {
        try {
            validThermalZones.clear()
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { _, name -> name.startsWith("thermal_zone") } ?: return
            
            // 按 zone 编号数字升序（zone0, zone1...）排序，保证输出不变形
            zones.sortBy { it.name.replace("thermal_zone", "").toIntOrNull() ?: 0 }

            for (zone in zones) {
                val typeFile = File(zone, "type")
                val tempFile = File(zone, "temp")
                if (typeFile.exists() && tempFile.exists()) {
                    val rawType = typeFile.readText().trim()
                    // 过滤掉无关紧要的、或者没有数据的死探头
                    if (rawType.isNotEmpty() && rawType != "unknown") {
                        validThermalZones.add(Pair(rawType, tempFile))
                        
                        // 顺便锁定物理 GPU 温度探头
                        val lowerType = rawType.lowercase()
                        if (gpuTempPath == null && (lowerType.contains("gpu") || lowerType.contains("kgsl") || lowerType.contains("msm_gpu"))) {
                            gpuTempPath = tempFile
                        }
                    }
                }
            }

            // 锁定物理 GPU 实时主频节点
            val commonGpuFreqPaths = arrayOf(
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/devices/platform/soc/valhall.gpu/devfreq/valhall.gpu/cur_freq"
            )
            for (path in commonGpuFreqPaths) {
                val f = File(path)
                if (f.exists()) {
                    gpuFreqPath = f
                    break
                }
            }
        } catch (e: Exception) {}
    }
}
