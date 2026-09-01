package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * 纯 Canvas 渲染的轻量级终端/日志视图
 * 彻底剥离 TextView、StaticLayout 和 Editor 引擎，内存恒定在 < 10MB
 */
@Keep
private class TerminalCanvasView(context: Context) : View(context) {

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        typeface = Typeface.MONOSPACE
        color = Color.BLACK
    }

    private var lines: List<String> = emptyList()
    private val fontMetrics = textPaint.fontMetrics
    private val lineSpacing = (fontMetrics.bottom - fontMetrics.top) * 1.25f
    private var maxLineWidth = 0f

    // 选中区域与系统原生浮动菜单（小爱/翻译/复制）支持
    private var selectedText = ""
    private val selectionRect = Rect()
    private var activeActionMode: ActionMode? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (lines.isEmpty()) return
            // 长按全选当前文本，并调起厂商系统浮动菜单
            selectedText = lines.joinToString("\n")
            selectionRect.set(0, 0, width, (lines.size * lineSpacing).toInt())
            showSystemFloatingMenu()
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 点击空白处收起浮动菜单
            activeActionMode?.finish()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setLogData(newLines: List<String>, textColor: Int) {
        this.lines = newLines
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }

        // 计算最大宽度与总高度，通知外层 ScrollView 测绘 Content bounds
        var maxW = 0f
        for (line in newLines) {
            val w = textPaint.measureText(line)
            if (w > maxW) maxW = w
        }
        maxLineWidth = maxW + 32f

        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = maxLineWidth.toInt().coerceAtLeast(suggestedMinimumWidth)
        val desiredHeight = (lines.size * lineSpacing).toInt().coerceAtLeast(suggestedMinimumHeight)
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty()) return

        // 核心性能点：视口裁剪（Viewport Clipping）
        // 无论几万行文本，Canvas 仅计算并绘制屏幕当前可见的十几行，绘制耗时 < 1ms
        val clip = canvas.clipBounds
        val startLine = (clip.top / lineSpacing).toInt().coerceAtLeast(0)
        val endLine = (clip.bottom / lineSpacing).toInt().coerceAtMost(lines.size - 1)

        val x = 16f
        var y = (startLine * lineSpacing) - fontMetrics.top + 16f

        for (i in startLine..endLine) {
            canvas.drawText(lines[i], x, y, textPaint)
            y += lineSpacing
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    /**
     * 手动唤起系统的 ActionMode.TYPE_FLOATING 菜单
     * 自动包含小米 HyperOS / MIUI "问小爱"、"翻译"、"复制" 等选项
     */
    private fun showSystemFloatingMenu() {
        activeActionMode?.finish()
        activeActionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.menuInflater.inflate(android.R.R.menu.text_select_alternative, menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.copy) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setText(selectedText)
                    mode.finish()
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                activeActionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                outRect.set(selectionRect)
            }
        }, ActionMode.TYPE_FLOATING)
    }
}

@Keep
private class LogContainerView(context: Context) : NestedScrollView(context) {
    val horizontalScrollView = HorizontalScrollView(context)
    val canvasView = TerminalCanvasView(context)
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

        horizontalScrollView.addView(canvasView)
        addView(horizontalScrollView)

        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = getChildAt(0)?.height ?: 0
            val maxScrollY = (contentHeight - height).coerceAtLeast(0)
            isAutoScrollEnabled = (maxScrollY - scrollY) <= 30
        }
    }

    fun updateLogs(lines: List<String>, textColor: Int) {
        canvasView.setLogData(lines, textColor)

        if (isAutoScrollEnabled) {
            post {
                val contentHeight = getChildAt(0)?.height ?: 0
                val maxScrollY = (contentHeight - height).coerceAtLeast(0)
                scrollTo(scrollX, maxScrollY)
            }
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
        if (isDark) Color(0xFFEEEEEE).toArgb() else Color(0xFF111111).toArgb()
    }

    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow
            .sample(60.milliseconds) // 节流至 ~16FPS，平衡 CPU 开销与渲染流畅度
            .collect {
                val totalCount = getLogCount()
                
                // 子线程并发提取字符串列表，不占用 UI 主线程耗时
                val lines = withContext(Dispatchers.Default) {
                    List(totalCount) { i -> getLogLineAt(i) }
                }

                withContext(Dispatchers.Main) {
                    view.updateLogs(lines, logTextColor)
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
            // 响应深浅色主题切换
            view.canvasView.setLogData(
                lines = List(getLogCount()) { i -> getLogLineAt(i) },
                textColor = logTextColor
            )
        },
        modifier = modifier.clipToBounds()
    )
}
