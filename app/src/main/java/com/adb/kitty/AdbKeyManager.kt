package com.adb.kitty

import android.content.Context
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.*
import java.security.spec.RSAKeyGenParameterSpec
import java.util.*

class AdbKeyManager(private val context: Context) {
    private val privFileName = "adbkey"
    private val pubFileName = "adbkey.pub"
    private val versionFileName = "version.json"
    private val CURRENT_VERSION = 6

    // 确保在类加载时添加 BouncyCastle 提供者
    companion object {
        init {
            Security.removeProvider("BC") // 移除系统自带的旧版 BC
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

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
        // 使用 BC 生成 2048 位 RSA 密钥对
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val kp = kpg.generateKeyPair()

        // 1. 使用 BouncyCastle 的 PEMWriter 存储私钥 (PKCS#8 格式)
        val sw = StringWriter()
        val pemWriter = JcaPEMWriter(sw)
        pemWriter.writeObject(kp.private)
        pemWriter.close()
        context.openFileOutput(privFileName, Context.MODE_PRIVATE).use { 
            it.write(sw.toString().toByteArray()) 
        }

        // 2. 存储公钥：ADB 期望的是 Base64 编码的 DER 格式，不带 PEM 头尾
        // 使用 SubjectPublicKeyInfo 获取标准编码
        val pubEncoded = android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.NO_WRAP)
        context.openFileOutput(pubFileName, Context.MODE_PRIVATE).use { 
            it.write(pubEncoded.toByteArray()) 
        }

        // 3. 保存版本
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("version", CURRENT_VERSION).toString().toByteArray())
        }
        return kp
    }

    private fun loadKeys(): KeyPair {
        // 使用 BC 的 PEMParser 加载私钥，它能自动处理 PEM 标签和换行符
        val privFile = File(context.filesDir, privFileName)
        val reader = StringReader(privFile.readText())
        val pemParser = PEMParser(reader)
        
        val privateKeyInfo = pemParser.readObject() as PrivateKeyInfo
        val privateKey = JcaPEMKeyConverter().setProvider("BC").getPrivateKey(privateKeyInfo)
        
        // 加载公钥 (从 Base64 恢复)
        val pubFile = File(context.filesDir, pubFileName)
        val pubBytes = android.util.Base64.decode(pubFile.readText(), android.util.Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("RSA", "BC")
        val publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(pubBytes))

        return KeyPair(publicKey, privateKey)
    }

    /**
     * 生成发送给手机的 AUTH 公钥包
     */
    fun getAdbAuthPayload(): ByteArray {
        val pubBase64 = File(context.filesDir, pubFileName).readText().trim()
        // ADB 协议要求公钥字符串后跟 " 描述符\0"
        return "$pubBase64 adb@kitty\u0000".toByteArray(Charsets.UTF_8)
    }

    /**
     * 对手机发来的 Token 进行 RSA 签名
     */
    fun signAdbToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        // 明确指定使用 BC 提供者进行 SHA1withRSA 签名
        val signer = Signature.getInstance("SHA1withRSA", "BC")
        signer.initSign(privateKey)
        signer.update(token)
        return signer.sign()
    }
}
