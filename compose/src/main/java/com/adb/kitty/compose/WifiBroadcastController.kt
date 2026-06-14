package com.adb.kitty.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build

object WifiBroadcastController {

    var isWifiEnabled: Boolean = false
    
    // --- 回调接口 ---
    var onWifiStateChanged: ((isEnabled: Boolean) -> Unit)? = null
    var onWifiEnabling: (() -> Unit)? = null
    var onWifiDisabling: (() -> Unit)? = null

    // --- 广播接收器 ---
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val wifiState = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE, 
                    WifiManager.WIFI_STATE_UNKNOWN
                )

                when (wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> {
                        isWifiEnabled = true
                        onWifiStateChanged?.invoke(true)
                    }
                    WifiManager.WIFI_STATE_DISABLED -> {
                        isWifiEnabled = false
                        onWifiStateChanged?.invoke(false)
                    }
                    WifiManager.WIFI_STATE_ENABLING -> {
                        onWifiEnabling?.invoke()
                    }
                    WifiManager.WIFI_STATE_DISABLING -> {
                        onWifiDisabling?.invoke()
                    }
                }
            }
        }
    }

    // --- 工具方法 ---
    fun getIntentFilter() = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)

    fun getReceiverFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_EXPORTED
        } else {
            0
        }
    }
    
    // 初始化时获取当前状态
    fun init(context: Context) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        isWifiEnabled = wifiManager.isWifiEnabled
    }
}
