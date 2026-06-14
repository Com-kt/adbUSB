package com.adb.kitty.compose

import android.*
import android.util.*
import android.content.pm.*
import android.app.*
import android.graphics.*
import android.animation.*

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
import androidx.core.app.*
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
import org.json.*
import androidx.annotation.Keep

import androidx.compose.runtime.*

@Keep
class LogViewModel : ViewModel() {
    // 自动触发 UI 更新的日志列表
    private val _logs = mutableStateListOf<String>()
    val logs: List<String> = _logs

    // 建议项列表
    val items = listOf("Cupcake", "Donut", "Eclair", "Froyo", "Gingerbread", "Honeycomb", "Jelly Bean", "KitKat", "Lollipop", "Marshmallow", "Nougat", "Oreo", "Pie")

    // 这里是转换后的 appendLog 方法，完全纯净，不含 View 逻辑
    fun appendLog(msg: String) {
        val current = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
        val time = current.format(formatter)
        _logs.add("$time $msg")
    }
    
    // 如果需要清空日志
    fun clearLogs() {
        _logs.clear()
    }
}
