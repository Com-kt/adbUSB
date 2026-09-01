package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * 原生 TextView 极简日志视图
 * 全自动兼容 HyperOS / MIUI 系统级“问小爱”、AI 分析、翻译、选择游标与放大镜
 */
@Keep
private class LogContainerView(context: Context) : NestedScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val textView = TextView(context)
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
            
            // 核心配置：开启原生可选中功能，全自动挂载系统的 AI/问小爱/翻译/搜索浮动菜单
            setTextIsSelectable(true)
            
            // 禁止自动换行，允许在 HorizontalScrollView 内横向滚动
            setHorizontallyScrolling(true)
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)

        // 监听滚动状态，判断用户是否向上翻阅日志（翻阅时暂时停用自动置底）
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = getChildAt(0)?.height ?: 0
            val maxScrollY = (contentHeight - height).coerceAtLeast(0)
            isAutoScrollEnabled = (maxScrollY - scrollY) <= 30
        }
    }

    fun updateLogs(fullText: String, textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }
        textView.text = fullText

        if (isAutoScrollEnabled) {
            post {
                val contentHeight = getChildAt(0)?.height ?: 0
                val maxScrollY = (contentHeight - height).coerceAtLeast(0)
                scrollTo(scrollX, maxScrollY)
            }
        }
    }

    fun setLogTextColor(textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }
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

    val isDark = isSystemInDarkTheme()
    val logTextColor = remember(isDark) {
        if (isDark) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    }

    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow
            .sample(100.milliseconds) // 节流采样，避免高频日志冲刷导致 TextView 频繁触发重新布局
            .collect {
                val totalCount = getLogCount()

                // 子线程并发构建巨型字符串，不占用主线程耗时
                val fullText = withContext(Dispatchers.Default) {
                    val sb = StringBuilder()
                    for (i in 0 until totalCount) {
                        sb.append(getLogLineAt(i))
                        if (i < totalCount - 1) {
                            sb.append('\n')
                        }
                    }
                    sb.toString()
                }

                withContext(Dispatchers.Main) {
                    view.updateLogs(fullText, logTextColor)
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
            // 响应 Compose 深浅色主题切换，仅更变文字颜色，绝不重复生成字符串
            view.setLogTextColor(logTextColor)
        },
        modifier = modifier.clipToBounds()
    )
}
