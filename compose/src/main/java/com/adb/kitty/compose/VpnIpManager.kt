/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty.compose

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet6Address

class VpnIpManager {

    fun getLocalVpnIpv6(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val activeNetwork = cm.activeNetwork ?: return null
            
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                
                val linkProperties = cm.getLinkProperties(activeNetwork)
                
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val address = linkAddress.address
                    
                    if (address is Inet6Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        return address.hostAddress?.split("%")?.get(0)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null 
    }
}
