package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.os.Build
import android.content.Context
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

/**
 * 自定义高性能日志容器，内部记录已渲染的行数，实现增量追加与精准底部滚动
 */
private class LogContainerView(context: Context) : HorizontalScrollView(context) {
    val verticalScrollView = ScrollView(context)
    val textView = TextView(context)
    var lastRenderedCount = 0

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true

        verticalScrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        verticalScrollView.isFillViewport = true

        textView.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
            // 保持跨行文本框选复制能力
            setTextIsSelectable(true)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (this as android.widget.TextView).setMagnifierEnabled(false)
            }
        }

        verticalScrollView.addView(textView)
        addView(verticalScrollView)
    }

    fun updateLogs(
        totalCount: Int,
        getLogLineAt: (Int) -> String,
        colors: NativeLogColors
    ) {
        // 1. 如果日志被清空，重置视图
        if (totalCount < lastRenderedCount) {
            textView.text = ""
            lastRenderedCount = 0
        }

        // 2. 仅在有新日志追加时执行增量拼接
        if (totalCount > lastRenderedCount) {
            val ssb = SpannableStringBuilder()

            for (i in lastRenderedCount until totalCount) {
                if (i > 0) {
                    ssb.append("\n")
                }
                val line = getLogLineAt(i)
                val start = ssb.length
                ssb.append(line)
                val end = ssb.length

                val color = determineNativeLogColor(line, colors)
                ssb.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 增量追加，不销毁旧文本与 Layout
            textView.append(ssb)
            lastRenderedCount = totalCount

            // 3. 平滑/直接滚动到最底部，跟随日志输出
            verticalScrollView.post {
                verticalScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun determineNativeLogColor(line: String, colors: NativeLogColors): Int {
        return when {
            line.contains("F") || line.contains("E") || line.contains("error") || line.contains("FAIL") || line.contains("[错误]") || line.contains("[FAIL]") -> colors.error
            line.contains("W") || line.contains("warn") || line.contains("[警告]") || line.contains("[WARN]") -> colors.warn
            line.contains("OKAY") || line.contains("[成功]") || line.contains("[OKAY]") -> colors.success
            line.contains("I") || line.contains("info") || line.contains("[提示]") || line.contains("[INFO]") -> colors.info
            line.contains("V") || line.contains("D") || line.contains("debug") || line.contains("[调试]") || line.contains("[DEBUG]") -> colors.debug
            else -> colors.trace
        }
    }
}

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
            LogContainerView(context)
        },
        update = { containerView ->
            @Suppress("UNUSED_VARIABLE")
            val trigger = updateTrigger
            
            // 触发增量更新
            containerView.updateLogs(
                totalCount = getLogCount(),
                getLogLineAt = getLogLineAt,
                colors = nativeColors
            )
        },
        modifier = modifier
    )
}
