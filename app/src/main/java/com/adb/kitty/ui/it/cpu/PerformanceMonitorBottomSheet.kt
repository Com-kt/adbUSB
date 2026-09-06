package com.adb.kitty.ui.it.cpu

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

val CoreColors = listOf(
    Color(0xFF2196F3), Color(0xFF03A9F4), Color(0xFF00BCD4), Color(0xFF009688),
    Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFFE91E63)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompletePerformanceMonitorBottomSheet(
    uiState: PerformanceUiState,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚡ 硬件性能与热状态监控",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (uiState.isRootConnected) "ROOT ACTIVE" else "CONNECTING",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (uiState.isRootConnected) Color.Red else Color.Gray,
                    modifier = Modifier
                        .background(
                            (if (uiState.isRootConnected) Color.Red else Color.Gray).copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // 1. 全局系统指标
            SystemSummaryCard(state = uiState)

            // 2. GPU 指标
            GpuMetricCard(gpu = uiState.gpuMetric)

            // 3. CPU 核心集群
            Text(
                text = "CPU 核心集群 (${uiState.cpuCores.size} Cores / IPC Pure HW)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // 4. 双列网格并排展示核心
            uiState.cpuCores.chunked(2).forEach { rowCores ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowCores.forEach { core ->
                        SingleCoreCard(core = core, modifier = Modifier.weight(1f))
                    }
                    if (rowCores.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun SystemSummaryCard(state: PerformanceUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "帧率波动", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format(Locale.US, "%.2f FPS", state.renderFps),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF4CAF50)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "屏幕刷新率", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = String.format(Locale.US, "%.2f Hz", state.refreshRateHz),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "电池温度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val tempColor = if (state.batteryTemp >= 40f) Color.Red else Color(0xFFFF9800)
                    Text(
                        text = String.format(Locale.US, "%.1f °C", state.batteryTemp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = tempColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            MetricLineChart(
                data = state.fpsHistory,
                minVal = 0f,
                maxVal = (state.refreshRateHz.takeIf { it > 0f } ?: 120f),
                lineColor = Color(0xFF4CAF50),
                drawGridLines = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(55.dp)
            )
        }
    }
}

@Composable
fun GpuMetricCard(gpu: GpuMetric) {
    val gpuColor = Color(0xFF9C27B0)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "GPU 核心", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format(Locale.US, "%d%% Load", gpu.utilizationPercent.toInt()),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = gpuColor,
                            modifier = Modifier
                                .background(gpuColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = String.format(Locale.US, "Limit: %.3f - %.3f GHz", gpu.minFreqGhz, gpu.maxFreqGhz),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = String.format(Locale.US, "%.3f GHz", gpu.curFreqGhz),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = gpuColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            MetricLineChart(
                data = gpu.history,
                minVal = 0f,
                maxVal = if (gpu.maxFreqGhz > 0f) gpu.maxFreqGhz else 1.5f,
                lineColor = gpuColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )
        }
    }
}

@Composable
fun SingleCoreCard(core: CpuCoreMetric, modifier: Modifier = Modifier) {
    val lineColor = CoreColors[core.coreIndex % CoreColors.size]

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Core ${core.coreIndex}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = String.format(Locale.US, "HW: %.2f-%.2fG", core.minFreqGhz, core.maxFreqGhz),
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = String.format(Locale.US, "%.2f GHz", core.curFreqGhz),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = lineColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            MetricLineChart(
                data = core.history,
                minVal = 0f,
                maxVal = if (core.maxFreqGhz > 0f) core.maxFreqGhz else 3.2f,
                lineColor = lineColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            )
        }
    }
}

@Composable
fun MetricLineChart(
    data: List<Float>,
    minVal: Float,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
    drawGridLines: Boolean = false
) {
    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val range = (maxVal - minVal).coerceAtLeast(0.1f)
        val dx = w / (data.size - 1).coerceAtLeast(1)

        if (drawGridLines) {
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            for (i in 1..2) {
                val y = h * (i / 3f)
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dashEffect
                )
            }
        }

        val linePath = Path()
        val fillPath = Path()

        data.forEachIndexed { index, value ->
            val normalized = ((value - minVal) / range).coerceIn(0f, 1f)
            val x = index * dx
            val y = h - (normalized * h)

            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo(w, h)
        fillPath.close()

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)
            )
        )

        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 1.8.dp.toPx())
        )
    }
}
