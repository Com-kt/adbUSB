package com.adb.kitty

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.*
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * ADB 密钥管理器 - 使用 BouncyCastle 实现
 * 负责 RSA 密钥对的生成、持久化存储以及符合 ADB 协议的签名逻辑。
 */
class AdbKeyManager(private val context: Context) {
    private val privFileName = "adbkey"
    private val pubFileName = "adbkey.pub"
    private val versionFileName = "version.json"
    private val CURRENT_VERSION = 7

    companion object {
        private const val TAG = "AdbKeyManager"
        init {
            // 确保 BouncyCastle 安全提供者已注册
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * 获取密钥对：如果本地没有或版本过旧则生成，否则从文件加载
     */
    fun getKeys(): KeyPair {
        val privFile = File(context.filesDir, privFileName)
        return if (!privFile.exists() || shouldRebuild()) {
            Log.d(TAG, "Generating new keys (Reason: File missing or version mismatch)")
            generateKeys()
        } else {
            try {
                loadKeys()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load keys, falling back to generation", e)
                generateKeys()
            }
        }
    }

    private fun shouldRebuild(): Boolean {
        val verFile = File(context.filesDir, versionFileName)
        if (!verFile.exists()) return true
        return try {
            JSONObject(verFile.readText()).getInt("version") < CURRENT_VERSION
        } catch (e: Exception) { true }
    }

    /**
     * 使用 BouncyCastle 生成 2048 位 RSA 密钥对
     */
    private fun generateKeys(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val kp = kpg.generateKeyPair()

        // 1. 保存私钥为 PEM 格式 (BC 会自动根据 Key 类型选择 PKCS#1 或 PKCS#8)
        val sw = StringWriter()
        val pemWriter = JcaPEMWriter(sw)
        pemWriter.writeObject(kp.private)
        pemWriter.close()
        context.openFileOutput(privFileName, Context.MODE_PRIVATE).use {
            it.write(sw.toString().toByteArray())
        }

        // 2. 保存公钥为纯 Base64 (ADB 握手协议需要的基础格式)
        val pubBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
        context.openFileOutput(pubFileName, Context.MODE_PRIVATE).use {
            it.write(pubBase64.toByteArray())
        }

        // 3. 记录版本号
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("version", CURRENT_VERSION).toString().toByteArray())
        }

        return kp
    }

    /**
     * 从文件加载密钥，兼容 PKCS#1 (PEMKeyPair) 和 PKCS#8 (PrivateKeyInfo)
     */
    private fun loadKeys(): KeyPair {
        val privContent = File(context.filesDir, privFileName).readText()
        val pemParser = PEMParser(StringReader(privContent))
        val converter = JcaPEMKeyConverter().setProvider("BC")

        val pemObject = pemParser.readObject() ?: throw IllegalStateException("PEM file is empty")
        
        // 关键修复：根据解析出的对象类型进行安全转换
        val privateKey = when (pemObject) {
            is PrivateKeyInfo -> converter.getPrivateKey(pemObject)
            is PEMKeyPair -> converter.getKeyPair(pemObject).private
            else -> throw IllegalStateException("Unsupported private key format: ${pemObject.javaClass.simpleName}")
        }

        // 加载公钥
        val pubContent = File(context.filesDir, pubFileName).readText().trim()
        val pubBytes = Base64.decode(pubContent, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("RSA", "BC")
        val publicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))

        return KeyPair(publicKey, privateKey)
    }

    /**
     * 获取发送给手机的 AUTH 公钥数据包
     */
    fun getAdbAuthPayload(): ByteArray {
        val pubFile = File(context.filesDir, pubFileName)
        if (!pubFile.exists()) return byteArrayOf()
        
        val pubBase64 = pubFile.readText().trim()
        // 格式：Base64公钥 + 空格 + 描述符 + \0
        return "$pubBase64 adb@kitty\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * 使用私钥对 ADB Token 进行签名 (SHA1withRSA)
     */
    fun signAdbToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA", "BC")
        signer.initSign(privateKey)
        signer.update(token)
        return signer.sign()
    }
}
