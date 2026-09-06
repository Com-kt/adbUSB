package com.adb.kitty.ui.it.cpu

import com.adb.kitty.*
import com.adb.kitty.R

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale

@Immutable
data class PerformanceUiState(
    val renderFps: Float = 0f,
    val refreshRateHz: Float = 0f,
    val batteryTemp: Float = 0f,
    val fpsHistory: List<Float> = emptyList(),
    val gpuMetric: GpuMetric = GpuMetric(),
    val cpuCores: List<CpuCoreMetric> = emptyList()
)