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
import android.os.Build
import android.text.Layout
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
private class LogContainerView(context: Context) : ScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val textView = TextView(context)
    
    private var currentScope: CoroutineScope? = null
    private var currentGetLogLineAt: ((Int) -> String)? = null
    private var currentColors: NativeLogColors? = null

    private var totalAvailableLogs = 0
    private var windowStartIdx = 0
    private var windowEndIdx = 0
    private var isRendering = false

    private val maxWindowSize = 15000       
    private val scrollBufferTrigger = 600   
    private val pageChunkSize = 2500        

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true 
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        horizontalScrollView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
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

            setLayerType(View.LAYER_TYPE_SOFTWARE, null) 
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            includeFontPadding = false
        }

        horizontalScrollView.addView(textView)
        addView(horizontalScrollView)
    }

    fun updateLogs(
        scope: CoroutineScope,
        totalCount: Int,
        getLogLineAt: (Int) -> String,
        colors: NativeLogColors
    ) {
        this.currentScope = scope
        this.currentGetLogLineAt = getLogLineAt
        this.currentColors = colors
        this.totalAvailableLogs = totalCount
        
        if (totalCount == 0 || totalCount < windowEndIdx) {
            windowStartIdx = 0
            windowEndIdx = 0
            textView.text = ""
            scrollTo(0, 0)
            return
        }

        // 🌟 FIX BLANK BUG 1: If TextView isn't laid out yet, defer initialization using a view post loop
        if (!textView.isLaidOut) {
            windowStartIdx = (totalCount - maxWindowSize).coerceAtLeast(0)
            windowEndIdx = totalCount
            
            // Render directly on the UI thread for the very first frame to establish structural bounds safely
            renderFirstFrameDirectly(getLogLineAt, colors)
            return
        }

        val viewScrollBoundsHeight = height
        val currentMaxScrollY = (textView.height - viewScrollBoundsHeight).coerceAtLeast(0)
        val isUserAtBottom = scrollY >= currentMaxScrollY - 600 || scrollY == 0

        if (windowStartIdx == 0 && windowEndIdx == 0 && totalCount > 0) {
            windowStartIdx = (totalCount - maxWindowSize).coerceAtLeast(0)
            windowEndIdx = totalCount
            renderActiveWindow(isScrollUpAction = false)
            return
        }

        if (isUserAtBottom) {
            if (totalCount - windowStartIdx > maxWindowSize) {
                windowStartIdx = totalCount - maxWindowSize
            }
            windowEndIdx = totalCount
            renderActiveWindow(isScrollUpAction = false)
        } else {
            windowEndIdx = totalCount
        }
    }

    // 🌟 FIX BLANK BUG 2: Safe synchronous builder fallback exclusively for the initial logcat burst frame
    private fun renderFirstFrameDirectly(getLogLineAt: (Int) -> String, colors: NativeLogColors) {
        val firstFrameBuilder = SpannableStringBuilder()
        val start = windowStartIdx
        val end = windowEndIdx

        for (i in start until end) {
            if (firstFrameBuilder.isNotEmpty()) {
                firstFrameBuilder.append("\n")
            }
            val line = getLogLineAt(i)
            val lineStart = firstFrameBuilder.length
            firstFrameBuilder.append(line)
            val lineEnd = firstFrameBuilder.length

            val color = determineNativeLogColor(line, colors)
            firstFrameBuilder.setSpan(
                ForegroundColorSpan(color),
                lineStart,
                lineEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        
        // Apply directly without PrecomputedText to give the layout system its initial dimensions
        textView.text = firstFrameBuilder
        
        post {
            val maxScrollY = (textView.height - height).coerceAtLeast(0)
            scrollTo(scrollX, maxScrollY)
        }
    }

    private fun renderActiveWindow(isScrollUpAction: Boolean) {
        val scope = currentScope ?: return
        val getLogLineAt = currentGetLogLineAt ?: return
        val colors = currentColors ?: return
        if (isRendering) return

        // Extra layer of protection: do not allow background measuring if view layout is corrupted
        if (!textView.isLaidOut) return

        isRendering = true
        val metricsParams = TextViewCompat.getTextMetricsParams(textView)
        val start = windowStartIdx
        val end = windowEndIdx
        
        val oldTextViewHeight = textView.height

        scope.launch(Dispatchers.Default) {
            val windowBuilder = SpannableStringBuilder()

            for (i in start until end) {
                if (windowBuilder.isNotEmpty()) {
                    windowBuilder.append("\n")
                }
                val line = getLogLineAt(i)
                val lineStart = windowBuilder.length
                windowBuilder.append(line)
                val lineEnd = windowBuilder.length

                val color = determineNativeLogColor(line, colors)
                windowBuilder.setSpan(
                    ForegroundColorSpan(color),
                    lineStart,
                    lineEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            val precomputedPayload = PrecomputedTextCompat.create(windowBuilder, metricsParams)

            withContext(Dispatchers.Main) {
                textView.setText(precomputedPayload)
                
                post {
                    val newTextViewHeight = textView.height
                    
                    if (isScrollUpAction) {
                        val heightDelta = newTextViewHeight - oldTextViewHeight
                        if (heightDelta > 0) {
                            scrollBy(0, heightDelta)
                        }
                    } else {
                        val viewScrollBoundsHeight = height
                        val maxScrollY = (newTextViewHeight - viewScrollBoundsHeight).coerceAtLeast(0)
                        scrollTo(scrollX, maxScrollY)
                    }
                    isRendering = false
                }
            }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        
        if (t < scrollBufferTrigger && windowStartIdx > 0 && !isRendering) {
            val nextTargetStart = (windowStartIdx - pageChunkSize).coerceAtLeast(0)
            if (nextTargetStart != windowStartIdx) {
                windowStartIdx = nextTargetStart
                renderActiveWindow(isScrollUpAction = true)
            }
        }
    }

    private fun determineNativeLogColor(line: String, colors: NativeLogColors): Int {
        return when {
            line.contains("E/") || line.contains("F/") || line.contains(" E ") || line.contains(" F ") ||
            line.contains("[错误]") || line.contains("[FAIL]") -> colors.error

            line.contains("W/") || line.contains(" W ") ||
            line.contains("[警告]") || line.contains("[WARN]") -> colors.warn

            line.contains("[成功]") || line.contains("[OKAY]") || line.contains("[OK]") -> colors.success

            line.contains("I/") || line.contains(" I ") ||
            line.contains("[提示]") || line.contains("[INFO]") -> colors.info

            line.contains("D/") || line.contains("V/") || line.contains(" D ") || line.contains(" V ") ||
            line.contains("[调试]") || line.contains("[DEBUG]") -> colors.debug

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
    val composeScope = rememberCoroutineScope()

    // 根据系统的主题模式（深色/浅色）动态解析日志的十六进制 ARGB 颜色
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
            
            containerView.updateLogs(
                scope = composeScope,
                totalCount = getLogCount(),
                getLogLineAt = getLogLineAt,
                colors = nativeColors
            )
        },
        modifier = modifier.clipToBounds()
    )
}
