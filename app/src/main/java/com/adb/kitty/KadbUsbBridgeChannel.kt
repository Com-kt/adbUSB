/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.flyfishxu.kadb.transport.TransportChannel
import okio.Buffer
import java.io.IOException

/**
 * 🎯 完美对齐 Kadb 最新架构的物理通道桥接器
 */
class KadbUsbBridgeChannel(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : TransportChannel {

    // 🌟 最新版 Kadb 统一采用 Okio 的 Buffer 进行高效无内存拷贝读写
    override fun read(sink: Buffer, byteCount: Long): Long {
        val buffer = ByteArray(byteCount.toInt().coerceAtMost(64 * 1024))
        val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 2000)
        if (readBytes <= 0) return -1
        sink.write(buffer, 0, readBytes)
        return readBytes.toLong()
    }

    override fun write(source: Buffer, byteCount: Long) {
        val bytes = source.readByteArray(byteCount)
        var offset = 0
        while (offset < bytes.size) {
            val chunkSize = (bytes.size - offset).coerceAtMost(64 * 1024)
            val sent = conn.bulkTransfer(epOut, bytes, offset, chunkSize, 2000)
            if (sent <= 0) throw IOException("USB 物理有线通道数据写入断裂")
            offset += sent
        }
    }

    // 对应新版接口可能要求的收尾方法
    override fun flush() {}
    override fun close() {}
}
