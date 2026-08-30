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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.text.buildSpannedString
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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

        // NestedScrollView 滑动监听：精准捕捉滑动距离与底部追尾状态
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = getChildAt(0)?.height ?: 0
            val maxScrollY = (contentHeight - height).coerceAtLeast(0)
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

@Keep
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    modifier: Modifier = Modifier
) {
    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

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
                // 1. 主线程获取当前 TextView 测量参数
                val params = withContext(Dispatchers.Main) {
                    view.getTextMetricsParams()
                }

                // 2. 在 Dispatchers.Default 后台子线程中完成：纯文本拼接 + 耗时预排版
                val precomputedText = withContext(Dispatchers.Default) {
                    val spanned = buildSpannedString {
                        for (i in lastRendered until totalCount) {
                            if (i > 0) {
                                append("\n")
                            }
                            append(getLogLineAt(i))
                        }
                    }
                    PrecomputedTextCompat.create(spanned, params)
                }

                // 3. 切回 Main 线程挂载渲染结果
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
        update = { /* 由 LaunchedEffect 驱动更新 */ },
        modifier = modifier.clipToBounds()
    )
}
