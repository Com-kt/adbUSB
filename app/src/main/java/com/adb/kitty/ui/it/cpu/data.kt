package com.adb.kitty.ui.it.cpu

import androidx.compose.runtime.Immutable
import java.util.ArrayDeque

@Immutable
data class CpuCoreMetric(
    val coreIndex: Int = 0,
    val curFreqGhz: Float = 0f,
    val minFreqGhz: Float = 0f,
    val maxFreqGhz: Float = 0f,
    val history: List<Float> = emptyList()
)

@Immutable
data class GpuMetric(
    val curFreqGhz: Float = 0f,
    val minFreqGhz: Float = 0f,
    val maxFreqGhz: Float = 0f,
    val utilizationPercent: Float = 0f,
    val history: List<Float> = emptyList()
)

@Immutable
data class PerformanceUiState(
    val isRootConnected: Boolean = false,
    val renderFps: Float = 0f,
    val refreshRateHz: Float = 0f,
    val batteryTemp: Float = 0f,
    val fpsHistory: List<Float> = emptyList(),
    val gpuMetric: GpuMetric = GpuMetric(),
    val cpuCores: List<CpuCoreMetric> = emptyList()
)
