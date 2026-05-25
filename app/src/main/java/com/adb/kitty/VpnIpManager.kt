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
import java.net.Inet6Address

class VpnIpManager {

    /**
     * 精准获取本地 VPN 虚拟网卡的本地 IPv6 地址
     * 必须在主线程/协程中传入 Context 调用
     */
    fun getLocalVpnIpv6(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // 1. 获取当前系统所有激活的网络通道
            val allNetworks = cm.allNetworks
            
            for (network in allNetworks) {
                val capabilities = cm.getNetworkCapabilities(network)
                
                // 2. 核心筛选：判断这个网络通道是不是 VPN
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    
                    // 3. 获取该 VPN 通道的链路属性 (包含分配的所有本地IP)
                    val linkProperties = cm.getLinkProperties(network)
                    
                    // 4. 遍历该 VPN 通道下的所有本地 IP 地址
                    linkProperties?.linkAddresses?.forEach { linkAddress ->
                        val address = linkAddress.address
                        
                        // 5. 严格筛选：必须是 IPv6 地址，同时排除回环和本地链路地址（如 fe80:: 开头的物理本地地址）
                        if (address is Inet6Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            
                            // 截退可能自带的网卡名后缀（如 %tun0），返回纯粹的 IPv6 字符串
                            return address.hostAddress?.split("%")?.get(0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null // 未开启 VPN，或者当前 VPN 不支持/未分配本地 IPv6
    }
}
