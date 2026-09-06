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

object SuHardwareCollector {

    // 一次 su -c 执行聚合拉取所有 CPU (cpuinfo_*)、GPU 及系统指标
    private const val BATCH_SU_CMD = """
        for c in /sys/devices/system/cpu/cpu[0-9]*; do
            f="${'$'}c/cpufreq"
            if [ -d "${'$'}f" ]; then
                echo "CPU|${'$'}c $(cat ${'$'}f/cpuinfo_cur_freq ${'$'}f/cpuinfo_min_freq ${'$'}f/cpuinfo_max_freq 2>/dev/null | tr '\n' ' ')"
            fi
        done
        cur_gpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq /sys/class/devfreq/gpufreq/cur_freq 2>/dev/null | head -n 1)
        min_gpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/devfreq/*gpu*/min_freq /sys/class/devfreq/*mali*/min_freq /sys/class/devfreq/gpufreq/min_freq 2>/dev/null | head -n 1)
        max_gpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/devfreq/*gpu*/max_freq /sys/class/devfreq/*mali*/max_freq /sys/class/devfreq/gpufreq/max_freq 2>/dev/null | head -n 1)
        busy_gpu=${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*mali*/load 2>/dev/null | head -n 1)
        echo "GPU|cur=${'$'}cur_gpu|min=${'$'}min_gpu|max=${'$'}max_gpu|busy=${'$'}busy_gpu"
        temp=${'$'}(cat /sys/class/power_supply/battery/temp 2>/dev/null || dumpsys battery | awk -F': ' '/temperature/{print ${'$'}2}')
        hz=${'$'}(dumpsys display | grep -E -o "mRenderFrameRate=[0-9.]+|refreshRate [0-9.]+" | head -n 1 | grep -E -o "[0-9.]+")
        fps=${'$'}(cat /sys/class/drm/card0-DSI-1/fps /sys/class/graphics/fb0/measured_fps 2>/dev/null | head -n 1)
        echo "SYS|temp=${'$'}temp|hz=${'$'}hz|fps=${'$'}fps"
    """

    fun fetchAllHardwareMetrics(): RawHardwareSnapshot {
        val cpuList = ArrayList<CpuCoreMetric>()
        var gpuMetric = GpuMetric()
        var fps = 0f
        var hz = 60f
        var temp = 0f

        var process: Process? = null
        var reader: BufferedReader? = null

        try {
            process = ProcessBuilder("su", "-c", BATCH_SU_CMD.trimIndent()).start()
            reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val str = line?.trim() ?: continue
                when {
                    str.startsWith("CPU|") -> {
                        val tokens = str.substringAfter("CPU|").split("\\s+".toRegex())
                        if (tokens.size >= 4) {
                            val coreIdx = tokens[0].substringAfter("cpu").toIntOrNull() ?: continue
                            val cur = (tokens[1].toLongOrNull() ?: 0L) / 1_000_000f
                            val min = (tokens[2].toLongOrNull() ?: 0L) / 1_000_000f
                            val max = (tokens[3].toLongOrNull() ?: 0L) / 1_000_000f
                            cpuList.add(CpuCoreMetric(coreIndex = coreIdx, curFreqGhz = cur, minFreqGhz = min, maxFreqGhz = max))
                        }
                    }
                    str.startsWith("GPU|") -> {
                        val map = parseKv(str.substringAfter("GPU|"))
                        val curKhz = parseToKhz(map["cur"])
                        val minKhz = parseToKhz(map["min"])
                        val maxKhz = parseToKhz(map["max"])
                        val busy = map["busy"]?.replace("%", "")?.split("@")?.firstOrNull()?.toFloatOrNull() ?: 0f
                        gpuMetric = GpuMetric(
                            curFreqGhz = curKhz / 1_000_000f,
                            minFreqGhz = minKhz / 1_000_000f,
                            maxFreqGhz = maxKhz / 1_000_000f,
                            utilizationPercent = busy
                        )
                    }
                    str.startsWith("SYS|") -> {
                        val map = parseKv(str.substringAfter("SYS|"))
                        val rawTemp = map["temp"]?.toFloatOrNull() ?: 0f
                        temp = when {
                            rawTemp > 1000f -> rawTemp / 1000f
                            rawTemp > 100f -> rawTemp / 10f
                            else -> rawTemp
                        }
                        hz = map["hz"]?.toFloatOrNull() ?: 60f
                        fps = map["fps"]?.toFloatOrNull() ?: 0f
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            reader?.close()
            process?.destroy()
        }

        return RawHardwareSnapshot(cpuList, gpuMetric, fps, hz, temp)
    }

    private fun parseKv(raw: String): Map<String, String> {
        return raw.split("|").associate {
            val parts = it.split("=")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        }
    }

    private fun parseToKhz(rawVal: String?): Long {
        val value = rawVal?.trim()?.toLongOrNull() ?: return 0L
        return when {
            value >= 100_000_000L -> value / 1000L
            value >= 100_000L -> value
            value > 0L -> value * 1000L
            else -> 0L
        }
    }

    data class RawHardwareSnapshot(
        val cpus: List<CpuCoreMetric>,
        val gpu: GpuMetric,
        val fps: Float,
        val hz: Float,
        val batteryTemp: Float
    )
}