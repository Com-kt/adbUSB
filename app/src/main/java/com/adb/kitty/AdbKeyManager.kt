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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec

/**
 * ADB 密钥管理器 - 完整实现
 * 生成符合 Android 握手协议 (QAAAA...) 格式的公钥
 */
class AdbKeyManager(private val context: Context) {
    private val privFileName = "adbkey"
    private val pubFileName = "adbkey.pub"
    private val versionFileName = "version.json"
    private val CURRENT_VERSION = 8

    companion object {
        private const val TAG = "AdbKeyManager"
        init {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun getKeys(): KeyPair {
        val privFile = File(context.filesDir, privFileName)
        return if (!privFile.exists() || shouldRebuild()) {
            Log.d(TAG, "Generating new keys...")
            generateKeys()
        } else {
            try {
                loadKeys()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load keys, regenerating", e)
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

    private fun generateKeys(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA", "BC")
        kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val kp = kpg.generateKeyPair()

        // 1. 保存私钥 (PEM 格式)
        val sw = StringWriter()
        val pemWriter = JcaPEMWriter(sw)
        pemWriter.writeObject(kp.private)
        pemWriter.close()
        context.openFileOutput(privFileName, Context.MODE_PRIVATE).use {
            it.write(sw.toString().toByteArray())
        }

        // 2. 转换为 ADB 专用的 RSAPublicKey 结构并保存为 Base64 (QAAAA...)
        val adbRawPub = convertToAdbFormat(kp.public as RSAPublicKey)
        val pubBase64 = Base64.encodeToString(adbRawPub, Base64.NO_WRAP)
        context.openFileOutput(pubFileName, Context.MODE_PRIVATE).use {
            it.write(pubBase64.toByteArray())
        }

        // 3. 记录版本
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("version", CURRENT_VERSION).toString().toByteArray())
        }

        return kp
    }

    private fun loadKeys(): KeyPair {
        val privContent = File(context.filesDir, privFileName).readText()
        val pemParser = PEMParser(StringReader(privContent))
        val converter = JcaPEMKeyConverter().setProvider("BC")

        val pemObject = pemParser.readObject() ?: throw IllegalStateException("Key file empty")
        
        val privateKey = when (pemObject) {
            is PrivateKeyInfo -> converter.getPrivateKey(pemObject)
            is PEMKeyPair -> converter.getKeyPair(pemObject).private
            else -> throw IllegalStateException("Unknown key type")
        }

        // 关键点：不再从 adbkey.pub 解析公钥对象，而是从私钥中恢复
        // 因为 adbkey.pub 现在是 ADB 结构，Java 标准库无法直接解析它
        val rsaPriv = privateKey as RSAPrivateCrtKey
        val kf = KeyFactory.getInstance("RSA", "BC")
        val publicKey = kf.generatePublic(RSAPublicKeySpec(rsaPriv.modulus, rsaPriv.publicExponent))

        return KeyPair(publicKey, privateKey)
    }

    /**
     * 将 RSA 公钥转换为符合 Android mincrypt 定义的二进制结构
     * 这样生成的 Base64 就会以 QAAAA 开头
     */
    private fun convertToAdbFormat(pubKey: RSAPublicKey): ByteArray {
        val nwords = 64 // 2048 bits / 32 bits
        // Header(8 bytes) + Modulus(256) + RR(256) + Exponent(4) = 524 bytes
        val buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)

        // 1. Nwords
        buffer.putInt(nwords)

        // 2. n0inv (通常设为 -1 即可，ADB 握手主要校验 Modulus)
        buffer.putInt(-0x1)

        // 3. Modulus (需要转换为小端序并补齐 256 字节)
        val modulusBytes = pubKey.modulus.toByteArray()
        // 去除 BigInteger 可能存在的符号位（0x00）
        val cleanModulus = if (modulusBytes[0] == 0.toByte()) {
            modulusBytes.copyOfRange(1, modulusBytes.size)
        } else {
            modulusBytes
        }
        val reversedModulus = cleanModulus.reversedArray()
        val paddedModulus = ByteArray(256)
        System.arraycopy(reversedModulus, 0, paddedModulus, 0, reversedModulus.size.coerceAtMost(256))
        buffer.put(paddedModulus)

        // 4. RR (Montgomery 预计算参数，手机端会重新计算，此处填充 256 个零即可)
        buffer.put(ByteArray(256))

        // 5. Exponent
        buffer.putInt(pubKey.publicExponent.toInt())

        return buffer.array()
    }

    fun getAdbAuthPayload(): ByteArray {
        val pubFile = File(context.filesDir, pubFileName)
        if (!pubFile.exists()) return byteArrayOf()
        
        val pubBase64 = pubFile.readText().trim()
        // 格式必须严格遵循：Base64公钥 + 空格 + 描述符 + \0
        return "$pubBase64 adb@kitty\u0000".toByteArray(Charsets.UTF_8)
    }

    fun signAdbToken(token: ByteArray, privateKey: PrivateKey): ByteArray {
        val signer = Signature.getInstance("SHA1withRSA", "BC")
        signer.initSign(privateKey)
        signer.update(token)
        return signer.sign()
    }
}
