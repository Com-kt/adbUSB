/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import java.io.*
import java.nio.*

interface FastbootTransport {
    fun send(data: ByteArray): Int
    fun receive(buffer: ByteArray, timeout: Int): Int
}
