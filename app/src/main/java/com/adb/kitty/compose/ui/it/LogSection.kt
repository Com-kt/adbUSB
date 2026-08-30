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
import android.view.ViewGroup
import android.widget.HorizontalScrollView
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
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlin.time.Duration.Companion.milliseconds

@Keep
private class LogContainerView(context: Context) : NestedScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val textView = TextView(context)
    var lastRenderedCount = 0
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

        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = getChildAt(0)?.height ?: 0
            val maxScrollY = (contentHeight - height).coerceAtLeast(0)
            isAutoScrollEnabled = (maxScrollY - scrollY) <= 30
        }
    }

    // 显式更新文字颜色（防止变灰色）
    fun setLogTextColor(colorInt: Int) {
        if (textView.currentTextColor != colorInt) {
            textView.setTextColor(colorInt)
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
                val contentHeight = getChildAt(0)?.height ?: 0
                val maxScrollY = (contentHeight - height).coerceAtLeast(0)
                scrollTo(scrollX, maxScrollY)
            }
        }
    }

    fun getTextMetricsParams(): PrecomputedTextCompat.Params {
        return TextViewCompat.getTextMetricsParams(textView)
    }
}

@OptIn(FlowPreview::class)
@Keep
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    modifier: Modifier = Modifier
) {
    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

    // 主题深暗色切换：浅色模式下使用加深纯黑 #111111，深色模式下使用 #EEEEEE
    val isDark = isSystemInDarkTheme()
    val logTextColor = remember(isDark) {
        if (isDark) Color(0xFFEEEEEE).toArgb() else Color(0xFF111111).toArgb()
    }

    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow
            .sample(8.milliseconds)
            .collect {
                val totalCount = getLogCount()
                val lastRendered = view.lastRenderedCount

                if (totalCount < lastRendered) {
                    withContext(Dispatchers.Main) {
                        view.clearLogs()
                    }
                }

                if (totalCount > lastRendered) {
                    val params = withContext(Dispatchers.Main) {
                        view.getTextMetricsParams()
                    }

                    val precomputedText = withContext(Dispatchers.Default) {
                        val spanned = buildSpannedString {
                            for (i in lastRendered until totalCount) {
                                if (i > 0) append("\n")
                                append(getLogLineAt(i))
                            }
                        }
                        PrecomputedTextCompat.create(spanned, params)
                    }

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
        update = { view ->
            // 实时确保文本色彩纯正
            view.setLogTextColor(logTextColor)
        },
        modifier = modifier.clipToBounds()
    )
}
