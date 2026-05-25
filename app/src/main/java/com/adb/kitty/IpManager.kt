/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

// 定义一个大结构体，装下所有 4 个 IP
data class AllIpInfo(
    val physicalIpv4: String?,
    val physicalIpv6: String?,
    val vpnProxyIpv4: String?,
    val vpnProxyIpv6: String?
)

class IpManager {

    /**
     * 核心方法：同时获取物理真身 IP 和 VPN 代理面具 IP
     * 因为涉及网络请求，必须在协程（Coroutine）或子线程中调用
     */
    suspend fun getAllIpAddresses(context: Context): AllIpInfo = withContext(Dispatchers.IO) {
        
        // 1. 从底层物理网卡“硬挖”出物理真实 IP
        val (physV4, physV6) = getPhysicalIpFromHardware()

        // 2. 判断当前手机是否真的开启了 VPN 代理
        val isVpnActive = isVpnConnected(context)

        var proxyV4: String? = null
        var proxyV6: String? = null

        if (isVpnActive) {
            // 3. 如果开了 VPN，顺着代理网络去问互联网服务器，拿到 VPN 代理 IP
            proxyV4 = fetchIpFromWeb("https://api.ipify.org")  // 纯 IPv4 服务器
            proxyV6 = fetchIpFromWeb("https://api6.ipify.org") // 纯 IPv6 服务器
        } else {
            // 没开 VPN 的话，代理 IP 自然就是物理 IP 本身（或者直接为 null）
            proxyV4 = physV4
            proxyV6 = physV6
        }

        AllIpInfo(physV4, physV6, proxyV4, proxyV6)
    }

    /**
     * 算法 A：无视 VPN 虚拟网卡，只扒物理硬件网卡 (wlan / rmnet)
     */
    private fun getPhysicalIpFromHardware(): Pair<String?, String?> {
        var v4: String? = null
        var v6: String? = null
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (netInterface in interfaces) {
                val name = netInterface.name.lowercase()

                // 核心过滤：只看蜂窝(rmnet/pdp)和Wi-Fi(wlan)，彻底无视含有 tun/ppp 的 VPN 网卡
                val isPhysical = name.contains("rmnet") || name.contains("wlan") || 
                                 name.contains("pdp") || name.contains("ccmni")

                if (isPhysical && netInterface.isUp) {
                    val addresses = Collections.list(netInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress) {
                            val hostAddress = address.hostAddress?.split("%")?.get(0)
                            when (address) {
                                is Inet4Address -> v4 = hostAddress
                                is Inet6Address -> v6 = hostAddress
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(v4, v6)
    }

    /**
     * 辅助方法：判断系统当前是否挂着 VPN
     */
    private fun isVpnConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        // 检查当前活跃网络是否带有 TRANSPORT_VPN 标志
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ?: false
    }

    /**
     * 辅助方法：通过网络请求获取外网看我们的 IP
     */
    private fun fetchIpFromWeb(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.connectTimeout = 3000
            urlConnection.readTimeout = 3000
            if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                val ip = reader.readLine()
                reader.close()
                ip?.trim()
            } else null
        } catch (e: Exception) {
            null // 如果 VPN 不支持 IPv6，访问 api6 会超时断开，直接返回 null
        }
    }
}
