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
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

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
        trackAppOpenInBackground()
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
    
    private fun trackAppOpenInBackground() {
        Thread {
            try {
                val sharedPref = getSharedPreferences("app_analytics_pref", MODE_PRIVATE)
                var installId = sharedPref.getString("client_id", null)
                if (installId == null) {
                    installId = UUID.randomUUID().toString()
                    sharedPref.edit().putString("client_id", installId).apply()
                }

                val baseUrl = "https://digitalplat.org"
                val encodedId = URLEncoder.encode(installId, Charsets.UTF_8.name())
                val finalUrlStr = "$baseUrl?event=app_open&version=1.0.0&platform=android&client_id=$encodedId"

                val url = URL(finalUrlStr)
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    
                    val responseCode = responseCode 
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        inputStream.close()
                    }
                    disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
