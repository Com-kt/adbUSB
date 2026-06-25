package com.adb.kitty.compose.data

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    
    private const val ITERATION_COUNT = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /**
     * 加密文件：输出后缀为 .enc 的加密文件
     */
    fun encryptFile(inputFile: File, outputFile: File, password: CharSequence): Boolean {
        if (!inputFile.exists()) return false
        return try {
            val secureRandom = SecureRandom()
            val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }

            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            val plainBytes = inputFile.readBytes()
            val cipherBytes = cipher.doFinal(plainBytes)

            outputFile.outputStream().use { fos ->
                fos.write(salt)
                fos.write(iv)
                fos.write(cipherBytes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 解密文件
     */
    fun decryptFile(encryptedFile: File, outputFile: File, password: CharSequence): Boolean {
        if (!encryptedFile.exists()) return false
        return try {
            val fileBytes = encryptedFile.readBytes()
            if (fileBytes.size < SALT_LENGTH + IV_LENGTH) return false

            val salt = fileBytes.copyOfRange(0, SALT_LENGTH)
            val iv = fileBytes.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
            val cipherBytes = fileBytes.copyOfRange(SALT_LENGTH + IV_LENGTH, fileBytes.size)

            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val spec = PBEKeySpec(password.toString().toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
            val tmp = factory.generateSecret(spec)
            val secretKey = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val decryptedBytes = cipher.doFinal(cipherBytes)

            outputFile.writeBytes(decryptedBytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
