package com.adb.kitty.compose

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import androidx.annotation.Keep

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("L")
    }
    override fun onCreate() {
        super.onCreate()
        
    }
}
