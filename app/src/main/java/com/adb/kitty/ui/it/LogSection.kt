package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.content.ClipData
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

    private val clipBoundsRect = Rect()

    private val charWidth = textPaint.measureText("M")
    private var maxLineWidth = 0f

    private var selectedText = ""
    private val selectionRect = Rect()
    private var activeActionMode: ActionMode? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (lines.isEmpty()) return

            // 根据长按 Y 坐标精准计算点击的是第几行
            val targetLineIndex = (e.y / lineSpacing).toInt().coerceIn(0, lines.size - 1)
            selectedText = lines[targetLineIndex]

            // 将浮动菜单锚点精确定位在当前行的上下边界
            val lineTop = (targetLineIndex * lineSpacing).toInt()
            val lineBottom = ((targetLineIndex + 1) * lineSpacing).toInt()
            selectionRect.set(0, lineTop, width, lineBottom)

            showSystemFloatingMenu()
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            activeActionMode?.finish()
            performClick()
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setTextColor(textColor: Int) {
        if (textPaint.color != textColor) {
            textPaint.color = textColor
            invalidate()
        }
    }

    fun setLogData(newLines: List<String>, textColor: Int, maxLineLength: Int) {
        this.lines = newLines
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }

        maxLineWidth = maxLineLength * charWidth + 32f

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

        canvas.getClipBounds(clipBoundsRect)
        val startLine = (clipBoundsRect.top / lineSpacing).toInt().coerceAtLeast(0)
        val endLine = (clipBoundsRect.bottom / lineSpacing).toInt().coerceAtMost(lines.size - 1)

        val x = 16f
        var y = (startLine * lineSpacing) - fontMetrics.top + 16f

        for (i in startLine..endLine) {
            canvas.drawText(lines[i], x, y, textPaint)
            y += lineSpacing
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun showSystemFloatingMenu() {
        activeActionMode?.finish()
        activeActionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                // 仅添加标准系统 ID，不要手动 add 任何 ProcessText Intent
                menu.add(
                    Menu.NONE,
                    android.R.id.copy,
                    Menu.NONE,
                    android.R.string.copy
                ).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)

                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == android.R.id.copy) {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("terminal_log", selectedText)
                    cm.setPrimaryClip(clip)
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

    fun updateLogs(lines: List<String>, textColor: Int, maxLineLength: Int) {
        canvasView.setLogData(lines, textColor, maxLineLength)

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
        if (isDark) 0xFFEEEEEE.toInt() else 0xFF111111.toInt()
    }

    LaunchedEffect(logUpdateFlow, containerView) {
        val view = containerView ?: return@LaunchedEffect

        logUpdateFlow
            .sample(60.milliseconds)
            .collect {
                val totalCount = getLogCount()

                val (lines, maxLen) = withContext(Dispatchers.Default) {
                    var maxLineLen = 0
                    val list = List(totalCount) { i ->
                        val line = getLogLineAt(i)
                        if (line.length > maxLineLen) {
                            maxLineLen = line.length
                        }
                        line
                    }
                    Pair(list, maxLineLen)
                }

                withContext(Dispatchers.Main) {
                    view.updateLogs(lines, logTextColor, maxLen)
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
            view.canvasView.setTextColor(logTextColor)
        },
        modifier = modifier.clipToBounds()
    )
}
