package com.adb.kitty.compose

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import androidx.annotation.Keep
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("L")
    }
    override fun onCreate() {
        NativeLibs.nativeVerify(applicationContext)
        super.onCreate()
        
    }
}
