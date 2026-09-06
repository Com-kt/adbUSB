package com.adb.kitty.ui.it.cpu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

class PerformanceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    private var rootBinder: ICpuBinder? = null
    private val maxHistoryPoints = 30
    private val fpsHistory = ArrayDeque<Float>()
    private val gpuHistory = ArrayDeque<Float>()
    private val cpuHistories = HashMap<Int, ArrayDeque<Float>>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootBinder = ICpuBinder.Stub.asInterface(service)
            _uiState.update { it.copy(isRootConnected = true) }
            startPollingHardware()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootBinder = null
            _uiState.update { it.copy(isRootConnected = false) }
        }
    }

    fun bindRootService(context: Context) {
        if (rootBinder != null) return
        val intent = Intent(context, GhzRootService::class.java)
        RootService.bind(intent, serviceConnection)
    }

    private fun startPollingHardware() {
        viewModelScope.launch(Dispatchers.IO) {
            while (rootBinder != null) {
                try {
                    val binder = rootBinder ?: break

                    // 1. 读取所有 CPU 物理核心主频
                    val cpuFreqs = binder.cpuCurrentFreqs
                    val cpuMetrics = cpuFreqs.mapIndexed { index, curGhz ->
                        val limits = binder.getCpuCoreLimits(index)
                        val deque = cpuHistories.getOrPut(index) { ArrayDeque() }
                        pushHistory(deque, curGhz)

                        CpuCoreMetric(
                            coreIndex = index,
                            curFreqGhz = curGhz,
                            minFreqGhz = limits[0],
                            maxFreqGhz = limits[1],
                            history = deque.toList()
                        )
                    }

                    // 2. 读取 GPU 核心指标
                    val gpuData = binder.gpuMetrics
                    pushHistory(gpuHistory, gpuData[0])
                    val gpuMetric = GpuMetric(
                        curFreqGhz = gpuData[0],
                        minFreqGhz = gpuData[1],
                        maxFreqGhz = gpuData[2],
                        utilizationPercent = gpuData[3],
                        history = gpuHistory.toList()
                    )

                    // 3. 读取系统热状态与帧率
                    val sysData = binder.systemMetrics

                    val currentHz = if (sysData[2] > 0f) sysData[2] else 60f
                    val currentFps = if (sysData[1] > 0f) sysData[1] else currentHz

                    pushHistory(fpsHistory, currentFps)

                    _uiState.update {
                        it.copy(
                            isRootConnected = true,
                            batteryTemp = sysData[0],
                            renderFps = currentFps,
                            refreshRateHz = currentHz,
                            fpsHistory = fpsHistory.toList(),
                            gpuMetric = gpuMetric,
                            cpuCores = cpuMetrics
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1000)
            }
        }
    }

    private fun pushHistory(deque: ArrayDeque<Float>, value: Float) {
        if (deque.size >= maxHistoryPoints) deque.removeFirst()
        deque.addLast(value)
    }

    override fun onCleared() {
        try {
            RootService.unbind(serviceConnection)
        } catch (e: Exception) { }
    }
}
