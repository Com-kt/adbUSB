/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections

data class MultiNetworkIp(
    val wifiIpv4: String? = null,
    val wifiIpv6: String? = null,
    val mobileIpv4: String? = null,
    val mobileIpv6: String? = null,
    val vpnIpv4: String? = null,
    val vpnIpv6: String? = null
)

class IpManager {

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
                
                if (!netInterface.isUp || netInterface.isLoopback) continue

                val addresses = Collections.list(netInterface.inetAddresses)
                for (address in addresses) {
                    if (address.isLoopbackAddress) continue

                    val hostAddress = address.hostAddress?.split("%")?.get(0) ?: continue

                    if (name.contains("wlan")) {
                        when (address) {
                            is Inet4Address -> wifiIpv4 = hostAddress
                            is Inet6Address -> wifiIpv6 = hostAddress
                        }
                    }
                    else if (name.contains("rmnet") || name.contains("pdp") || 
                             name.contains("ccmni") || name.contains("vzw")) {
                        when (address) {
                            is Inet4Address -> mobileIpv4 = hostAddress
                            is Inet6Address -> mobileIpv6 = hostAddress
                        }
                    }
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
