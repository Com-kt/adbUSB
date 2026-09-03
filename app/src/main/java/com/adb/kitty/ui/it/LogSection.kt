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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
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

        if (textView.text.toString() != fullText) {
            // 使用 BufferType.NORMAL 降低 Editor 内部 Spannable 逻辑消耗
            textView.setText(fullText, TextView.BufferType.NORMAL)
        }

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

    fun releaseMemory() {
        textView.text = ""
        textView.destroyDrawingCache()
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
    uiUpdateVersionFlow: StateFlow<Long>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var containerView by remember { mutableStateOf<LogContainerView?>(null) }

    val isDark = isSystemInDarkTheme()
    val logTextColor = remember(isDark) {
        if (isDark) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    }

    // 1. 低内存回调清理 UI 渲染层
    LaunchedEffect(context) {
        val app = context.applicationContext as? BypassApi ?: return@LaunchedEffect

        @Suppress("DEPRECATION")
        app.trimMemoryEvents.collect { level ->
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            ) {
                containerView?.releaseMemory()
            }
        }
    }

    // 2. 只有前台 (STARTED) 状态才采样更新 UI，切回前台自动触发 StateFlow 重绘恢复
    LaunchedEffect(uiUpdateVersionFlow, containerView, lifecycleOwner) {
        val view = containerView ?: return@LaunchedEffect

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            uiUpdateVersionFlow
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
    }

    DisposableEffect(Unit) {
        onDispose {
            containerView?.releaseMemory()
            containerView = null
        }
    }

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
