package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.widget.TextViewCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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
private class LogContainerView(context: Context) : NestedScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val textView = TextView(context)
    var lastRenderedCount = 0

    // 控制是否开启自动追尾滚动
    var isAutoScrollEnabled = true

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true

        horizontalScrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        horizontalScrollView.isFillViewport = true

        textView.apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)

        // NestedScrollView 的滑动监听，精准捕捉滑动距离与尾部判断
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = horizontalScrollView.height
            val scrollViewHeight = height
            val maxScrollY = (contentHeight - scrollViewHeight).coerceAtLeast(0)
            isAutoScrollEnabled = (maxScrollY - scrollY) <= 30
        }
    }

    fun clearLogs() {
        textView.text = ""
        lastRenderedCount = 0
        scrollTo(0, 0)
    }

    fun appendPrecomputedText(precomputedText: PrecomputedTextCompat, newCount: Int) {
        textView.append(precomputedText)
        lastRenderedCount = newCount

        if (isAutoScrollEnabled) {
            post {
                val contentHeight = horizontalScrollView.height
                val scrollViewHeight = height
                val maxScrollY = (contentHeight - scrollViewHeight).coerceAtLeast(0)
                scrollTo(scrollX, maxScrollY)
            }
        }
    }

    fun getTextMetricsParams(): PrecomputedTextCompat.Params {
        return TextViewCompat.getTextMetricsParams(textView)
    }
}

private fun determineNativeLogColor(line: String, colors: NativeLogColors): Int {
    return when {
        // 1. Error / Fatal
        line.contains("E/") || line.contains("F/") || line.contains(" E ") || line.contains(" F ") ||
        line.contains("[错误]") || line.contains("[FAIL]") -> colors.error

        // 2. Warn
        line.contains("W/") || line.contains(" W ") ||
        line.contains("[警告]") || line.contains("[WARN]") -> colors.warn

        // 3. Success
        line.contains("[成功]") || line.contains("[OKAY]") || line.contains("[OK]") -> colors.success

        // 4. Info
        line.contains("I/") || line.contains(" I ") ||
        line.contains("[提示]") || line.contains("[INFO]") -> colors.info

        // 5. Debug / Verbose
        line.contains("D/") || line.contains("V/") || line.contains(" D ") || line.contains(" V ") ||
        line.contains("[调试]") || line.contains("[DEBUG]") -> colors.debug

        else -> colors.trace
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

    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

    // 使用 LaunchedEffect 结合 Dispatchers.Default 在后台异步完成计算
    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow.collect {
            val totalCount = getLogCount()
            val lastRendered = view.lastRenderedCount

            if (totalCount < lastRendered) {
                withContext(Dispatchers.Main) {
                    view.clearLogs()
                }
            }

            if (totalCount > lastRendered) {
                // 1. 获取当前 TextView 测量参数 (Main 线程轻量获取)
                val params = view.getTextMetricsParams()

                // 2. 在 Dispatchers.Default 后台子线程中完成：DSL 富文本构建 + TextLayout 耗时测量预排版
                val precomputedText = withContext(Dispatchers.Default) {
                    val spanned = buildSpannedString {
                        for (i in lastRendered until totalCount) {
                            if (length > 0) append("\n")
                            val line = getLogLineAt(i)
                            color(determineNativeLogColor(line, nativeColors)) {
                                append(line)
                            }
                        }
                    }
                    PrecomputedTextCompat.create(spanned, params)
                }

                // 3. 切回 Main 线程直接挂载，避免阻塞 UI 线程
                withContext(Dispatchers.Main) {
                    view.appendPrecomputedText(precomputedText, totalCount)
                }
            }
        }
    }

    AndroidView(
        factory = { context ->
            LogContainerView(context).also {
                containerView = it
            }
        },
        update = { /* 无需在 AndroidView.update 中做同步重绘 */ },
        modifier = modifier.clipToBounds()
    )
}
