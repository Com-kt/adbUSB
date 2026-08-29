package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@Keep
class NativeLogColors(
    val error: Int,
    val warn: Int,
    val success: Int,
    val info: Int,
    val debug: Int,
    val trace: Int
)

@Keep
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val errorColor = (if (isDark) Color(0xFFFF8A80) else Color(0xFFC62828)).toArgb()
    val warnColor = (if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)).toArgb()
    val successColor = (if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)).toArgb()
    val infoColor = (if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)).toArgb()
    val debugColor = (if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)).toArgb()
    val traceColor = (if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A)).toArgb()

    val nativeColors = remember(isDark) {
        NativeLogColors(errorColor, warnColor, successColor, infoColor, debugColor, traceColor)
    }

    var updateTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(logUpdateFlow) {
        logUpdateFlow.collectLatest {
            updateTrigger++
        }
    }

    AndroidView(
        factory = { context ->
            // 外层：横向滚动条
            HorizontalScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true

                // 中层：纵向滚动条
                val verticalScrollView = ScrollView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFillViewport = true

                    // 内层：单一 TextView，承载所有文本，支持原生跨行选择
                    val textView = TextView(context).apply {
                        tag = "LOG_TEXT_VIEW"
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setPadding(16, 16, 16, 16)
                        
                        // 核心：开启原生文本选择，单一 TextView 可完美跨多行框选复制
                        setTextIsSelectable(true)
                    }
                    addView(textView)
                }
                addView(verticalScrollView)
            }
        },
        update = { horizontalScrollView ->
            // 读取触发依赖，确保 ViewModel 数据更新时重新构建富文本
            @Suppress("UNUSED_VARIABLE")
            val trigger = updateTrigger

            val verticalScrollView = horizontalScrollView.getChildAt(0) as ScrollView
            val textView = verticalScrollView.findViewWithTag<TextView>("LOG_TEXT_VIEW") ?: return@AndroidView

            val count = getLogCount()
            val ssb = SpannableStringBuilder()

            // 拼装带有颜色的富文本
            for (i in 0 until count) {
                val line = getLogLineAt(i)
                val start = ssb.length
                ssb.append(line)
                val end = ssb.length
                
                val color = determineNativeLogColor(line, nativeColors)
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                
                if (i < count - 1) {
                    ssb.append("\n")
                }
            }

            textView.text = ssb

            // 产生新日志时，自动平滑滚动到底部
            verticalScrollView.post {
                verticalScrollView.fullScroll(View.FOCUS_DOWN)
            }
        },
        modifier = modifier
    )
}

private fun determineNativeLogColor(line: String, colors: NativeLogColors): Int {
    return when {
        line.contains("error") || line.contains("FAIL") || line.contains("[错误]") || line.contains("[FAIL]") -> colors.error
        line.contains("warn") || line.contains("[警告]") || line.contains("[WARN]") -> colors.warn
        line.contains("OKAY") || line.contains("[成功]") || line.contains("[OKAY]") -> colors.success
        line.contains("info") || line.contains("[提示]") || line.contains("[INFO]") -> colors.info
        line.contains("debug") || line.contains("[调试]") || line.contains("[DEBUG]") -> colors.debug
        else -> colors.trace
    }
}
