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
import com.flyfishxu.kadb.channel.AdbChannel
import okio.Buffer
import okio.Source
import okio.Sink
import okio.Timeout
import java.io.IOException

/**
 * 铁血物理桥接：无缝承接你原本的 UsbDeviceConnection、epIn、epOut，
 * 将标准的 Android 用户态 bulkTransfer 泵转换成 Kadb 内部极速处理的 Okio 流。
 */
class KadbUsbBridgeChannel(
    private val conn: UsbDeviceConnection,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) : AdbChannel {

    override val source: Source = object : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            // AOSP 规范单包最大缓冲区上限限制为 64KB
            val buffer = ByteArray(byteCount.toInt().coerceAtMost(64 * 1024))
            val readBytes = conn.bulkTransfer(epIn, buffer, buffer.size, 2000)
            if (readBytes <= 0) return -1
            sink.write(buffer, 0, readBytes)
            return readBytes.toLong()
        }
        override fun close() {}
        override fun timeout(): Timeout = Timeout.NONE
    }

    override val sink: Sink = object : Sink {
        override fun write(source: Buffer, byteCount: Long) {
            val bytes = source.readByteArray(byteCount)
            var offset = 0
            while (offset < bytes.size) {
                val chunkSize = (bytes.size - offset).coerceAtMost(64 * 1024)
                val sent = conn.bulkTransfer(epOut, bytes, offset, chunkSize, 2000)
                if (sent <= 0) throw IOException("USB 物理有线通道写入数据发生物理断裂")
                offset += sent
            }
        }
        override fun flush() {}
        override fun close() {}
        override fun timeout(): Timeout = Timeout.NONE
    }

    override fun close() {
        // 连接关闭时的自定义硬件收尾留空，由外部持有者自主释放 conn
    }
}
