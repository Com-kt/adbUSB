package com.adb.kitty

import java.security.PrivateKey

object AdbAuth {

    init {
        System.loadLibrary("adb_auth")
    }

    private external fun nativeSignToken(
        token: ByteArray,
        privateKeyDer: ByteArray
    ): ByteArray?
    
    private external fun nativeGetPublicKey(
        privateKeyDer: ByteArray
    ): ByteArray?

    fun signAdbToken(
        token: ByteArray,
        privateKey: PrivateKey
    ): ByteArray {
        require(token.size == 20) {
            "ADB token must be 20 bytes"
        }

        val der = privateKey.encoded
            ?: error("PrivateKey has no encoded form")

        return nativeSignToken(token, der)
            ?: error("ADB auth sign failed")
    }

    fun auth_pubkey(privateKey: PrivateKey): ByteArray {
        val der = privateKey.encoded
            ?: error("PrivateKey has no encoded form")
            
        return nativeGetPublicKey(der)
            ?: error("Failed to get public key from private key")
    }
}
