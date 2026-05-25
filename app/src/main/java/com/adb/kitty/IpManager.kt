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

/**
 * Data class representing a single IP address entry found on the device.
 */
data class IpDetails(
    val interfaceName: String, // e.g., "wlan0", "rmnet0", "tun0"
    val ipAddress: String,     // The actual IP string
    val isIPv6: Boolean,       // True for IPv6, False for IPv4
    val isLoopback: Boolean,   // True for 127.0.0.1 or ::1
    val isLinkLocal: Boolean   // True for scope-local addresses like fe80::
)

/**
 * Master data class containing the complete network profile of the device.
 */
data class ComprehensiveIpProfile(
    val localIpList: List<IpDetails>,
    val publicIpv4: String?,
    val publicIpv6: String?
)

class IpManager {

    /**
     * Core Method: Collects the entire list of local IP addresses from all interfaces
     * and queries external servers for the current public-facing WAN IPs.
     * Must be called from a Coroutine or background thread.
     */
    suspend fun getComprehensiveIpProfile(): ComprehensiveIpProfile = withContext(Dispatchers.IO) {
        // 1. Scraping the entire list of IPs bound to the hardware/virtual interfaces
        val localIPs = getAllLocalAddresses()

        // 2. Fetch public WAN IPs via web requests (handles VPN tunnels automatically)
        
        val wanV4 = fetchIpFromWeb("https://api.ipify.org")
        val wanV6 = fetchIpFromWeb("https://api6.ipify.org")


        ComprehensiveIpProfile(
            localIpList = localIPs,
            publicIpv4 = wanV4,
            publicIpv6 = wanV6
        )
    }

    /**
     * Iterates through every network interface on the device to build a full list of IPs.
     */
    private fun getAllLocalAddresses(): List<IpDetails> {
        val masterList = mutableListOf<IpDetails>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            for (netInterface in interfaces) {
                // Ignore down interfaces to keep the list relevant
                if (!netInterface.isUp) continue

                val addresses = Collections.list(netInterface.inetAddresses)
                for (address in addresses) {
                    // Clean up IPv6 zone indices (e.g., fe80::1%wlan0 -> fe80::1)
                    val cleanAddress = address.hostAddress?.split("%")?.get(0) ?: continue
                    
                    val details = IpDetails(
                        interfaceName = netInterface.name,
                        ipAddress = cleanAddress,
                        isIPv6 = address is Inet6Address,
                        isLoopback = address.isLoopbackAddress,
                        isLinkLocal = address.isLinkLocalAddress
                    )
                    masterList.add(details)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return masterList
    }

    /**
     * Helper Method: Network request to resolve how the internet sees this device.
     */
    private fun fetchIpFromWeb(urlString: String): String? {
        return try {
            val url = URL(urlString)
            val urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.connectTimeout = 2500 
            urlConnection.readTimeout = 2500
            urlConnection.useCaches = false
            
            if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                val ip = reader.readLine()
                reader.close()
                ip?.trim()
            } else null
        } catch (e: Exception) {
            null // Returns null if the protocol (like IPv6) is unsupported or timed out
        }
    }

    /**
     * Helper Method: Diagnostic check to see if a system-wide VPN transport layer is active.
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
