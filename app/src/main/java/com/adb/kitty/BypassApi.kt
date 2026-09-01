package com.adb.kitty

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.Keep
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R

@Keep
class BypassApi : Application(), DefaultLifecycleObserver {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        thread {
            val apkPath = packageCodePath
            val isNativeVerified = NativeLibs.VerifyAllSignatures(apkPath)
            if (!isNativeVerified) {
                val appContext = this
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post(object : Runnable {
                    override fun run() {
                        Toast.makeText(
                            appContext,
                            "您正在使用非官方正版应用，注意代码安全",
                            Toast.LENGTH_LONG
                        ).show()
                        mainHandler.postDelayed(this, 3000)
                    }
                })
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        System.gc()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            System.gc()
        }
    }
}
