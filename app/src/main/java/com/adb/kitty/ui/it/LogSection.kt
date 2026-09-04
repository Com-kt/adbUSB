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
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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
    private var isUserTouching = false
    private var isScrolling = false
    private var isUpdatingText = false
    private var lastLoadedText: String? = null

    private val scrollIdleHandler = Handler(Looper.getMainLooper())
    
    private val scrollIdleRunnable = Runnable {
        isScrolling = false
        if (!isUserTouching) {
            if (isAtBottom()) {
                isAutoScrollEnabled = true
                scrollToBottom()
            }
        }
    }

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
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)

        setOnScrollChangeListener { _, _, _, _, _ ->
            if (!isUpdatingText) {
                isScrolling = true
                scrollIdleHandler.removeCallbacks(scrollIdleRunnable)
                scrollIdleHandler.postDelayed(scrollIdleRunnable, 120)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        when (ev?.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isUserTouching = true
                isAutoScrollEnabled = false
                scrollIdleHandler.removeCallbacks(scrollIdleRunnable)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isUserTouching = false
                scrollIdleHandler.postDelayed(scrollIdleRunnable, 120)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        return false
    }

    private fun isAtBottom(): Boolean {
        val maxScrollY = (horizontalScrollView.height - height).coerceAtLeast(0)
        if (maxScrollY == 0) return true
        return (maxScrollY - scrollY) <= 80
    }

    private fun scrollToBottom() {
        val maxScrollY = (horizontalScrollView.height - height).coerceAtLeast(0)
        scrollTo(scrollX, maxScrollY)
    }

    fun updateLogs(fullText: String, textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }

        if (lastLoadedText != fullText) {
            lastLoadedText = fullText
            val savedScrollY = scrollY

            isUpdatingText = true
            textView.setText(fullText, TextView.BufferType.NORMAL)

            post {
                val maxScrollY = (horizontalScrollView.height - height).coerceAtLeast(0)
                
                if (isAutoScrollEnabled && !isUserTouching && !isScrolling) {
                    scrollTo(scrollX, maxScrollY)
                } else {
                    scrollTo(scrollX, savedScrollY.coerceAtMost(maxScrollY))
                }
                isUpdatingText = false
            }
        }
    }

    fun setLogTextColor(textColor: Int) {
        if (textView.currentTextColor != textColor) {
            textView.setTextColor(textColor)
        }
    }

    fun releaseMemory() {
        scrollIdleHandler.removeCallbacks(scrollIdleRunnable)
        lastLoadedText = null
        textView.text = ""
        isAutoScrollEnabled = true
        isUserTouching = false
        isScrolling = false
        isUpdatingText = false
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

    LaunchedEffect(uiUpdateVersionFlow, containerView, lifecycleOwner) {
        val view = containerView ?: return@LaunchedEffect

        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            uiUpdateVersionFlow
                .sample(100.milliseconds)
                .collect {
                    val totalCount = getLogCount()

                    val fullText = withContext(Dispatchers.Default) {
                        if (totalCount == 0) return@withContext ""
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
