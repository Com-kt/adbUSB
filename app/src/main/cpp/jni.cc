#include <jni.h>
#include <string>

#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>

#include "adb_auth.h"

static RSA* load_private_key_from_der(
        const uint8_t* data,
        size_t len) {

    const uint8_t* p = data;

    EVP_PKEY* pkey = d2i_AutoPrivateKey(
            nullptr,
            &p,
            len);

    if (!pkey) {
        return nullptr;
    }

    RSA* rsa = EVP_PKEY_get1_RSA(pkey);

    EVP_PKEY_free(pkey);

    return rsa;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_adb_kitty_AdbAuth_nativeSignToken(
        JNIEnv* env,
        jobject,
        jbyteArray token_,
        jbyteArray privateKeyDer_) {

    const jsize token_len =
            env->GetArrayLength(token_);

    const jsize key_len =
            env->GetArrayLength(privateKeyDer_);

    if (token_len != TOKEN_SIZE || key_len <= 0) {
        return nullptr;
    }

    jbyte* token =
            env->GetByteArrayElements(token_, nullptr);

    jbyte* key =
            env->GetByteArrayElements(privateKeyDer_, nullptr);

    RSA* rsa = load_private_key_from_der(
            reinterpret_cast<uint8_t*>(key),
            key_len);

    env->ReleaseByteArrayElements(
            privateKeyDer_,
            key,
            JNI_ABORT);

    if (!rsa) {
        env->ReleaseByteArrayElements(
                token_,
                token,
                JNI_ABORT);

        return nullptr;
    }

    std::string sig = adb_auth_sign(
            rsa,
            reinterpret_cast<const char*>(token),
            token_len);

    RSA_free(rsa);

    env->ReleaseByteArrayElements(
            token_,
            token,
            JNI_ABORT);

    if (sig.empty()) {
        return nullptr;
    }

    jbyteArray out =
            env->NewByteArray(sig.size());

    env->SetByteArrayRegion(
            out,
            0,
            sig.size(),
            reinterpret_cast<const jbyte*>(sig.data()));

    return out;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_adb_kitty_AdbAuth_nativeGetPublicKey(
        JNIEnv* env,
        jobject,
        jbyteArray privateKeyDer_) {

    const jsize key_len =
            env->GetArrayLength(privateKeyDer_);

    if (key_len <= 0) {
        return nullptr;
    }

    jbyte* key =
            env->GetByteArrayElements(
                    privateKeyDer_,
                    nullptr);

    RSA* rsa = load_private_key_from_der(
            reinterpret_cast<uint8_t*>(key),
            key_len);

    env->ReleaseByteArrayElements(
            privateKeyDer_,
            key,
            JNI_ABORT);

    if (!rsa) {
        return nullptr;
    }

    std::string pubkey =
            adb_auth_pubkey(rsa);

    RSA_free(rsa);

    if (pubkey.empty()) {
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(pubkey.size());
    if (out == nullptr) {
        return nullptr;
    }

    env->SetByteArrayRegion(
            out,
            0,
            pubkey.size(),
            reinterpret_cast<const jbyte*>(pubkey.data()));

    return out;
}
