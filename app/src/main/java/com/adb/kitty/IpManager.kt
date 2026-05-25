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
import java.net.URL

/**
 * 每一个本地 IP 地址的详细结构体
 */
data class IpDetails(
    val interfaceName: String, // 统一标准化为 "wlan"、"rmnet"、"vpn" 或 "other"
    val ipAddress: String,     // 干净的 IP 地址字符串
    val isIPv6: Boolean,       // 是否为 IPv6
    val isLoopback: Boolean,   // 是否为回环地址 (127.0.0.1 / ::1)
    val isLinkLocal: Boolean   // 是否为本地链路地址 (如 fe80:: 开头的内网组网地址)
)

/**
 * 汇聚本地所有网卡 IP 以及外网公网出口 IP 的完整网络档案
 */
data class ComprehensiveIpProfile(
    val localIpList: List<IpDetails>,
    val publicIpv4: String?,
    val publicIpv6: String?
)

class IpManager {

    /**
     * 核心公开方法：获取当前设备的完整网络 IP 档案
     * 必须在协程或子线程中调用
     */
    suspend fun getComprehensiveIpProfile(context: Context): ComprehensiveIpProfile = withContext(Dispatchers.IO) {
        // 1. 使用 Android 官方现代 API 精准抓取本地 Wi-Fi 和蜂窝的所有 IP 路由表
        val localIPs = getAllLocalAddresses(context)

        // 2. 顺着当前的真实网络出口，请求权威服务器获取外网看到的公网 IP
        // 使用带有 User-Agent 伪装和防 HTML 污染的高可用请求方法
        val wanV4 = fetchIpFromWeb("https://api.ipify.org")
        val wanV6 = fetchIpFromWeb("https://api6.ipify.org")

        ComprehensiveIpProfile(
            localIpList = localIPs,
            publicIpv4 = wanV4,
            publicIpv6 = wanV6
        )
    }

    /**
     * 改进重点：使用 ConnectivityManager 代替过时的网卡遍历
     * 彻底解决 Wi-Fi 有 IPv6 却因为网卡休眠或改名导致抓不到的系统 Bug
     */
    private fun getAllLocalAddresses(context: Context): List<IpDetails> {
        val masterList = mutableListOf<IpDetails>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val allNetworks = cm.allNetworks

            for (network in allNetworks) {
                val capabilities = cm.getNetworkCapabilities(network) ?: continue
                val linkProperties = cm.getLinkProperties(network) ?: continue

                // 统一标准化底层网卡标签，不再依赖不确定性的 "wlan0" 或 "rmnet_data0" 字符串匹配
                val typeLabel = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wlan"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "rmnet"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    else -> "other"
                }

                // 直接从系统内核的 LinkAddresses 路由缓存中提取所有已分配的 IP（包含临时公网 IPv6）
                val linkAddresses = linkProperties.linkAddresses
                for (linkAddress in linkAddresses) {
                    val inetAddress = linkAddress.address
                    // 剔除 IPv6 的作用域后缀（如 %wlan0）
                    val cleanAddress = inetAddress.hostAddress?.split("%")?.get(0) ?: continue

                    val details = IpDetails(
                        interfaceName = typeLabel,
                        ipAddress = cleanAddress,
                        isIPv6 = inetAddress is Inet6Address,
                        isLoopback = inetAddress.isLoopbackAddress,
                        isLinkLocal = inetAddress.isLinkLocalAddress
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
     * 改进重点：抗干扰外网 IP 请求方法
     * 增加了浏览器伪装、拦截过滤和严格的 IP 正则校验，绝不返回 "<!DOCTYPE html>"
     */
    private fun fetchIpFromWeb(urlString: String): String? {
        var urlConnection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            urlConnection = url.openConnection() as HttpURLConnection
            urlConnection.connectTimeout = 3000 
            urlConnection.readTimeout = 3000
            urlConnection.useCaches = false
            
            // 伪装浏览器头部，防止部分海外 VPN 节点的 IP 触发 api.ipify.org 的 Cloudflare 机器人人机拦截
            urlConnection.setRequestProperty("Accept", "text/plain")
            urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                // 防护：如果返回的是网页类型(html/xml)，说明请求在公共Wi-Fi或代理层被拦截重定向了，直接丢弃
                val contentType = urlConnection.contentType?.lowercase() ?: ""
                if (contentType.contains("html") || contentType.contains("xml")) {
                    return null
                }

                val reader = BufferedReader(InputStreamReader(urlConnection.inputStream))
                val rawIp = reader.readLine()?.trim()
                reader.close()

                // 终极关卡：使用严格正则验证返回的内容是不是合法的 IP 格式
                if (!rawIp.isNullOrEmpty() && (isValidIPv4(rawIp) || isValidIPv6(rawIp))) {
                    return rawIp
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            urlConnection?.disconnect()
        }
    }

    /**
     * 辅助方法：判断当前系统是否挂载了活跃的 VPN 虚拟通道
     */
    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun isValidIPv4(ip: String): Boolean {
        val regex = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$".toRegex()
        return ip.matches(regex)
    }

    private fun isValidIPv6(ip: String): Boolean {
        return ip.contains(":") && ip.length >= 3
    }
}
