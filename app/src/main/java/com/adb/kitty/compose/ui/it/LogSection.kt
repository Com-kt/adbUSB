package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow

@Keep
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    
    val errorColor = (if (isDark) Color(0xFFFF8A80) else Color(0xFFC62828)).toArgb()
    val warnColor = (if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100)).toArgb()
    val successColor = (if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)).toArgb()
    val infoColor = (if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)).toArgb()
    val debugColor = (if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)).toArgb()
    val traceColor = (if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A)).toArgb()

    val nativeColors = remember(isDark) {
        NativeLogColors(errorColor, warnColor, successColor, infoColor, debugColor, traceColor)
    }

    var currentCountState by remember { mutableIntStateOf(getLogCount()) }

    // 监听 ViewModel 管道发出的事件通知（增量刷新/清空刷新）
    LaunchedEffect(logUpdateFlow) {
        logUpdateFlow.collect {
            currentCountState = getLogCount()
        }
    }

    val adapter = remember {
        LogRecyclerViewAdapter(
            getLogLineAt = getLogLineAt,
            colors = nativeColors
        )
    }

    Box(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    this.adapter = adapter
                    setHasFixedSize(true)
                    setItemViewCacheSize(20)
                }
            },
            update = { recyclerView ->
                val oldSize = adapter.getItemCount()
                val newSize = currentCountState
                adapter.totalItemCount = newSize

                if (newSize > oldSize) {
                    // 局部增量通知，避免重布局卡顿
                    adapter.notifyItemRangeInserted(oldSize, newSize - oldSize)

                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisibleIndex = layoutManager.findLastVisibleItemPosition()
                    if (newSize - 1 - lastVisibleIndex < 15) {
                        recyclerView.post {
                            recyclerView.scrollToPosition(newSize - 1)
                        }
                    }
                } else {
                    // 当清空日志 (clearLogs) 或 newSize < oldSize 时全量刷新
                    adapter.notifyDataSetChanged()
                }
            }
        )
    }
}

private data class NativeLogColors(
    val error: Int, val warn: Int, val success: Int,
    val info: Int, val debug: Int, val trace: Int
)

private class LogRecyclerViewAdapter(
    private val getLogLineAt: (Int) -> String,
    val colors: NativeLogColors
) : RecyclerView.Adapter<LogRecyclerViewAdapter.LogViewHolder>() {

    var totalItemCount: Int = 0

    class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, 2, 0, 2)
            setTextIsSelectable(true) // 支持原生长按选择复制
        }
        return LogViewHolder(textView)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val line = getLogLineAt(position)
        holder.textView.text = line
        holder.textView.setTextColor(determineNativeLogColor(line, colors))
    }

    override fun getItemCount(): Int = totalItemCount

    private fun determineNativeLogColor(line: String, colors: NativeLogColors): Int {
        if (line.isEmpty()) return 0xFF888888.toInt()

        val maxScanLen = minOf(line.length, 80)
        if (line.indexOf(" E/", 0) in 0 until maxScanLen ||
            line.indexOf(" F/", 0) in 0 until maxScanLen ||
            line.contains("错误") || line.contains("异常") ||
            line.contains("失败") || line.contains("🔴") ||
            line.contains("FAIL") || line.contains("Exception") ||
            line.contains("Error") || line.contains("Fatal")
        ) {
            return colors.error
        }

        if (line.indexOf(" W/", 0) in 0 until maxScanLen ||
            line.contains("警告") || line.contains("超时") ||
            line.contains("TIMEOUT") || line.contains("Warn")
        ) {
            return colors.warn
        }

        if (line.contains("成功") || line.contains("🟢") ||
            line.contains("Success") || line.contains("OKAY")
        ) {
            return colors.success
        }

        if (line.indexOf(" I/", 0) in 0 until maxScanLen ||
            line.contains("提示") || line.contains("信息") ||
            line.contains("INFO")
        ) {
            return colors.info
        }

        if (line.indexOf(" D/", 0) in 0 until maxScanLen ||
            line.contains("debug") || line.contains("Debug")
        ) {
            return colors.debug
        }

        if (line.indexOf(" V/", 0) in 0 until maxScanLen ||
            line.contains("Trace") || line.contains("Verbose")
        ) {
            return colors.trace
        }

        return colors.info
    }
}
