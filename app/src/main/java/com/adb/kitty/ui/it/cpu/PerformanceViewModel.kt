package com.adb.kitty.ui.it.cpu

import com.adb.kitty.*
import com.adb.kitty.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.Locale

class PerformanceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    private val maxHistoryPoints = 30
    private val fpsHistory = ArrayDeque<Float>()
    private val gpuHistory = ArrayDeque<Float>()
    private val cpuHistories = HashMap<Int, ArrayDeque<Float>>()

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val snap = SuHardwareCollector.fetchAllHardwareMetrics()

                // 更新滑动窗口
                pushHistory(fpsHistory, snap.fps)
                pushHistory(gpuHistory, snap.gpu.curFreqGhz)

                val updatedCpus = snap.cpus.map { core ->
                    val deque = cpuHistories.getOrPut(core.coreIndex) { ArrayDeque() }
                    pushHistory(deque, core.curFreqGhz)
                    core.copy(history = deque.toList())
                }

                _uiState.update {
                    PerformanceUiState(
                        renderFps = snap.fps,
                        refreshRateHz = snap.hz,
                        batteryTemp = snap.batteryTemp,
                        fpsHistory = fpsHistory.toList(),
                        gpuMetric = snap.gpu.copy(history = gpuHistory.toList()),
                        cpuCores = updatedCpus
                    )
                }

                delay(1000) // 1s 刷新间隔
            }
        }
    }

    private fun pushHistory(deque: ArrayDeque<Float>, value: Float) {
        if (deque.size >= maxHistoryPoints) deque.removeFirst()
        deque.addLast(value)
    }
}