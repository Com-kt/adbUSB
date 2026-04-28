package com.adb.kitty

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbKeyManager(private val context: Context) {
    private val privFileName = "adbkey"
    private val pubFileName = "adbkey.pub"
    private val versionFileName = "version.json"
    private val CURRENT_VERSION = 2
    
    fun getKeys(): KeyPair {
        val privFile = File(context.filesDir, privFileName)
        val verFile = File(context.filesDir, versionFileName)

        // 仅在版本不匹配时重建
        if (shouldRebuild(verFile)) {
            privFile.delete()
            File(context.filesDir, pubFileName).delete()
            val kp = generateKeys()
            saveVersion()
            return kp
        }
        return loadKeys()
    }

    private fun shouldRebuild(verFile: File): Boolean {
        if (!verFile.exists()) return true
        return try {
            JSONObject(verFile.readText()).getInt("version") < CURRENT_VERSION
        } catch (e: Exception) { true }
    }
    
    private fun saveVersion() {
        context.openFileOutput(versionFileName, Context.MODE_PRIVATE).use {
            it.write(JSONObject().put("version", CURRENT_VERSION).toString().toByteArray())
        }
    }

    private fun generateKeys(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val kp = kpg.generateKeyPair()

        // 1. 保存私钥 (保持不变)
        val privBase64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
        context.openFileOutput(privFileName, Context.MODE_PRIVATE).use {
            it.write("-----BEGIN PRIVATE KEY-----\n$privBase64\n-----END PRIVATE KEY-----".toByteArray())
        }

        // 2. 生成并保存 ADB 格式公钥 (核心修复)
        val rsaPub = kp.public as RSAPublicKey
        val mod = rsaPub.modulus.toByteArray()
        val n = if (mod.size == 257 && mod[0] == 0.toByte()) mod.copyOfRange(1, 257) else mod
        val e = rsaPub.publicExponent.toByteArray()

        val buffer = ByteBuffer.allocate(4 + n.size + e.size + 20)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(n.size)
        buffer.put(n)
        buffer.put(e)
        buffer.put(" adb@kitty".toByteArray())

        val adbPubBase64 = Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
        context.openFileOutput(pubFileName, Context.MODE_PRIVATE).use {
            it.write("$adbPubBase64 adb@kitty".toByteArray())
        }
        return kp
    }

    private fun loadKeys(): KeyPair {
        val pem = context.openFileInput(privFileName).bufferedReader().readText()
        val cleanKey = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\n", "").replace("\r", "").trim()
        val privBytes = Base64.decode(cleanKey, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("RSA")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes)) as RSAPrivateKey
        val pubSpec = RSAPublicKeySpec(privKey.modulus, RSAKeyGenParameterSpec.F4)
        val pubKey = kf.generatePublic(pubSpec) as PublicKey
        return KeyPair(pubKey, privKey)
    }

    fun getPublicKeyBase64(): String = context.openFileInput(pubFileName).bufferedReader().readText()
}