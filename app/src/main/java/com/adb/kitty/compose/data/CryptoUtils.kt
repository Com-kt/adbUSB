package com.adb.kitty.compose.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import androidx.annotation.Keep

@Keep
object CryptoUtils {

    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding"
    
    private const val ITERATION_COUNT = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 16
    private const val BUFFER_SIZE = 8192

    /**
     * 流式加密：完美支持数 GB 级系统镜像，内存稳如老狗
     */
    fun encryptFile(inputFile: File, outputFile: File, password: CharSequence): Boolean {
        if (!inputFile.exists()) return false
        
        var fis: FileInputStream? = null
        var fos: FileOutputStream? = null
        
        return try {
            val secureRandom = SecureRandom()
            val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }

            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

            fis = FileInputStream(inputFile)
            fos = FileOutputStream(outputFile)
            fos.write(salt)
            fos.write(iv)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                val cipherBytes = cipher.update(buffer, 0, bytesRead)
                if (cipherBytes != null) {
                    fos.write(cipherBytes)
                }
                bytesRead = fis.read(buffer)
            }
            
            val finalBytes = cipher.doFinal()
            if (finalBytes != null) {
                fos.write(finalBytes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    /**
     * 流式解密
     */
    fun decryptFile(encryptedFile: File, outputFile: File, password: CharSequence): Boolean {
        if (!encryptedFile.exists()) return false
        
        var fis: FileInputStream? = null
        var fos: FileOutputStream? = null
        
        return try {
            fis = FileInputStream(encryptedFile)
            
            val salt = ByteArray(SALT_LENGTH)
            val iv = ByteArray(IV_LENGTH)
            if (fis.read(salt) != SALT_LENGTH || fis.read(iv) != IV_LENGTH) {
                return false
            }

            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            fos = FileOutputStream(outputFile)

            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                val plainBytes = cipher.update(buffer, 0, bytesRead)
                if (plainBytes != null) {
                    fos.write(plainBytes)
                }
                bytesRead = fis.read(buffer)
            }

            val finalBytes = cipher.doFinal()
            if (finalBytes != null) {
                fos.write(finalBytes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { fos?.close() } catch (_: Exception) {}
        }
    }
}
