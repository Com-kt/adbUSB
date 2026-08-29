package com.adb.kitty.compose.ui.it

import android.*
import android.util.*
import android.content.pm.*
import android.animation.*
import android.provider.*
import android.app.PendingIntent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.speech.tts.TextToSpeech

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.graphics.createBitmap
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.system.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import okio.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.*
import org.json.*

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

@Keep
@Composable
fun LogSection(
    logCount: Int,
    getLogLineAt: (index: Int) -> String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val globalHorizontalScrollState = rememberScrollState()
    
    val customTextSelectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    )
    
    val isDark = isSystemInDarkTheme()
    val errorColor = remember(isDark) { if (isDark) Color(0xFFFF8A80) else Color(0xFFC62828) }   // 柔粉红 / 深绛红
    val warnColor = remember(isDark) { if (isDark) Color(0xFFFFCC80) else Color(0xFFE65100) }    // 浅麦黄 / 暗深橙
    val successColor = remember(isDark) { if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32) } // 薄荷绿 / 森林绿
    val infoColor = remember(isDark) { if (isDark) Color(0xFF90CAF9) else Color(0xFF1565C0) }    // 淡空蓝 / 深宝蓝
    val debugColor = remember(isDark) { if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2) }   // 薰衣紫 / 暗茄紫
    val traceColor = remember(isDark) { if (isDark) Color(0xFFB0BEC5) else Color(0xFF546E7A) }   // 灰蓝石 / 黛青石

    // React to the total line count size changes
    LaunchedEffect(logCount) {
        if (logCount > 0) {
            val lastIndex = logCount - 1
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastIndex - lastVisibleIndex < 5) {
                listState.scrollToItem(lastIndex) 
            } else if (lastIndex - lastVisibleIndex < 20) {
                listState.animateScrollToItem(lastIndex)
            } else {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .horizontalScroll(globalHorizontalScrollState)
            .padding(8.dp)
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth() 
                ) {
                    items(
                        count = logCount,
                        key = { index -> 
                            // Generates a lightweight structural structural item hash signature 
                            // derived from the line mapping to ensure smooth list state retention
                            index
                        }
                    ) { index ->
                        val logLineStr = getLogLineAt(index)
                        val rowTextColor = remember(logLineStr, debugColor, infoColor, warnColor, errorColor, traceColor, successColor) {
                            determineLogColor(logLineStr, debugColor, infoColor, warnColor, errorColor, traceColor, successColor)
                        }
                        Text(
                            text = logLineStr,
                            color = rowTextColor,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Keep
private fun determineLogColor(
    line: String,
    debugColor: Color,
    infoColor: Color,
    warnColor: Color,
    errorColor: Color,
    traceColor: Color,
    successColor: Color
): Color {
    if (line.isEmpty()) return Color.Unspecified

    // 优先级最高：最高危的【错误 / 异常 / Fatal】
    // 无论是标准 Logcat 头部标记，还是文本中包含“错误”“异常”，整行直接爆红
    val maxScanLen = minOf(line.length, 80)
    if (line.indexOf(" E/", 0) in 0 until maxScanLen || 
        line.indexOf(" F/", 0) in 0 until maxScanLen || 
        line.contains("错误") || 
        line.contains("异常") || 
        line.contains("失败") || 
        line.contains("🔴") || 
        line.contains("FAIL") || 
        line.contains("Exception") || 
        line.contains("exception") || 
        line.contains("Error") || 
        line.contains("error") || 
        line.contains("fatal") || 
        line.contains("Fatal")
    ) {
        return errorColor
    }

    //. 优先级第二：【警告 / 超时】
    if (line.indexOf(" W/", 0) in 0 until maxScanLen || 
        line.contains("警告") || 
        line.contains("超时") || 
        line.contains("未知") || 
        line.contains("TIMEOUT") || 
        line.contains("unknown") || 
        line.contains("Warn") || 
        line.contains("warn") || 
        line.contains("Warning") || 
        line.contains("Timeout")
    ) {
        return warnColor
    }

    // 优先级第三：【成功】
    // 绿色在日志中极其显眼，通常用于核心流程跑通的打点
    if (line.contains("成功") || 
        line.contains("🟢") || 
        line.contains("Success") || 
        line.contains("success") || 
        line.contains("OKAY") || 
        line.contains("OK")
    ) {
        return successColor
    }

    // 优先级第四：【提示 / 信息 / Info】
    if (line.indexOf(" I/", 0) in 0 until maxScanLen || 
        line.contains("提示") || 
        line.contains("信息") || 
        line.contains("INFO") || 
        line.contains("Info") || 
        line.contains("info") || 
        line.contains("Hint")
    ) {
        return infoColor
    }

    // 优先级第五：【调试 / Debug】
    if (line.indexOf(" D/", 0) in 0 until maxScanLen || 
        line.contains("debug") || 
        line.contains("调试") || 
        line.contains("Debug")
    ) {
        return debugColor
    }

    // 优先级第六：【追踪 / Verbose / Trace】
    if (line.indexOf(" V/", 0) in 0 until maxScanLen || 
        line.contains("Trace") || 
        line.contains("Verbose")
    ) {
        return traceColor
    }

    // 兜底兼容：针对新版 AS 格式的孤立字母（如 "  E  "）进行前缀快速扫描
    for (i in 0 until maxScanLen - 2) {
        if (line[i] == ' ' && line[i + 2] == ' ') {
            when (line[i + 1]) {
                'E', 'F' -> return errorColor
                'W' -> return warnColor
                'I' -> return infoColor
                'D' -> return debugColor
                'V' -> return traceColor
            }
        }
    }

    return Color.Unspecified
}