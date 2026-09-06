package com.adb.kitty.ui.it.cpu

import com.adb.kitty.*
import com.adb.kitty.R

import androidx.compose.runtime.Immutable

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale

@Immutable
data class GpuMetric(
    val curFreqGhz: Float = 0f,
    val minFreqGhz: Float = 0f,
    val maxFreqGhz: Float = 0f,
    val utilizationPercent: Float = 0f,
    val history: List<Float> = emptyList()
)