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

    @OptIn(ExperimentalUnsignedTypes::class)
    val SIGNATURE_PADDING: ByteArray = byteArrayOf(0x00.toByte(), 0x01.toByte()) + 
        ByteArray(202) { 0xff.toByte() } + 
        byteArrayOf(
            0x00.toByte(), 0x30.toByte(), 0x31.toByte(), 0x30.toByte(), 
            0x0d.toByte(), 0x06.toByte(), 0x09.toByte(), 0x60.toByte(), 
            0x86.toByte(), 0x48.toByte(), 0x01.toByte(), 0x65.toByte(), 
            0x03.toByte(), 0x04.toByte(), 0x02.toByte(), 0x01.toByte(), 
            0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x20.toByte()
    )
    
    private val _adbCommands = listOf(
        AdbCommand("查看 adbd 用户组", "id"),
        AdbCommand("查看SeLinux状态", "getenforce"),
        AdbCommand("重启", "reboot"),
        AdbCommand("重启到系统", "reboot system"),
        AdbCommand("重启到 Recovery", "reboot recovery"),
        AdbCommand("重启到 Bootloader", "reboot bootloader"),
        AdbCommand("重启到 FastbootD", "reboot fastboot"),
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
    
}
