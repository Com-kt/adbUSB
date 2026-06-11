/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.LinkedList

class FlashTest {

    // 模拟一个“听话”或“调皮”的设备
    class MockTransport : FastbootTransport {
        val responses = LinkedList<String>()

        override fun send(data: ByteArray): Int {
            // 这里可以打印发出的命令，用于调试
            println("Sent command: ${String(data)}")
            return data.size
        }

        override fun receive(buffer: ByteArray, timeout: Int): Int {
            if (responses.isEmpty()) return -1
            val resp = responses.poll().toByteArray()
            System.arraycopy(resp, 0, buffer, 0, resp.size)
            return resp.size
        }
    }

    @Test
    fun testSuccessfulFlashFlow() = runTest {
        val mock = MockTransport()
        // 预设协议交互顺序
        mock.responses.add("DATA00000004") // 模拟收到 Download 许可
        mock.responses.add("OKAY")         // 模拟数据流传输完毕
        mock.responses.add("OKAY")         // 模拟 Flash 完成

        val protocol = FastbootProtocol(mock)
        val result = protocol.flashPartition("boot", byteArrayOf(0, 1, 2, 3))
        
        assertEquals("Success", result)
    }

    @Test
    fun testFlashFailure_WhenDeviceRefuses() = runBlocking {
        val mock = MockTransport()
        mock.responses.add("FAILDeviceBusy") // 模拟设备拒绝

        val protocol = FastbootProtocol(mock)
        val result = protocol.flashPartition("boot", byteArrayOf(0, 1, 2, 3))
        
        assertEquals("Failed", result)
    }
}
