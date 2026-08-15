package com.adb.kitty.compose.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.Keep

@Keep
data class AppInfo(
    val appName: String,
    val packageName: String,
    val apkPath: String,
    val versionName: String,
    val isSystemApp: Boolean
)

@Keep
fun getInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val flags = PackageManager.GET_META_DATA
    val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(flags)
    }

    return packages.mapNotNull { pkg ->
        val appInfo = pkg.applicationInfo ?: return@mapNotNull null
        val apkPath = appInfo.publicSourceDir ?: return@mapNotNull null
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val versionName = pkg.versionName ?: "1.0.0"
        val appName = appInfo.loadLabel(pm).toString()

        AppInfo(
            appName = appName,
            packageName = pkg.packageName,
            apkPath = apkPath,
            versionName = versionName,
            isSystemApp = isSystem
        )
    }
}
