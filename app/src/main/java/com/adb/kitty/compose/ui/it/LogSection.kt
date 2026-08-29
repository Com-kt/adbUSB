package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

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
@SuppressLint("NotifyDataSetChanged")
@Composable
fun LogSection(
    getLogCount: () -> Int,
    getLogLineAt: (index: Int) -> String,
    logUpdateFlow: Flow<Unit>,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val errorColor = (if (isDark) Color(0xFFFF8A80) else Color(0xFFC62828)).toArgb()
    val warnColor = (if (isDark) Color(0xFFFFFCC80) else Color(0xFFE65100)).toArgb()
    val successColor = (if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)).toArgb()
    val infoColor = (if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0)).toArgb()
    val debugColor = (if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)).toArgb()
    val traceColor = (if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A)).toArgb()

    val nativeColors = remember(isDark) {
        NativeLogColors(errorColor, warnColor, successColor, infoColor, debugColor, traceColor)
    }

    var currentCountState by remember { mutableIntStateOf(getLogCount()) }

    LaunchedEffect(logUpdateFlow) {
        logUpdateFlow.collectLatest {
            currentCountState = getLogCount()
        }
    }

    val adapter = remember(getLogLineAt, nativeColors) {
        LogRecyclerViewAdapter(getLogLineAt, nativeColors)
    }

    AndroidView(
        factory = { context ->
            HorizontalScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true

                val recyclerView = RecyclerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    layoutManager = LinearLayoutManager(context)
                    this.adapter = adapter
                    setItemViewCacheSize(20)
                }
                addView(recyclerView)
            }
        },
        update = { horizontalScrollView ->
            val recyclerView = horizontalScrollView.getChildAt(0) as RecyclerView
            val oldSize = adapter.itemCount
            val newSize = currentCountState
            adapter.totalItemCount = newSize

            if (newSize > oldSize) {
                adapter.notifyItemRangeInserted(oldSize, newSize - oldSize)

                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleIndex = layoutManager.findLastVisibleItemPosition()
                if (newSize - 1 - lastVisibleIndex < 15) {
                    recyclerView.post {
                        recyclerView.scrollToPosition(newSize - 1)
                    }
                }
            } else if (newSize < oldSize) {
                if (newSize == 0 && oldSize > 0) {
                    adapter.notifyItemRangeRemoved(0, oldSize)
                } else {
                    adapter.notifyDataSetChanged()
                }
            }
        },
        modifier = modifier
    )
}

private class LogRecyclerViewAdapter(
    private val getLogLineAt: (Int) -> String,
    val colors: NativeLogColors
) : RecyclerView.Adapter<LogRecyclerViewAdapter.LogViewHolder>() {

    var totalItemCount: Int = 0

    class LogViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val textView = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, 2, 0, 2)
            setTextIsSelectable(true)
            
            setHorizontallyScrolling(true)
            isSingleLine = true
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
        return when {
            line.contains("error") || line.contains("FAIL") || line.contains("[错误]") || line.contains("[FAIL]") -> colors.error
            line.contains("warn") || line.contains("[警告]") || line.contains("[WARN]") -> colors.warn
            line.contains("OKAY") || line.contains("[成功]") || line.contains("[OKAY]") -> colors.success
            line.contains("info") || line.contains("[提示]") || line.contains("[INFO]") -> colors.info
            line.contains("debug") || line.contains("[调试]") || line.contains("[DEBUG]") -> colors.debug
            else -> colors.trace
        }
    }
}
