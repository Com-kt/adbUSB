/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toPath
import java.io.File

class AdbKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "AdbKeyManager"
    }

    init {
        // 在全局或组件初始化时，一键激活并校准 KadbCert 密码学引擎
        initializeKadbCertEngine()
    }

    private fun initializeKadbCertEngine() {
        try {
            val privFile = File(context.filesDir, "adbkey")
            val okioPath = privFile.absolutePath.toPath()

            // 绑定 Okio 原子持久化仓库
            val store = OkioFilePrivateKeyStore(privateKeyPath = okioPath)

            // 定制符合 AOSP 规范的最高防线证书策略
            val policy = KadbCertPolicy().apply {
                keySizeBits = 2048
                certValidityDays = 3650 // 10年有效期
                autoHealInvalidPrivateKey = true // 激活损坏自愈
            }

            // 注入全局配置。Kadb 在无线有线建立连接时，会自动调度此处的密钥单例
            KadbCert.configure(
                store = store,
                policy = policy,
                additionalPrivateKeysPem = emptyList()
            )

            // 强制审查状态机，若密钥丢失或损坏，会自动原子化重新生成
            val snapshot = KadbCert.ensureReady()
            
            Log.i(TAG, "🎉 KadbCert 密码学引擎部署就绪！物理指纹 SHA256: ${snapshot.fingerprintSha256}")
        } catch (e: Exception) {
            Log.e(TAG, "💥 初始化物理 KadbCert 矩阵发生致命断裂", e)
        }
    }

    /**
     * 特权功能：允许用户在界面手动强制销毁并重新轮换（重置）密钥对
     */
    fun forceRotateKeys() {
        Log.w(TAG, "⚠️ 正在触发用户特权：强制重新轮换全局 ADB 密钥对...")
        val newSnapshot = KadbCert.rotate()
        Log.i(TAG, "🔄 密钥轮换成功！新设备指纹: ${newSnapshot.fingerprintSha256}")
    }
}
