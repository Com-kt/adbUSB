package com.adb.kitty

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AdbKeyManager(private val context: Context) {
    private val privFileName = "adbkey"
    private val pubFileName = "adbkey.pub"
    private val versionFileName = "version.json"
    private val CURRENT_VERSION = 5

    fun getKeys(): KeyPair {
        val privFile = File(context.filesDir, privFileName)
        if (!privFile.exists() || shouldRebuild()) {
            return generateKeys()
        }
        return loadKeys()
    }

    private fun shouldRebuild(): Boolean {
        val verFile = File(context.filesDir, versionFileName)
        if (!verFile.exists()) return true
        return try {
            JSONObject(verFile.readText()).getInt("version") < CURRENT_VERSION
        } catch (e: Exception) { true }
    }

    private fun generateKeys(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        // 1. 存储私钥：必须保存为标准的 PEM 格式，否则某些 ADB 库无法加载
        val privEncoded = Base64.encodeToString(kp.private.encoded, Base64.DEFAULT)
        val pem = "-----BEGIN PRIVATE KEY-----\n$privEncoded-----END PRIVATE KEY-----"
        context.openFileOutput(privFileName, Context.MODE_PRIVATE).use { it.write(pem.toByteArray()) }

        // 2. 存储公钥：存储为纯 Base64 (X.509 DER)
        val pubBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        context.openFileOutput(pubFileName, Context.MODE_PRIVATE).use { it.write(pubBase64.toByteArray()) }

        // 3. 保存版本
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("version", CURRENT_VERSION).toString().toByteArray())
        }
        return kp
    }

    private fun loadKeys(): KeyPair {
        val kf = KeyFactory.getInstance("RSA")
        
        // 读取私钥时需要剔除 PEM 的头尾标签
        val privContent = File(context.filesDir, privFileName).readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "") // 移除所有空白符
        
        val privBytes = Base64.decode(privContent, Base64.DEFAULT)
        val pubBytes = Base64.decode(File(context.filesDir, pubFileName).readText(), Base64.NO_WRAP)
        
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(pubBytes)),
            kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        )
    }

    /**
     * 生成发送给手机的 AUTH 公钥包
     */
    fun getAdbAuthPayload(): ByteArray {
        val pubBase64 = File(context.filesDir, pubFileName).readText().trim()
        // 关键：Base64 + 空格 + 标识符 + \0 结束符
        return "$pubBase64 adb@kitty\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * 对手机发来的 Token 进行 RSA 签名
     * @param token 手机通过 AUTH 消息发来的随机字节（通常是 20 字节）
     */
    fun signAdbToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        // ADB 协议标准的签名算法是 SHA1withRSA
        // 注意：Android 的 Signature 会自动处理填充 (PKCS#1 v1.5)
        val signer = Signature.getInstance("SHA1withRSA")
        signer.initSign(privateKey)
        signer.update(token)
        return signer.sign()
    }
}