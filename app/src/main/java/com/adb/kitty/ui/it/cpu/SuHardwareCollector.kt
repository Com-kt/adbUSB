package com.adb.kitty.ui.it.cpu

import com.adb.kitty.*
import com.adb.kitty.R

import java.io.BufferedReader
import java.io.InputStreamReader

object SuHardwareCollector {

    private const val BATCH_SU_CMD =
        "for c in /sys/devices/system/cpu/cpu[0-9]*; do " +
            "if [ -d \"\$c/cpufreq\" ]; then " +
                "cur=\$(cat \$c/cpufreq/cpuinfo_cur_freq \$c/cpufreq/scaling_cur_freq 2>/dev/null | head -n 1); " +
                "min=\$(cat \$c/cpufreq/cpuinfo_min_freq \$c/cpufreq/scaling_min_freq 2>/dev/null | head -n 1); " +
                "max=\$(cat \$c/cpufreq/cpuinfo_max_freq \$c/cpufreq/scaling_max_freq 2>/dev/null | head -n 1); " +
                "[ -z \"\$cur\" ] && cur=0; [ -z \"\$min\" ] && min=0; [ -z \"\$max\" ] && max=0; " +
                "echo \"CPU|\$c|\$cur|\$min|\$max\"; " +
            "fi; " +
        "done; " +
        "cur_gpu=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq /sys/class/devfreq/gpufreq/cur_freq 2>/dev/null | head -n 1); " +
        "min_gpu=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/devfreq/*gpu*/min_freq /sys/class/devfreq/*mali*/min_freq /sys/class/devfreq/gpufreq/min_freq 2>/dev/null | head -n 1); " +
        "max_gpu=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/devfreq/*gpu*/max_freq /sys/class/devfreq/*mali*/max_freq /sys/class/devfreq/gpufreq/max_freq 2>/dev/null | head -n 1); " +
        "busy_gpu=\$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*mali*/load 2>/dev/null | head -n 1); " +
        "echo \"GPU|cur=\$cur_gpu|min=\$min_gpu|max=\$max_gpu|busy=\$busy_gpu\"; " +
        "temp=\$(cat /sys/class/power_supply/battery/temp 2>/dev/null || dumpsys battery | awk -F': ' '/temperature/{print \$2}'); " +
        "hz=\$(dumpsys display | grep -E -o \"mRenderFrameRate=[0-9.]+|refreshRate [0-9.]+\" | head -n 1 | grep -E -o \"[0-9.]+\"); " +
        "fps=\$(cat /sys/class/drm/card0-DSI-1/fps /sys/class/drm/sde-crtc-0/fps /sys/class/graphics/fb0/measured_fps /sys/devices/virtual/graphics/fb0/measured_fps 2>/dev/null | head -n 1); " +
        "[ -z \"\$fps\" ] && fps=\$(dumpsys SurfaceFlinger --latency | wc -l | awk '{if(\$1>2) print \$1-2; else print 0}'); " +
        "echo \"SYS|temp=\$temp|hz=\$hz|fps=\$fps\""

    fun fetchAllHardwareMetrics(): RawHardwareSnapshot {
        val cpuList = ArrayList<CpuCoreMetric>()
        var gpuMetric = GpuMetric()
        var fps = 0f
        var hz = 60f
        var temp = 0f

        var process: Process? = null
        var reader: BufferedReader? = null

        try {
            process = ProcessBuilder("su", "-c", BATCH_SU_CMD).start()
            reader = BufferedReader(InputStreamReader(process.inputStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val str = line?.trim() ?: continue
                when {
                    // 解析 CPU 行: CPU|/sys/devices/system/cpu/cpu0|1804800|800000|2400000
                    str.startsWith("CPU|") -> {
                        val parts = str.split("|")
                        if (parts.size >= 5) {
                            val corePath = parts[1]
                            val coreIdx = corePath.substringAfter("cpu").toIntOrNull() ?: continue
                            val cur = (parts[2].toLongOrNull() ?: 0L) / 1_000_000f
                            val min = (parts[3].toLongOrNull() ?: 0L) / 1_000_000f
                            val max = (parts[4].toLongOrNull() ?: 0L) / 1_000_000f
                            cpuList.add(
                                CpuCoreMetric(
                                    coreIndex = coreIdx,
                                    curFreqGhz = cur,
                                    minFreqGhz = min,
                                    maxFreqGhz = max
                                )
                            )
                        }
                    }
                    // 解析 GPU 行: GPU|cur=...|min=...|max=...|busy=...
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
                    // 解析 SYS 行: SYS|temp=...|hz=...|fps=...
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

        return RawHardwareSnapshot(
            cpus = cpuList.sortedBy { it.coreIndex },
            gpu = gpuMetric,
            fps = fps,
            hz = hz,
            batteryTemp = temp
        )
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
            value >= 100_000_000L -> value / 1000L // 针对某些以 Hz 输出的 GPU 节点
            value >= 100_000L -> value            // 针对标准的 kHz 节点
            value > 0L -> value * 1000L            // 针对以 MHz 输出的节点
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
