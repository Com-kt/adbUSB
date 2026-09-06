package com.adb.kitty.ui.it.cpu

import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

@Keep
class GhzRootService : RootService() {

    override fun onBind(intent: Intent): IBinder {
        return object : ICpuBinder.Stub() {

            override fun getCpuCurrentFreqs(): FloatArray {
                val freqs = ArrayList<Float>()
                var index = 0
                while (true) {
                    val cpuDir = File("/sys/devices/system/cpu/cpu$index")
                    if (!cpuDir.exists()) break

                    val curFile = File(cpuDir, "cpufreq/cpuinfo_cur_freq")
                    val khz = curFile.readTextOrZero()

                    freqs.add(khz / 1_000_000f) // kHz -> GHz
                    index++
                }
                return freqs.toFloatArray()
            }

            override fun getCpuCoreLimits(core: Int): FloatArray {
                val cpuDir = File("/sys/devices/system/cpu/cpu$core/cpufreq")
                if (!cpuDir.exists()) return floatArrayOf(0f, 0f, 0f)

                val minKhz = File(cpuDir, "cpuinfo_min_freq").readTextOrZero()
                val maxKhz = File(cpuDir, "cpuinfo_max_freq").readTextOrZero()
                val curKhz = File(cpuDir, "cpuinfo_cur_freq").readTextOrZero()

                return floatArrayOf(
                    minKhz / 1_000_000f,
                    maxKhz / 1_000_000f,
                    curKhz / 1_000_000f
                )
            }

            override fun getGpuMetrics(): FloatArray {
                val curHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq").readTextOrZero()
                val minHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/min_freq").readTextOrZero()
                val maxHz = File("/sys/class/kgsl/kgsl-3d0/devfreq/max_freq").readTextOrZero()
                val load = File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").readTextOrZero()

                return floatArrayOf(
                    curHz / 1_000_000_000f, // Hz -> GHz
                    minHz / 1_000_000_000f,
                    maxHz / 1_000_000_000f,
                    load
                )
            }

            override fun getSystemMetrics(): FloatArray {
                // 1. 电池温度多节点搜索
                val batPaths = arrayOf(
                    "/sys/class/power_supply/battery/temp",
                    "/sys/class/power_supply/bms/temp"
                )
                var batRaw = 0f
                for (path in batPaths) {
                    val file = File(path)
                    if (file.exists()) {
                        batRaw = file.readTextOrZero()
                        if (batRaw > 0f) break
                    }
                }
                val temp = when {
                    batRaw > 1000f -> batRaw / 1000f
                    batRaw > 100f -> batRaw / 10f
                    else -> batRaw
                }

                val fpsPaths = arrayOf(
                    "/sys/class/drm/sde-crtc-0/measured_fps",
                    "/sys/class/mi_display/disp-DSI-0/fps",
                    "/sys/class/graphics/fb0/measured_fps",
                    "/sys/class/drm/card0-DSI-1/fps"
                )
                var fps = 0f
                for (path in fpsPaths) {
                    val file = File(path)
                    if (file.exists()) {
                        val value = file.readTextOrZero()
                        if (value > 0f) {
                            fps = value
                            break
                        }
                    }
                }

                val hzPaths = arrayOf(
                    "/sys/class/drm/sde-crtc-0/fps",
                    "/sys/class/mi_display/disp-DSI-0/disp_param",
                    "/sys/class/drm/card0-DSI-1/mode"
                )
                var hz = 0f
                for (path in hzPaths) {
                    val file = File(path)
                    if (file.exists()) {
                        val text = try { file.readText().trim() } catch (e: Exception) { "" }
                        val parsedHz = text.substringAfter("@", "")
                            .substringBefore("Hz")
                            .substringBefore(" ")
                            .toFloatOrNull() ?: text.toFloatOrNull()
                        if (parsedHz != null && parsedHz > 0f) {
                            hz = parsedHz
                            break
                        }
                    }
                }

                return floatArrayOf(temp, fps, hz)
            }

            private fun File.readTextOrZero(): Float {
                return try {
                    if (exists()) readText().trim().replace("%", "").toFloatOrNull() ?: 0f else 0f
                } catch (e: Exception) {
                    0f
                }
            }
        }
    }
}
