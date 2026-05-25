/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

// 终极数据类：容纳所有 3 种通道的 v4 和 v6
data class MultiNetworkIp(
    val wifiIpv4: String? = null,
    val wifiIpv6: String? = null,
    val mobileIpv4: String? = null,
    val mobileIpv6: String? = null,
    val vpnIpv4: String? = null,
    val vpnIpv6: String? = null
)

class IpManager {

    /**
     * 一网打尽：同时抓取 Wi-Fi、移动网络 以及 VPN 虚拟网卡的本地 IP 地址
     */
    fun getAllLocalIpAddresses(): MultiNetworkIp {
        var wifiIpv4: String? = null
        var wifiIpv6: String? = null
        var mobileIpv4: String? = null
        var mobileIpv6: String? = null
        var vpnIpv4: String? = null
        var vpnIpv6: String? = null

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (netInterface in interfaces) {
                val name = netInterface.name.lowercase()
                
                // 确保网卡是启动状态，且不是回环网卡
                if (!netInterface.isUp || netInterface.isLoopback) continue

                val addresses = Collections.list(netInterface.inetAddresses)
                for (address in addresses) {
                    if (address.isLoopbackAddress) continue

                    // 截退 IPv6 尾部的网卡名后缀 (例如 %tun0)
                    val hostAddress = address.hostAddress?.split("%")?.get(0) ?: continue

                    // 1. 筛选 Wi-Fi 网卡 (通常包含 wlan)
                    if (name.contains("wlan")) {
                        when (address) {
                            is Inet4Address -> wifiIpv4 = hostAddress
                            is Inet6Address -> wifiIpv6 = hostAddress
                        }
                    }
                    // 2. 筛选移动网络网卡 (包含 rmnet, pdp, ccmni, vzw 等)
                    else if (name.contains("rmnet") || name.contains("pdp") || 
                             name.contains("ccmni") || name.contains("vzw")) {
                        when (address) {
                            is Inet4Address -> mobileIpv4 = hostAddress
                            is Inet6Address -> mobileIpv6 = hostAddress
                        }
                    }
                    // 3. 筛选 VPN 虚拟网卡 (通常包含 tun, ppp, tap)
                    else if (name.contains("tun") || name.contains("ppp") || name.contains("tap")) {
                        when (address) {
                            is Inet4Address -> vpnIpv4 = hostAddress
                            is Inet6Address -> vpnIpv6 = hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return MultiNetworkIp(wifiIpv4, wifiIpv6, mobileIpv4, mobileIpv6, vpnIpv4, vpnIpv6)
    }
}
