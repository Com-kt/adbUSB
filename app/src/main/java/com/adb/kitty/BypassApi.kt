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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.ui.it.*
import com.adb.kitty.data.*
import com.adb.kitty.R

@Keep
class BypassApi : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }

    private val _appBackgroundEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val appBackgroundEvents: Flow<Unit> = _appBackgroundEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            /**
             * 当整个应用移动到后台（用户按下 Home 键、切换应用、切到后台等）时触发
             */
            override fun onStop(owner: LifecycleOwner) {
                _appBackgroundEvents.tryEmit(Unit)
            }
        })
    }
}
