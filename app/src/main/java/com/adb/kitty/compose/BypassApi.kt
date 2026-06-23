package com.adb.kitty.compose

import android.app.Application
import android.content.Context
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.Keep
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
       // HiddenApiBypass.addHiddenApiExemptions("L")
        HiddenApiBypass.setHiddenApiExemptions("")
    }
    override fun onCreate() {
        super.onCreate()
        thread {
            val apkPath = packageCodePath
            val isNativeVerified = NativeLibs.V3Signature(apkPath)
            if (!isNativeVerified) {
                val appContext = this
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post(object : Runnable {
                    override fun run() {
                        Toast.makeText(
                            appContext,
                            "V3签名校验失败，V3.1签名校验失败",
                            Toast.LENGTH_LONG
                        ).show()
                        mainHandler.postDelayed(this, 3000)
                    }
                })
            }
        }
    }
}
