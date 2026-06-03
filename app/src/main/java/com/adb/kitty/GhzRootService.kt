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

    // 动态映射表：解耦硬编码，动态绑定物理 cpu 0..7 到最真实的物理 Linux 温度文件节点
    private val cpuThermalPaths = arrayOfNulls<File>(8)
    // 缓存抓取到的高通 GPU 温度节点文件
    private var gpuThermalPath: File? = null

    override fun onCreate() {
        super.onCreate()
        // 守护进程拉起瞬间，立刻激活广义自适应高通热力学拓扑大普查
        initUniversalQualcommThermalMapping()
    }

    override fun onBind(intent: Intent): IBinder {
        return object : ICpuBinder.Stub() {
            
            /**
             * 核心频率 8×6 方阵数据拉取
             */
            override fun getAllCpuFreqData(core: Int): DoubleArray {
                val data = DoubleArray(6) { 0.0 }
                val nodeLabels = arrayOf(
                    "cpuinfo_cur_freq", "cpuinfo_max_freq", "cpuinfo_min_freq",
                    "scaling_max_freq", "scaling_min_freq", "scaling_cur_freq"
                )
                for (i in 0..5) {
                    try {
                        val path = "/sys/devices/system/cpu/cpu$core/cpufreq/${nodeLabels[i]}"
                        val rawStr = File(path).readText().trim()
                        val rawVal = rawStr.toDouble()
                        // 赫兹转吉赫兹（Hz -> GHz）
                        data[i] = if (rawVal > 10000) rawVal / 1000000.0 else rawVal
                    } catch (e: Exception) {
                        data[i] = 0.0
                    }
                }
                return data
            }

            /**
             * 核心大件热链路收割（融入全系高通 GPU 频率与温度监控）
             */
            override fun getSystemTemperatures(): DoubleArray {
                val data = DoubleArray(4) { 0.0 } // [0]=CPU综合, [1]=电池, [2]=GPU温度, [3]=GPU主频(GHz)
                try {
                    // 1. CPU 综合平均温度（常驻 zone0 兜底）
                    val cpuRaw = File("/sys/class/thermal/thermal_zone0/temp").readText().trim().toDouble()
                    data[0] = if (cpuRaw > 1000) cpuRaw / 1000.0 else cpuRaw

                    // 2. 电池物理温度
                    val batRaw = File("/sys/class/power_supply/battery/temp").readText().trim().toDouble()
                    data[1] = when {
                        batRaw > 1000 -> batRaw / 1000.0
                        batRaw > 100 -> batRaw / 10.0
                        else -> batRaw
                    }

                    // 3. GPU 核心温度（信任 onCreate 中普查出来的专属节点）
                    if (gpuThermalPath != null && gpuThermalPath!!.exists()) {
                        val gpuRaw = gpuThermalPath!!.readText().trim().toDoubleOrNull() ?: 0.0
                        data[2] = if (gpuRaw > 1000) gpuRaw / 1000.0 else gpuRaw
                    } else {
                        data[2] = data[0] // 没搜到则用 CPU 综合温度平滑兜底
                    }

                    // 4. 高通通用 Adreno GPU 物理实时运行主频
                    try {
                        val gpuClkRaw = File("/sys/class/kgsl/kgsl-3d0/gpuclk").readText().trim().toDouble()
                        data[3] = gpuClkRaw / 1000000000.0 // Hz -> GHz (如 515000000 -> 0.515GHz)
                    } catch (e: Exception) {
                        data[3] = 0.0
                    }

                } catch (e: Exception) { }
                return data
            }

            /**
             * 通用自适应 8核心独立物理温度收割接口
             */
            override fun getAllCpuCoreTemps(): DoubleArray {
                val temps = DoubleArray(8) { 0.0 }
                for (core in 0..7) {
                    try {
                        val file = cpuThermalPaths[core]
                        if (file != null && file.exists()) {
                            val raw = file.readText().trim().toDouble()
                            temps[core] = if (raw > 1000) raw / 1000.0 else raw
                        }
                    } catch (e: Exception) {
                        temps[core] = 0.0
                    }
                }
                return temps
            }
        }
    }

    /**
     * 🔥 核心算法：全系高通骁龙底层物理热敏探头（TSENS）模糊权重识别与自适应动态拓扑对齐算法
     */
    private fun initUniversalQualcommThermalMapping() {
        try {
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { _, name -> name.startsWith("thermal_zone") } ?: return
            
            val explicitCpuZones = ArrayList<Pair<Int, File>>()
            val tsensZones = ArrayList<Pair<Int, File>>()

            for (zone in zones) {
                if (!File(zone, "type").exists()) continue
                val type = File(zone, "type").readText().trim().lowercase()
                val tempFile = File(zone, "temp")

                // 🎯 A710 / Cortex-X2 / 甚至是后来的 X4/X5 核心直接显示声明的节点
                if (type.contains("cpu") || type.contains("core") || type.contains("prime")) {
                    val coreIdInType = type.filter { it.isDigit() }.toIntOrNull()
                    if (coreIdInType != null && coreIdInType in 0..7) {
                        explicitCpuZones.add(Pair(coreIdInType, tempFile))
                        continue
                    }
                    if (type.contains("prime") || type.contains("x2") || type.contains("cpu-1-7") || type.contains("cpu7")) {
                        explicitCpuZones.add(Pair(7, tempFile))
                        continue
                    }
                }

                // 🎯 斩获高通通用 GPU 物理传感器
                if (gpuThermalPath == null && (type.contains("gpu") || type.contains("kgsl") || type.contains("msm_gpu"))) {
                    gpuThermalPath = tempFile
                }

                // 🎯 高通底层连续物理排序探头序列 tsens_tz_sensor
                if (type.contains("tsens_tz_sensor")) {
                    val sensorId = type.replace("tsens_tz_sensor", "").toIntOrNull()
                    if (sensorId != null) {
                        tsensZones.add(Pair(sensorId, tempFile))
                    }
                }
            }

            // 装配第一梯队：直接信任显式标明物理编号的黄金节点
            for (pair in explicitCpuZones) {
                val core = pair.first
                if (cpuThermalPaths[core] == null) {
                    cpuThermalPaths[core] = pair.second
                }
            }

            // 装配第二梯队：利用高通物理升序连续性，动态填充未映射完的核心温度槽位
            if (tsensZones.isNotEmpty()) {
                tsensZones.sortBy { it.first } // 按硬件物理排布升序
                
                for (core in 0..7) {
                    if (cpuThermalPaths[core] == null) {
                        // 考虑架构演进（如 1+3+4、1+5+2、2+6 等全集群自适应分配）
                        val targetIndex = when (core) {
                            in 0..3 -> 0 // 降维映射，能效核心共享前端传感器
                            4 -> if (tsensZones.size > 1) 1 else 0
                            5 -> if (tsensZones.size > 2) 2 else 0
                            6 -> if (tsensZones.size > 3) 3 else 0
                            7 -> tsensZones.size - 1 // 物理超大核永远霸占整个硬件阵列的最末端狂暴位置
                            else -> 0
                        }
                        if (targetIndex < tsensZones.size) {
                            cpuThermalPaths[core] = tsensZones[targetIndex].second
                        }
                    }
                }
            }

            // 第三梯队：终极防爆闭环兜底
            val fallbackZone = File("/sys/class/thermal/thermal_zone0/temp")
            for (i in 0..7) {
                if (cpuThermalPaths[i] == null || !cpuThermalPaths[i]!!.exists()) {
                    cpuThermalPaths[i] = fallbackZone
                }
            }
            if (gpuThermalPath == null) {
                gpuThermalPath = fallbackZone
            }
        } catch (e: Exception) {
            val fallbackZone = File("/sys/class/thermal/thermal_zone0/temp")
            for (i in 0..7) { cpuThermalPaths[i] = fallbackZone }
            gpuThermalPath = fallbackZone
        }
    }
}
