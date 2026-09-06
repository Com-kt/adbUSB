package com.adb.kitty.ui.it.cpu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
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
            startPollingHardware()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootBinder = null
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

                    val gpuData = binder.gpuMetrics
                    pushHistory(gpuHistory, gpuData[0])
                    val gpuMetric = GpuMetric(
                        curFreqGhz = gpuData[0],
                        minFreqGhz = gpuData[1],
                        maxFreqGhz = gpuData[2],
                        utilizationPercent = gpuData[3],
                        history = gpuHistory.toList()
                    )

                    val sysData = binder.systemMetrics
                    pushHistory(fpsHistory, sysData[1])

                    _uiState.update {
                        PerformanceUiState(
                            batteryTemp = sysData[0],
                            renderFps = sysData[1],
                            refreshRateHz = sysData[2],
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
        super.onCleared()
        try {
            RootService.unbind(serviceConnection)
        } catch (e: Exception) { }
    }
}
