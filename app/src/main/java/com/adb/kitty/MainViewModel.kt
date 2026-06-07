/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import javax.crypto.Cipher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

class MainViewModel : ViewModel() {
    
    private val _adbCommands = listOf(
        AdbCommand("查看 adbd 用户组", "id"),
        AdbCommand("查看SeLinux状态", "getenforce"),
        AdbCommand("重启", "reboot"),
        AdbCommand("重启到系统", "reboot system"),
        AdbCommand("重启到 Recovery", "reboot recovery"),
        AdbCommand("重启到 Bootloader", "reboot bootloader"),
        AdbCommand("重启到 FastbootD", "reboot fastboot"),
        AdbCommand("重启到 Edl", "reboot edl"),
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
        FbCommand("fastboot 使用帮助", "-help"),
        FbCommand("当前连接的 fastboot 设备", "devices"),
        FbCommand("查看当前安全补丁级别", "getvar security-patch-level"),
        FbCommand("查看当前活跃的分区槽位（a 或 b)", "getvar current-slot"),
        FbCommand("查看 Bootloader 解锁状态", "getvar unlocked"),
        FbCommand("oem info", "oem device-info"),
        FbCommand("获取设备的所有系统变量（如版本号、电池电压、Bootloader 锁状态等)", "getvar all"),
        FbCommand("尝试设置 SeLinux 为宽容模式", "oem set-gpu-preemption 0 androidboot.selinux=permissive"),
        FbCommand("设置完成", "continue"),
        FbCommand("退出 Fastboot 模式并正常重启手机", "reboot"),
        FbCommand("从 Fastboot 模式再次重启回 Fastboot 模式（用于重置连接状态)", "reboot bootloader"),
        FbCommand("进入 FastbootD 模式", "reboot fastboot"),
        FbCommand("擦除缓存分区", "erase cache"),
        FbCommand("擦除用户数据分区（相当于恢复出厂设置)", "erase userdata"),
        FbCommand("擦除出厂重置保护(谷歌锁)", "erase frp"),
        FbCommand("尝试解锁 Bootloader", "oem unlock"),
        FbCommand("尝试解锁 Bootloader", "flashing unlock")
    )
    
    val fbCommands: List<FbCommand> get() = _fbCommands
    
}
