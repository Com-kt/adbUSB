/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

// 重新定义单次通道的测试战果
data class ChannelResult(
    val isSuccess: Boolean,
    val ip: String?,
    val costTime: Long,
    val errorMessage: String?
)

// 每一个网址的完整诊断报告（包含v4和v6两个维度的尝试）
data class UrlDiagnosticReport(
    val url: String,
    val ipv4Result: ChannelResult,
    val ipv6Result: ChannelResult
)

class UniversalIpTester {

    /**
     * 对任意给定的网址，强行拆分并分别测试其 IPv4 和 IPv6 的连通与出口情况
     */
    suspend fun diagnoseUrl(urlString: String): UrlDiagnosticReport = withContext(Dispatchers.IO) {
        val urlObj = URL(urlString)
        val host = urlObj.host

        var ipv4Channel = ChannelResult(false, null, 0, "未检测到IPv4解析")
        var ipv6Channel = ChannelResult(false, null, 0, "未检测到IPv6解析")

        try {
            // 1. 核心解析：获取该域名绑定的所有底层 IP
            val allAddresses = InetAddress.getAllByName(host)
            val v4Address = allAddresses.firstOrNull { it is Inet4Address }
            val v6Address = allAddresses.firstOrNull { it is Inet6Address }

            // 2. 强行走 IPv4 通道测试
            ipv4Channel = if (v4Address != null) {
                executeRequest(urlObj, v4Address, "IPv4")
            } else {
                ChannelResult(false, null, 0, "该网址的DNS没有配置IPv4记录")
            }

            // 3. 强行走 IPv6 通道测试
            ipv6Channel = if (v6Address != null) {
                executeRequest(urlObj, v6Address, "IPv6")
            } else {
                ChannelResult(false, null, 0, "该网址的DNS没有配置IPv6记录")
            }

        } catch (e: Exception) {
            val errMsg = e.message ?: "DNS 解析失败 (可能设备彻底断网)"
            ipv4Channel = ChannelResult(false, null, 0, errMsg)
            ipv6Channel = ChannelResult(false, null, 0, errMsg)
        }

        UrlDiagnosticReport(urlString, ipv4Channel, ipv6Channel)
    }

    /**
     * 底层物理隔离请求：将请求直接打在特定 IP 实体上，并手动补回 Host 头
     */
    private fun executeRequest(originalUrl: URL, targetAddress: InetAddress, label: String): ChannelResult {
        var ip: String? = null
        var isSuccess = false
        var errorMsg: String? = null
        val startTime = System.currentTimeMillis()

        try {
            // 根据 v4 或 v6 拼接物理物理连接字符串
            val spec = if (targetAddress is Inet6Address) {
                "${originalUrl.protocol}://[${targetAddress.hostAddress}]${originalUrl.file}"
            } else {
                "${originalUrl.protocol}://${targetAddress.hostAddress}${originalUrl.file}"
            }

            val ipUrl = URL(spec)
            val conn = ipUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000 // 3秒超时
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            
            // 关键：必须要让服务器知道原本的域名，否则会被当作非法请求拦截
            conn.setRequestProperty("Host", originalUrl.host)

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                ip = reader.readLine()?.trim()
                reader.close()
                isSuccess = !ip.isNullOrEmpty()
            } else {
                errorMsg = "HTTP 状态码: ${conn.responseCode}"
            }
        } catch (e: java.net.SocketTimeoutException) {
            errorMsg = "连接超时 (3000ms)"
        } catch (e: java.net.ConnectException) {
            errorMsg = "网络不可达 (证明手机无此通道或被VPN掐断)"
        } catch (e: Exception) {
            errorMsg = e.message ?: "网络未知错误"
        }

        val costTime = System.currentTimeMillis() - startTime
        return ChannelResult(isSuccess, ip, costTime, errorMsg)
    }
}
