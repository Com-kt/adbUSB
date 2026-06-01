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

    // 用纯 Kotlin 匿名对象实现 AIDL 接口，稳稳锁住 Binder 跨进程引用
    private val binder = object : ICpuBinder.Stub() {
        override fun getAllCpuFreqData(coreIndex: Int): DoubleArray {
            val freqData = DoubleArray(6)
            val baseDir = "/sys/devices/system/cpu/cpu$coreIndex/cpufreq"

            // 6 个核心时钟节点的标准物理文件名
            val nodeNames = arrayOf(
                "cpuinfo_cur_freq",
                "cpuinfo_max_freq",
                "cpuinfo_min_freq",
                "scaling_max_freq",
                "scaling_min_freq",
                "scaling_cur_freq"
            )

            // 此时已经是 Linux 特权进程 (UID=0)，无视任何安全封锁，直接暴力硬核直读
            for (i in nodeNames.indices) {
                freqData[i] = try {
                    val file = File(baseDir, nodeNames[i])
                    // 用 Kotlin 超爽的 readText 扩展函数直读文本，自动关闭流，优雅！
                    val freqKhz = file.readText().trim().toDouble()
                    freqKhz / 1000000.0 // KHz 瞬变 GHz
                } catch (e: Exception) {
                    0.0 // 容错：若因高通内核核心极深休眠导致瞬时断开，温和返回 0.0GHz，绝不闪退
                }
            }
            return freqData
        }
    }

    override fun onBind(intent: Intent): IBinder {
        // 将免注册的 Linux 特权管道 Binder 实例抛还给主进程
        return binder
    }
}
