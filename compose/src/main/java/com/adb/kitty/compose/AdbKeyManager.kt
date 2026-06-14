/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty.compose

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File
import androidx.annotation.Keep

@Keep
class AdbKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbKeyManager"
    }

    init {
        // 在 App 启动或组件初始化时，一键激活并校准全局 KadbCert 密码学引擎
        initializeKadbCertEngine()
    }

    private fun initializeKadbCertEngine() {
        try {
            // 1. 选定本地沙箱中存储 ADB 私钥的文件路径
            val privFile = File(context.filesDir, "adbkey")
            val okioPath = privFile.absolutePath.toPath()

            // 2. 绑定 Okio 原子持久化仓库（新版 kadb 规范）
            val store = OkioFilePrivateKeyStore(privateKeyPath = okioPath)

            // 3. 🪐 严格对齐最新 2.x 源码规范：
            // 直接使用默认空构造器。因为新版 KadbCertPolicy 内部已经默认配置了：
            // keySizeBits = 2048 (RSA 2048位最高防线)
            // certValidityDays = 3650 (10年超长有效期)
            // autoHealInvalidPrivateKey = true (密钥损坏自动擦除自愈)
            // 这些属性在源码中是不可变的 'val'，直接使用默认策略即是最完美的 AOSP 兼容状态。
            val policy = KadbCertPolicy()

            // 4. 将配置注入全局单例
            // 无论是通过有线 TCP 环回（UsbPortForwarder），还是走真正的局域网无线调试，
            // kadb 底层在执行底层 `AdbConnection.connect` 时，都会自动调度这里的密钥凭证。
            KadbCert.configure(
                store = store,
                policy = policy,
                additionalPrivateKeysPem = emptyList()
            )

            // 5. 强行激活并校准状态机
            // 如果本地 adbkey 文件不存在，它会在底层调用操作系统安全的密码学随机数发生器，
            // 现场为你编织并持久化生成一对全新的 AOSP 规范 RSA 密钥对。
            val snapshot = KadbCert.ensureReady()
            
            Log.i(TAG, "🎉 KadbCert 密码学引擎部署就绪！物理指纹 SHA256: ${snapshot.fingerprintSha256}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 初始化全局 KadbCert 矩阵发生致命断裂", e)
        }
    }

    /**
     * 特权轮换：允许用户在 App 界面上点击“重置密钥”时，强行销毁历史证书并重新生成。
     * 当电视端断开、拒绝接受历史私钥，或用户需要清除授权时调用此方法。
     */
    fun forceRotateKeys() {
        try {
            Log.w(TAG, "⚠️ 正在触发用户特权：强制重新轮换全局 ADB 密钥对...")
            
            // 调用 2.x 原生 rotate 接口，原子化擦除老密钥，原地诞生新身份
            val newSnapshot = KadbCert.rotate()
            
            Log.i(TAG, "🔄 密钥轮换成功！全新设备物理指纹: ${newSnapshot.fingerprintSha256}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 强制轮换密钥对失败", e)
        }
    }
}
