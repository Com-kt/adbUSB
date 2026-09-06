package com.adb.kitty.ui.it.cpu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Choreographer
import android.view.Display
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
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.io.File

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

data class HistoryRecording(
    val file: File,
    val formattedDate: String,
    val durationSeconds: Int,
    val samples: List<PerformanceSample> = emptyList()
)

data class PerformanceSample(
    val timestampMs: Long,
    val fps: Float,
    val refreshRate: Float,
    val batteryTemp: Float,
    val gpuFreqGhz: Float,
    val gpuLoadPercent: Float,
    val cpuFreqsGhz: List<Float>
)

@Immutable
data class PerformanceUiState(
    val isRootConnected: Boolean = false,
    val renderFps: Float = 0f,
    val refreshRateHz: Float = 0f,
    val batteryTemp: Float = 0f,
    val fpsHistory: List<Float> = emptyList(),
    val gpuMetric: GpuMetric = GpuMetric(),
    val cpuCores: List<CpuCoreMetric> = emptyList(),
    
    // 屏幕显示参数
    val currentResolution: String = "",
    val supportedDisplayModes: List<String> = emptyList(),

    // 录制控制状态
    val isRecording: Boolean = false,
    val recordedDurationSeconds: Int = 0,
    val exportCsvContent: String? = null
    
    val historyFiles: List<File> = emptyList(),
    val selectedHistory: HistoryRecording? = null
)

class PerformanceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(PerformanceUiState())
    val uiState: StateFlow<PerformanceUiState> = _uiState.asStateFlow()

    private var rootBinder: ICpuBinder? = null
    private val maxHistoryPoints = 30
    private val fpsHistory = ArrayDeque<Float>()
    private val gpuHistory = ArrayDeque<Float>()
    private val cpuHistories = HashMap<Int, ArrayDeque<Float>>()

    // 录制相关私有变量
    private val recordingBuffer = mutableListOf<PerformanceSample>()
    private var recordingStartTimeMs: Long = 0L

    // 屏幕测速 API
    private var displayManager: DisplayManager? = null
    private var frameCount = 0
    private var lastFpsCalculateTime = System.currentTimeMillis()
    private var currentCalculatedFps = 60f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = System.currentTimeMillis()
            val delta = now - lastFpsCalculateTime
            if (delta >= 1000) {
                currentCalculatedFps = (frameCount * 1000f) / delta
                frameCount = 0
                lastFpsCalculateTime = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

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

    fun initAndBind(context: Context) {
        if (displayManager == null) {
            displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        updateDisplayCapabilities()

        if (rootBinder == null) {
            val intent = Intent(context, GhzRootService::class.java)
            RootService.bind(intent, serviceConnection)
        }
    }

    private fun updateDisplayCapabilities() {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val currentMode = display.mode

        val curW = maxOf(currentMode.physicalWidth, currentMode.physicalHeight)
        val curH = minOf(currentMode.physicalWidth, currentMode.physicalHeight)
        val resFormatted = "${curW}×${curH}"

        val modes = display.supportedModes.map { mode ->
            val w = maxOf(mode.physicalWidth, mode.physicalHeight)
            val h = minOf(mode.physicalWidth, mode.physicalHeight)
            val hz = mode.refreshRate.toInt()
            "${w}×${h} @ ${hz}Hz"
        }.distinct()

        _uiState.update {
            it.copy(
                currentResolution = resFormatted,
                supportedDisplayModes = modes
            )
        }
    }

    private fun getActiveRefreshRate(): Float {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.refreshRate ?: display?.mode?.refreshRate ?: 60f
    }

    // 手动点击：开始录制
    fun startRecording() {
        recordingStartTimeMs = System.currentTimeMillis()
        synchronized(recordingBuffer) {
            recordingBuffer.clear()
        }
        _uiState.update {
            it.copy(
                isRecording = true,
                recordedDurationSeconds = 0,
                exportCsvContent = null
            )
        }
    }

    // 手动点击：停止录制
    fun stopRecording() {
        val csv = generateCsvData()
        _uiState.update {
            it.copy(
                isRecording = false,
                recordedDurationSeconds = 0,
                exportCsvContent = csv
            )
        }
    }

    fun clearExportData() {
        _uiState.update { it.copy(exportCsvContent = null) }
    }

    private fun startPollingHardware() {
        viewModelScope.launch(Dispatchers.IO) {
            while (rootBinder != null) {
                try {
                    val binder = rootBinder ?: break

                    // 1. CPU
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

                    // 2. GPU
                    val gpuData = binder.gpuMetrics
                    pushHistory(gpuHistory, gpuData[0])
                    val gpuMetric = GpuMetric(
                        curFreqGhz = gpuData[0],
                        minFreqGhz = gpuData[1],
                        maxFreqGhz = gpuData[2],
                        utilizationPercent = gpuData[3],
                        history = gpuHistory.toList()
                    )

                    // 3. System
                    val sysData = binder.systemMetrics
                    val temp = sysData[0]

                    // 4. Display & FPS
                    val activeHz = getActiveRefreshRate()
                    val realFps = currentCalculatedFps.coerceAtMost(activeHz)

                    pushHistory(fpsHistory, realFps)

                    val isRecordingActive = _uiState.value.isRecording
                    var durationSec = 0

                    if (isRecordingActive) {
                        val nowMs = System.currentTimeMillis()
                        durationSec = ((nowMs - recordingStartTimeMs) / 1000).toInt()

                        synchronized(recordingBuffer) {
                            recordingBuffer.add(
                                PerformanceSample(
                                    timestampMs = nowMs,
                                    fps = realFps,
                                    refreshRate = activeHz,
                                    batteryTemp = temp,
                                    gpuFreqGhz = gpuData[0],
                                    gpuLoadPercent = gpuData[3],
                                    cpuFreqsGhz = cpuFreqs.toList()
                                )
                            )
                        }
                    }

                    _uiState.update { state ->
                        state.copy(
                            isRootConnected = true,
                            batteryTemp = temp,
                            renderFps = realFps,
                            refreshRateHz = activeHz,
                            fpsHistory = fpsHistory.toList(),
                            gpuMetric = gpuMetric,
                            cpuCores = cpuMetrics,
                            recordedDurationSeconds = if (isRecordingActive) durationSec else 0
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1000)
            }
        }
    }

    private fun generateCsvData(): String {
        val samples = synchronized(recordingBuffer) { recordingBuffer.toList() }
        if (samples.isEmpty()) return ""

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()

        sb.append("Time,Timestamp(ms),FPS,RefreshRate(Hz),BatteryTemp(°C),GPU_Freq(GHz),GPU_Load(%)")
        val maxCpuCount = samples.maxOfOrNull { it.cpuFreqsGhz.size } ?: 0
        for (i in 0 until maxCpuCount) {
            sb.append(",CPU$i(GHz)")
        }
        sb.append("\n")

        for (sample in samples) {
            val timeStr = dateFormat.format(Date(sample.timestampMs))
            sb.append(String.format(Locale.US, "%s,%d,%.2f,%.2f,%.1f,%.3f,%.1f",
                timeStr, sample.timestampMs, sample.fps, sample.refreshRate,
                sample.batteryTemp, sample.gpuFreqGhz, sample.gpuLoadPercent
            ))

            for (i in 0 until maxCpuCount) {
                val freq = sample.cpuFreqsGhz.getOrNull(i) ?: 0f
                sb.append(String.format(Locale.US, ",%.3f", freq))
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun pushHistory(deque: ArrayDeque<Float>, value: Float) {
        if (deque.size >= maxHistoryPoints) deque.removeFirst()
        deque.addLast(value)
    }

    override fun onCleared() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        try {
            RootService.unbind(serviceConnection)
        } catch (e: Exception) { }
    }
    
    private fun getCpuFolder(context: Context): File {
        val cpuFolder = File(context.getExternalFilesDir(null), "cpu")
        if (!cpuFolder.exists()) {
            cpuFolder.mkdirs()
        }
        return cpuFolder
    }

    // 刷新已保存的历史录制文件列表
    fun refreshSavedFiles(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val folder = getCpuFolder(context)
            val files = folder.listFiles { file -> file.extension.lowercase() == "csv" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            _uiState.update { it.copy(historyFiles = files) }
        }
    }

    // 停止录制并自动保存到 cpu 目录
    fun stopRecordingAndSave(context: Context): File? {
        val csv = generateCsvData()
        _uiState.update { 
            it.copy(
                isRecording = false, 
                recordedDurationSeconds = 0, 
                exportCsvContent = csv 
            ) 
        }
        
        if (csv.isEmpty()) return null

        return try {
            val folder = getCpuFolder(context)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(folder, "cpu_record_$timeStamp.csv")
            file.writeText(csv)
            
            refreshSavedFiles(context)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 删除指定的历史文件
    fun deleteHistoryFile(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
            }
            if (_uiState.value.selectedHistory?.file == file) {
                _uiState.update { it.copy(selectedHistory = null) }
            }
            refreshSavedFiles(context)
        }
    }

    // 从 CSV 文件解析出离线采样数据并用于图形展示
    fun loadHistoryFromFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = parseCsvFile(file)
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(file.lastModified()))
            
            val record = HistoryRecording(
                file = file,
                formattedDate = dateStr,
                durationSeconds = samples.size,
                samples = samples
            )

            _uiState.update { it.copy(selectedHistory = record) }
        }
    }

    // 清除选中的历史解析数据（返回列表）
    fun clearSelectedHistory() {
        _uiState.update { it.copy(selectedHistory = null) }
    }

    // 反解析 CSV 内容
    private fun parseCsvFile(file: File): List<PerformanceSample> {
        val samples = mutableListOf<PerformanceSample>()
        if (!file.exists()) return samples

        try {
            val lines = file.readLines()
            if (lines.size <= 1) return samples // 只有表头或为空

            for (i in 1 until lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                val tokens = line.split(",")
                if (tokens.size >= 7) {
                    val timestampMs = tokens[1].toLongOrNull() ?: 0L
                    val fps = tokens[2].toFloatOrNull() ?: 0f
                    val refreshRate = tokens[3].toFloatOrNull() ?: 60f
                    val batteryTemp = tokens[4].toFloatOrNull() ?: 0f
                    val gpuFreqGhz = tokens[5].toFloatOrNull() ?: 0f
                    val gpuLoadPercent = tokens[6].toFloatOrNull() ?: 0f
                    
                    val cpuFreqs = if (tokens.size > 7) {
                        tokens.subList(7, tokens.size).mapNotNull { it.toFloatOrNull() }
                    } else emptyList()

                    samples.add(
                        PerformanceSample(
                            timestampMs = timestampMs,
                            fps = fps,
                            refreshRate = refreshRate,
                            batteryTemp = batteryTemp,
                            gpuFreqGhz = gpuFreqGhz,
                            gpuLoadPercent = gpuLoadPercent,
                            cpuFreqsGhz = cpuFreqs
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return samples
    }
}
