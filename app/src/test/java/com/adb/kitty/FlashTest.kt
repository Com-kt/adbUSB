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
            println("Sent command: ${String(data)}")
            return data.size
        }

        override fun receive(buffer: ByteArray, timeout: Int): Int {
            // 🌟 修复关键点：使用 ?: 处理 null 的情况
            val pollResult = responses.poll() ?: return -1 
            
            // 此时 pollResult 已经被智能类型转换为非空 String 了
            val resp = pollResult.toByteArray()
            
            // 确保不会溢出 buffer
            val length = minOf(resp.size, buffer.size)
            System.arraycopy(resp, 0, buffer, 0, length)
            return length
        }
    }

    @Test
    fun testSuccessfulFlashFlow() = runTest { // 统一使用 runTest
        val mock = MockTransport()
        mock.responses.add("DATA00000004") 
        mock.responses.add("OKAY")         
        mock.responses.add("OKAY")         

        val protocol = FastbootProtocol(mock)
        val result = protocol.flashPartition("boot", byteArrayOf(0, 1, 2, 3))
        
        assertEquals("Success", result)
    }

    @Test
    fun testFlashFailure_WhenDeviceRefuses() = runTest { // 统一使用 runTest
        val mock = MockTransport()
        mock.responses.add("FAILDeviceBusy")

        val protocol = FastbootProtocol(mock)
        val result = protocol.flashPartition("boot", byteArrayOf(0, 1, 2, 3))
        
        assertEquals("Failed", result)
    }
}
