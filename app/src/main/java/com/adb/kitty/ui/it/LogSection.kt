package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

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
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)

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

    /**
     * 显式清空 View 内存与内部 CharSequence 引用
     */
    fun releaseMemory() {
        textView.text = ""
        removeAllViews()
        horizontalScrollView.removeAllViews()
        setOnScrollChangeListener(null as OnScrollChangeListener?)
    }
}

@OptIn(FlowPreview::class)
@Keep
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    onTrimMemoryRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

    val isDark = isSystemInDarkTheme()
    val logTextColor = remember(isDark) {
        if (isDark) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    }

    // 1. 显式注册组件级内存回调与 Lifecycle 解绑处理（脱离 Application 类依赖）
    DisposableEffect(context) {
        val applicationContext = context.applicationContext

        val memoryCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // 系统内存紧张时显式触发释放
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                ) {
                    onTrimMemoryRequested()
                    containerView?.releaseMemory()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
            override fun onLowMemory() {
                onTrimMemoryRequested()
                containerView?.releaseMemory()
            }
        }

        applicationContext.registerComponentCallbacks(memoryCallback)

        onDispose {
            // LogSection 退出 Compose 视图树时显式释放一切 View 与 Callback 引用
            applicationContext.unregisterComponentCallbacks(memoryCallback)
            containerView?.releaseMemory()
            containerView = null
        }
    }

    // 2. 日志采样更新逻辑
    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow
            .sample(100.milliseconds)
            .collect {
                val totalCount = getLogCount()

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

    // 3. 原生 View 渲染容器
    AndroidView(
        factory = { ctx ->
            LogContainerView(ctx).also {
                containerView = it
            }
        },
        update = { view ->
            view.setLogTextColor(logTextColor)
        },
        modifier = modifier.clipToBounds()
    )
}
