/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.widget.Toast
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PerformanceTurbo(private val context: Context) {

    private var hintSession: PerformanceHintManager.Session? = null

    suspend fun enterTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            try {
                val hintManager = context.getSystemService<PerformanceHintManager>()
                if (hintManager != null) {
                    val myTid = Process.myTid()
                    val targetDurationNanos = 10_000_000L // 10ms
                    hintSession = hintManager.createHintSession(intArrayOf(myTid), targetDurationNanos)
                    
                    // 内核心理战提频
                    hintSession?.reportActualWorkDuration(1_000_000L) 
                    
                    // 🟩 用协程切回主线程弹 Toast
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context.applicationContext, "🚀 狂暴性能策略已激活！", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun exitTurboMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hintSession?.close()
            hintSession = null
            
            // 🟩 用协程切回主线程弹 Toast
            withContext(Dispatchers.Main) {
                Toast.makeText(context.applicationContext, "🍃 性能策略已恢复省电模式", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
