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
            ?: error("adb auth sign failed")
    }
}