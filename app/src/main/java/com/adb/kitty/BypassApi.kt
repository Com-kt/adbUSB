package com.adb.kitty

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R

import android.app.Application
import android.content.Context
import android.content.ComponentCallbacks2
import org.lsposed.hiddenapibypass.HiddenApiBypass
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.Keep
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }

    override fun onCreate() {
        super.onCreate()
        NativeLibs.initNativeEngine(8 * 1024 * 1024)
    }
    
    private val _trimMemoryEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    val trimMemoryEvents: Flow<Int> = _trimMemoryEvents.asSharedFlow()
    
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        _trimMemoryEvents.tryEmit(level)
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        _trimMemoryEvents.tryEmit(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
