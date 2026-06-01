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

            // 6 个核心时钟节点的标准文件名
            val nodeNames = arrayOf(
                "cpuinfo_cur_freq",
                "cpuinfo_max_freq",
                "cpuinfo_min_freq",
                "scaling_max_freq",
                "scaling_min_freq",
                "scaling_cur_freq"
            )

            // 拥有 Root (UID=0) 特权，畅通无阻直读硬件测谎仪节点
            for (i in nodeNames.indices) {
                freqData[i] = try {
                    val file = File(baseDir, nodeNames[i])
                    // 使用 Kotlin 扩展函数读取文本，自动管理流的关闭，极为优雅
                    val freqKhz = file.readText().trim().toDouble()
                    freqKhz / 1000000.0 // KHz 换算为人类直观的 GHz
                } catch (e: Exception) {
                    0.0 // 容错：若因核心极深休眠导致瞬时断开，温和返回 0.0GHz，绝不闪退
                }
            }
            return freqData
        }
    }

    override fun onBind(intent: Intent): IBinder {
        // 将特权 Binder 管道抛还给 UI 进程
        return binder
    }
}
