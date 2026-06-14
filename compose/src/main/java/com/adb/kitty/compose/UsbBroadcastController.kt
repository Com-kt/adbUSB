package com.adb.kitty.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

object UsbBroadcastController {

    // --- 回调接口 ---
    var onUsbPermissionGranted: ((UsbDevice?) -> Unit)? = null
    var onUsbPermissionDenied: (() -> Unit)? = null
    var onUsbAttached: ((UsbDevice) -> Unit)? = null
    var onUsbDetached: (() -> Unit)? = null

    const val ACTION_USB_PERMISSION = "com.adb.kitty.compose.USB_PERMISSION"

    // --- 1. USB 权限接收器 ---
    val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                if (granted) {
                    onUsbPermissionGranted?.invoke(device)
                } else {
                    onUsbPermissionDenied?.invoke()
                }
            }
        }
    }

    // --- 2. USB 状态接收器 (插拔) ---
    val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbDevice::class.java.name)
                    }
                    device?.let { onUsbAttached?.invoke(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    onUsbDetached?.invoke()
                }
            }
        }
    }

    // --- 工具方法：获取 IntentFilter ---
    fun getPermissionFilter() = IntentFilter(ACTION_USB_PERMISSION)

    fun getStateFilter() = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
        addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
    }

    // --- Android 13+ 注册标志位辅助 ---
    fun getExportFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
    }
}
