/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataChecksum: Int,
    val magic: Int
) {
    companion object {
        fun parseHeader(bytes: ByteArray): AdbMessage {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return AdbMessage(
                command = buffer.int,
                arg0 = buffer.int,
                arg1 = buffer.int,
                dataLength = buffer.int,
                dataChecksum = buffer.int,
                magic = buffer.int
            )
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(dataLength)
        buffer.putInt(dataChecksum)
        buffer.putInt(magic)
        return buffer.array()
    }
}
