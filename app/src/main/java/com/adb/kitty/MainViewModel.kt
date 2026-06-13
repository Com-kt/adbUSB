/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

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

class MainViewModel : ViewModel() {
    
    private val _adbCommands = listOf(
        AdbCommand("查看 adbd 用户组", "id"),
        AdbCommand("查看SeLinux状态", "getenforce"),
        AdbCommand("重启", "reboot"),
        AdbCommand("重启到系统", "reboot system"),
        AdbCommand("重启到 Recovery", "reboot recovery"),
        AdbCommand("重启到 Bootloader", "reboot bootloader"),
        AdbCommand("重启到 FastbootD", "reboot fastboot"),
        AdbCommand("重启到 Edl (紧急下载/9008模式)", "reboot edl"),
        AdbCommand("纯净系统无任何用户激活 Dhizuku (需要安装Dhizuku)", "dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver"),
        AdbCommand("激活 Sence (需要安装Sence)", "sh /storage/emulated/0/Android/data/com.omarea.vtools/up.sh"),
        AdbCommand("激活 AppManager (需要安装AppManager)", "sh /storage/emulated/0/Android/data/io.github.muntashirakon.AppManager/cache/run_server.sh 60001 wasp-lurk-ripen"),
        AdbCommand("查询系统信息", "uname -a"),
        AdbCommand("查看内核版本", "cat /proc/version"),
        AdbCommand("查看su版本", "su -v"),
        AdbCommand("查看基带版本", "getprop gsm.version.baseband"),
        AdbCommand("查看安卓系统版本", "getprop ro.build.version.release"),
        AdbCommand("查看设备编译版本", "getprop ro.build.display.id"),
        AdbCommand("查看安全补丁级别", "getprop ro.build.version.security_patch"),
        AdbCommand("查看主板型号", "getprop ro.product.board"),
        AdbCommand("查看屏幕分辨率 (随设置而变化)", "getprop persist.sys.miui_resolution"),
        AdbCommand("卸载系统更新", "pm uninstall --user 0 com.android.updater"),
        AdbCommand("恢复系统更新", "pm install-existing --user 0 com.android.updater"),
        AdbCommand("强行启用小米堆叠桌面 (已支持勿用)", "settings put global task_stack_view_layout_style 2 "),
        AdbCommand("强行停止小米系统桌面", "am force-stop com.miui.home")
    )
    
    val adbCommands: List<AdbCommand> get() = _adbCommands
    
    private val _fbCommands = listOf(
        FbCommand("查看当前安全补丁级别", "getvar security-patch-level"),
        FbCommand("查看当前活跃的分区槽位（a 或 b)", "getvar current-slot"),
        FbCommand("查看 Bootloader 解锁状态", "getvar unlocked"),
        FbCommand("oem info", "oem device-info"),
        FbCommand("获取设备的所有系统变量（如版本号、电池电压、Bootloader 锁状态等)", "getvar all"),
        FbCommand("退出 Fastboot 模式并正常重启手机", "reboot"),
        FbCommand("从 Fastboot 模式再次重启回 Fastboot 模式（用于重置连接状态)", "reboot bootloader"),
        FbCommand("重启到 FastbootD 模式", "reboot fastboot"),
        FbCommand("尝试重启到 EDL 模式(紧急下载/9008)", "reboot edl"),
        FbCommand("尝试进入 EDL 模式(紧急下载/9008)", "oem edl"),
        FbCommand("擦除缓存分区", "erase cache"),
        FbCommand("擦除用户数据分区（相当于恢复出厂设置)", "erase userdata"),
        FbCommand("擦除出厂重置保护(谷歌锁)", "erase frp"),
        FbCommand("尝试解锁 Bootloader", "oem unlock"),
        FbCommand("尝试解锁 Bootloader", "flashing unlock")
    )
    
    val fbCommands: List<FbCommand> get() = _fbCommands
    
    val warnMessage = """
        adbd 命令使用说明:
           •adb pair [IP:配对端口] [配对码]
           •adb connect [IP:无线调试端口]
           •adb push [本地文件名] [远端路径]
           •adb pull [远端路径] (可选本地落地名)
        fastboot 命令使用说明:
           •flash <分区> <路径>
           •boot <文件名>
           •reboot <可选参数>
           •oem <参数>
           •getvar <参数>
           •erase <分区>
           •format <分区>
           •set_active <a或b>
        1. adb使用kadb库实现，感谢github@[flyfishxu/Kadb]
        2. fastboot原生链路实现，不保证所有设备可用
        3. 我的个人项目github@[Com-kt/adbUSB]
        4. 应用自身没有签名校验机制，随时都有可能会被寡改
        5. fastboot线刷之前做好售后9008的准备，如果你拿不到9008免授权的话
        6. 免责声明：开发者没有任何义务对所有人进行服务
        7. 线刷文件夹路径：/storage/emulated/0/Android/data/com.adb.kitty/files/flash/
        8. 开发者正在计划怎么适配9008模式/紧急下载模式
    """.trimIndent()
    
    private var _fastbootManager: FastbootManager? = null
    val fastbootManager: FastbootManager? get() = _fastbootManager

    fun initFastboot(
        usbConn: UsbDeviceConnection,
        epOut: UsbEndpoint,
        epIn: UsbEndpoint,
        responseChannel: Channel<String>,
        flashFolder: File
    ) {
        _fastbootManager = FastbootManager(
            scope = viewModelScope,
            usbConn = usbConn,
            epOut = epOut,
            epIn = epIn,
            responseChannel = responseChannel,
            flashFolder = flashFolder
        )
        
        _fastbootManager?.startFastbootReader()
    }

    fun runCommand(cmd: String) {
        viewModelScope.launch {
            _fastbootManager?.executeCommandSync(cmd)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // ViewModel 销毁时，可以清理资源
    }
}
