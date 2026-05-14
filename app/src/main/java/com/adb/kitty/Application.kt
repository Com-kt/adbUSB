/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build

class Application : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // Android P (9.0, API 28) +++
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addRuntimeReflectionExemptions("")
        }
    }
    override fun onCreate() {
        super.onCreate()
        
    }
}
