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
    
    // 异步数据源的回调缓存
    private var currentScope: CoroutineScope? = null
    private var currentGetLogLineAt: ((Int) -> String)? = null
    private var currentColors: NativeLogColors? = null

    private var totalAvailableLogs = 0
    private var windowStartIdx = 0
    private var windowEndIdx = 0
    private var isRendering = false

    // 🌟 滑动窗口核心配置（保证海量数据不崩溃、不卡顿、可无限加载）
    private val maxWindowSize = 15000       // TextView中同时容纳的最大日志行数
    private val scrollBufferTrigger = 600   // 距离顶部多少像素时触发向上无缝加载历史日志
    private val pageChunkSize = 2500        // 每次向上滚动时，往前追加的历史日志行数

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFillViewport = true
        descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

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
            setTextIsSelectable(true) // 保留核心功能：原生长按跨行自由框选复制

            // 🌟 核心优化 1：彻底打破16384px的GPU纹理限制，让海量文本使用系统内存渲染，永不崩溃
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) 
            
            // 核心优化 2：关闭高耗能的现代折行排版引擎
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
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
        
        // 重置/清空日志处理
        if (totalCount == 0 || totalCount < windowEndIdx) {
            windowStartIdx = 0
            windowEndIdx = 0
            textView.text = ""
            scrollTo(0, 0)
            return
        }

        // 判断用户当前是否停留在最底部（允许一定的误差范围）
        val viewScrollBoundsHeight = height
        val currentMaxScrollY = (textView.height - viewScrollBoundsHeight).coerceAtLeast(0)
        val isUserAtBottom = scrollY >= currentMaxScrollY - 600 || scrollY == 0

        if (isUserAtBottom) {
            // 如果用户在最底部，随着新日志流入，将滑动窗口平滑向前推进
            if (totalCount - windowStartIdx > maxWindowSize) {
                windowStartIdx = totalCount - maxWindowSize
            }
            windowEndIdx = totalCount
            renderActiveWindow(isScrollUpAction = false)
        } else {
            // 如果用户滚到上面去查看/框选日志了，只更新结束索引，不剔除头部，
            // 这样能绝对保护用户当前正在拉取的长按复制框选选区不被破坏
            windowEndIdx = totalCount
        }
    }

    private fun renderActiveWindow(isScrollUpAction: Boolean) {
        val scope = currentScope ?: return
        val getLogLineAt = currentGetLogLineAt ?: return
        val colors = currentColors ?: return
        if (isRendering) return

        isRendering = true
        val metricsParams = TextViewCompat.getTextMetricsParams(textView)
        val start = windowStartIdx
        val end = windowEndIdx
        
        // 记录更新前的 TextView 实际物理像素高度
        val oldTextViewHeight = textView.height

        // 🌟 核心优化 3：完全在 Default 线程池进行千万级文本过滤、Spannable 拼装
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

            // 🌟 核心优化 4：在后台线程预先计算断行、字形度量，将测量耗时彻底从主线程剥离
            val precomputedPayload = PrecomputedTextCompat.create(windowBuilder, metricsParams)

            withContext(Dispatchers.Main) {
                // 🌟 核心优化 5：不再调用慢速的 append，直接一帧之内扔下预计算好的完美 payload
                textView.setText(precomputedPayload)
                
                post {
                    val newTextViewHeight = textView.height
                    
                    if (isScrollUpAction) {
                        // 🌟 核心优化 6【无缝向上加载的关键】：
                        // 顶部塞入历史日志后，TextView的总高度变高了。
                        // 计算高度差值 delta，然后让 ScrollView 瞬间向下滚动相同像素。
                        // 这样用户的眼睛和视窗相对于当前看到的日志内容完全静止，没有任何视觉跳动！
                        val heightDelta = newTextViewHeight - oldTextViewHeight
                        if (heightDelta > 0) {
                            scrollBy(0, heightDelta)
                        }
                    } else {
                        // 正常最底部追加日志流时，自动跟随机箱滚动到最底部
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
        
        // 🌟 核心优化 7：监听滚动事件，当用户往上推、快要触顶时，无缝捞取 ViewModel 更早的索引数据
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

    // 1. 根据系统的主题模式（深色/浅色）动态解析日志的十六进制 ARGB 颜色
    val errorColor = (if (isDark) Color(0xFFFF8A80) else Color(0xFFC62828)).toArgb()
    val warnColor = (if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)).toArgb()
    val successColor = (if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)).toArgb()
    val infoColor = (if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)).toArgb()
    val debugColor = (if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)).toArgb()
    val traceColor = (if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A)).toArgb()

    // 2. 缓存颜色配置对象，避免 Compose 主体层在高频无意义的重组中重复实例化
    val nativeColors = remember(isDark) {
        NativeLogColors(errorColor, warnColor, successColor, infoColor, debugColor, traceColor)
    }

    var updateTrigger by remember { mutableIntStateOf(0) }

    // 3. 核心流防抖：使用 collectLatest 监听 ViewModel 的通知。
    // 如果后台日志刷新极快（比如1毫秒冲进来十几条），collectLatest 会自动掐断并抛弃掉来不及响应的旧通知，
    // 永远只对最新的最新一帧做响应，从源头上杜绝多线程排队造成的卡顿现象。
    LaunchedEffect(logUpdateFlow) {
        logUpdateFlow.collectLatest {
            updateTrigger++
        }
    }

    // 4. 互操作层桥接
    AndroidView(
        factory = { context ->
            LogContainerView(context)
        },
        update = { containerView ->
            // 绑定数据流，保证通知到达时触发 View 树内部的滑动窗口逻辑
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
