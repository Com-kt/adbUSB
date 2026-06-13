/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.*
import android.os.*
import android.util.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*

import javax.crypto.*
import javax.net.ssl.*

import okio.*
import org.json.*

data class AdbCommand(val description: String, val command: String)

data class FbCommand(val description: String, val command: String)

data class FastbootResponse(val status: String, val payload: String, val allLines: List<String>)

data class AdbDevice(val ip: String, val port: Int, val wifiSsid: String, val lastConnectedTime: Long)

object NativeLibs {
    init {
        System.loadLibrary("native-lib")
    }
    external fun UserString(): String
}
