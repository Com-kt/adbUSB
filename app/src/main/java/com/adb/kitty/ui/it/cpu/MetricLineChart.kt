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