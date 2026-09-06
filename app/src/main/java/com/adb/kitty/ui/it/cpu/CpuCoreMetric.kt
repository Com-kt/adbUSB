package com.adb.kitty.ui.it.cpu

import com.adb.kitty.*
import com.adb.kitty.R

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale

@Immutable
data class CpuCoreMetric(
    val coreIndex: Int = 0,
    val curFreqGhz: Float = 0f, // cpuinfo_cur_freq
    val minFreqGhz: Float = 0f, // cpuinfo_min_freq
    val maxFreqGhz: Float = 0f, // cpuinfo_max_freq
    val history: List<Float> = emptyList()
)