#include <system_error>
#include <cstdlib>
#include <jni.h>
#include <iostream>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <list>
#include <map>
#include <set>
#include <unordered_map> 
#include <unordered_set>
#include <fstream>
#include <memory>
#include <thread>
#include <chrono>
#include <exception>
#include <android/log.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <fcntl.h>
#include <cstring>
#include <unistd.h>
#include <cstdlib>
#include <cstdio>

const char* USER_KITTY_CONSTANT = "FAILunknown";

extern "C" JNIEXPORT jstring JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_UserString(JNIEnv *env, jobject) {
    return env->NewStringUTF(USER_KITTY_CONSTANT);
}

#define HARD_ASSERT(env, ptr, exit_code) \
    if (!(ptr)) { \
        if ((env)->ExceptionCheck()) { (env)->ExceptionClear(); } \
        std::exit(exit_code); \
    }

const uint8_t EXPECTED_SHA256[32] = {
    0x92, 0x6f, 0x6e, 0x03, 0x60, 0xe3, 0xe8, 0xf6,
    0xdd, 0xc4, 0xe9, 0xd9, 0xff, 0xf0, 0x74, 0x9d,
    0x73, 0x08, 0x5a, 0x64, 0x78, 0xd4, 0x61, 0xa7,
    0x63, 0xac, 0x48, 0x9f, 0x4c, 0x87, 0x95, 0x76
};

inline std::string _XOR_(const std::vector<uint8_t>& encrypted) {
    std::string decrypted;
    decrypted.reserve(encrypted.size());
    for (uint8_t b : encrypted) {
        decrypted.push_back(static_cast<char>(b ^ 0x5A));
    }
    return decrypted;
}

bool verifyApkSigningBlock(const std::string& apkPath) {
    std::ifstream apk(apkPath, std::ios::binary | std::ios::ate);
    if (!apk.is_open()) return false;

    long long fileSize = static_cast<long long>(apk.tellg());
    if (fileSize < 22) return false;

    long long readSize = (fileSize > 1024) ? 1024 : fileSize;
    apk.seekg(-readSize, std::ios::end);
    std::vector<char> eocdBuffer(readSize);
    apk.read(eocdBuffer.data(), readSize);

    long long eocdOffset = -1;
    for (long long i = readSize - 22; i >= 0; --i) {
        if (eocdBuffer[i] == 0x50 && eocdBuffer[i+1] == 0x4B && eocdBuffer[i+2] == 0x05 && eocdBuffer[i+3] == 0x06) {
            eocdOffset = fileSize - readSize + i;
            break;
        }
    }
    if (eocdOffset == -1) return false;

    apk.seekg(eocdOffset + 16, std::ios::beg);
    uint32_t cdOffset = 0;
    apk.read(reinterpret_cast<char*>(&cdOffset), 4);
    if (cdOffset < 32) return false;

    long long searchOffset = (cdOffset > 4096) ? (cdOffset - 4096) : 0;
    long long searchSize = cdOffset - searchOffset;

    apk.seekg(searchOffset, std::ios::beg);
    std::string buffer(searchSize, '\0');
    apk.read(&buffer[0], searchSize);
    apk.close();

    std::vector<uint8_t> enc_block = {0x1b, 0x0a, 0x11, 0x7a, 0x09, 0x33, 0x3d, 0x7a, 0x18, 0x36, 0x35, 0x39, 0x31, 0x7a, 0x6e, 0x68};
    if (buffer.find(_XOR_(enc_block)) == std::string::npos) {
        return false;
    }
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_nativeVerify(JNIEnv *env, jobject thiz, jobject context) {
    if (!context) {
        std::abort();
    }

    jclass contextClz = env->GetObjectClass(context);
    HARD_ASSERT(env, contextClz, 100);
    
    jmethodID getPackageCodePathMd = env->GetMethodID(contextClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x19, 0x35, 0x3e, 0x3f, 0x0a, 0x3b, 0x2e, 0x32 }).c_str(), 
        _XOR_({ 0x72, 0x73, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61 }).c_str());
    HARD_ASSERT(env, getPackageCodePathMd, 101);
    
    auto jApkPath = (jstring)env->CallObjectMethod(context, getPackageCodePathMd);
    HARD_ASSERT(env, jApkPath, 102);
    
    const char* apkPathChars = env->GetStringUTFChars(jApkPath, nullptr);
    std::string apkPath(apkPathChars);
    env->ReleaseStringUTFChars(jApkPath, apkPathChars);

    if (!verifyApkSigningBlock(apkPath)) {
        std::exit(401);
    }

    jmethodID getPackageManagerMd = env->GetMethodID(contextClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x17, 0x3b, 0x34, 0x3b, 0x3d, 0x3f, 0x28 }).c_str(), 
        _XOR_({ 0x72, 0x73, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x17, 0x3b, 0x34, 0x3b, 0x3d, 0x3f, 0x28, 0x61 }).c_str());
    HARD_ASSERT(env, getPackageManagerMd, 103);
    
    jobject packageManager = env->CallObjectMethod(context, getPackageManagerMd);
    HARD_ASSERT(env, packageManager, 104);
    
    jmethodID getPackageNameMd = env->GetMethodID(contextClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x14, 0x3b, 0x37, 0x3f }).c_str(), 
        _XOR_({ 0x72, 0x73, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61 }).c_str());
    HARD_ASSERT(env, getPackageNameMd, 105);
    
    auto packageName = (jstring)env->CallObjectMethod(context, getPackageNameMd);
    HARD_ASSERT(env, packageName, 106);
    
    jclass pmClz = env->GetObjectClass(packageManager);
    HARD_ASSERT(env, pmClz, 107);

    jmethodID getPackageInfoMd = env->GetMethodID(pmClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x13, 0x34, 0x3c, 0x35 }).c_str(), 
        _XOR_({ 0x72, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61, 0x13, 0x73, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x0a, 0x3b, 0x39, 0x31, 0x3b, 0x3d, 0x3f, 0x13, 0x34, 0x3c, 0x35, 0x61 }).c_str());
    HARD_ASSERT(env, getPackageInfoMd, 108);
    
    jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfoMd, packageName, 0x08000000);
    HARD_ASSERT(env, packageInfo, 109);
    
    jclass packageInfoClz = env->GetObjectClass(packageInfo);
    HARD_ASSERT(env, packageInfoClz, 110);
    
    jfieldID signingInfoFd = env->GetFieldID(packageInfoClz, 
        _XOR_({ 0x29, 0x33, 0x3d, 0x34, 0x33, 0x34, 0x3d, 0x13, 0x34, 0x3c, 0x35 }).c_str(), 
        _XOR_({ 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x09, 0x33, 0x3d, 0x34, 0x33, 0x34, 0x3d, 0x13, 0x34, 0x3c, 0x35, 0x61 }).c_str());
    HARD_ASSERT(env, signingInfoFd, 111);
    
    jobject signingInfo = env->GetObjectField(packageInfo, signingInfoFd);
    HARD_ASSERT(env, signingInfo, 112);
    
    jclass signingInfoClz = env->GetObjectClass(signingInfo);
    HARD_ASSERT(env, signingInfoClz, 113);
    
    jmethodID getApkContentsSignersMd = env->GetMethodID(signingInfoClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x1b, 0x2a, 0x31, 0x19, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x29, 0x09, 0x33, 0x3d, 0x34, 0x3f, 0x28, 0x29 }).c_str(), 
        _XOR_({ 0x72, 0x73, 0x01, 0x16, 0x3b, 0x34, 0x3e, 0x28, 0x35, 0x33, 0x3e, 0x75, 0x39, 0x35, 0x34, 0x2e, 0x3f, 0x34, 0x2e, 0x75, 0x2a, 0x37, 0x75, 0x09, 0x33, 0x3d, 0x34, 0x3b, 0x2e, 0x2f, 0x28, 0x3f, 0x61 }).c_str());
    HARD_ASSERT(env, getApkContentsSignersMd, 114);
    
    auto signatureArray = (jobjectArray)env->CallObjectMethod(signingInfo, getApkContentsSignersMd);
    
    jobject signatureObj = nullptr;
    if (signatureArray && env->GetArrayLength(signatureArray) > 0) {
        signatureObj = env->GetObjectArrayElement(signatureArray, 0);
    }
    HARD_ASSERT(env, signatureObj, 115);

    jclass signatureClz = env->GetObjectClass(signatureObj);
    HARD_ASSERT(env, signatureClz, 116);
    
    jmethodID toByteArrayMd = env->GetMethodID(signatureClz, 
        _XOR_({ 0x2e, 0x35, 0x18, 0x23, 0x2e, 0x3f, 0x1b, 0x28, 0x28, 0x3b, 0x23 }).c_str(), 
        _XOR_({ 0x72, 0x73, 0x01, 0x18 }).c_str());
    HARD_ASSERT(env, toByteArrayMd, 117);
    
    auto certificateBytes = (jbyteArray)env->CallObjectMethod(signatureObj, toByteArrayMd);
    HARD_ASSERT(env, certificateBytes, 118);

    jclass messageDigestClz = env->FindClass(_XOR_({ 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x17, 0x3f, 0x29, 0x29, 0x3b, 0x3d, 0x3f, 0x1e, 0x33, 0x3d, 0x3f, 0x29, 0x2e }).c_str());
    HARD_ASSERT(env, messageDigestClz, 119);
    
    jmethodID getInstanceMd = env->GetStaticMethodID(messageDigestClz, 
        _XOR_({ 0x3d, 0x3f, 0x2e, 0x13, 0x34, 0x29, 0x2e, 0x3b, 0x34, 0x39, 0x3f }).c_str(), 
        _XOR_({ 0x72, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x09, 0x2e, 0x28, 0x33, 0x34, 0x3d, 0x61, 0x73, 0x16, 0x30, 0x3b, 0x2c, 0x3b, 0x75, 0x36, 0x3b, 0x34, 0x3d, 0x75, 0x17, 0x3f, 0x29, 0x29, 0x3b, 0x3d, 0x3f, 0x1e, 0x33, 0x3d, 0x3f, 0x29, 0x2e, 0x61 }).c_str());
    HARD_ASSERT(env, getInstanceMd, 120);
    
    jstring algoNameStr = env->NewStringUTF(_XOR_({ 0x09, 0x12, 0x1b, 0x77, 0x68, 0x6f, 0x6c }).c_str());
    jobject messageDigest = env->CallStaticObjectMethod(messageDigestClz, getInstanceMd, algoNameStr);
    env->DeleteLocalRef(algoNameStr);
    HARD_ASSERT(env, messageDigest, 121);
    
    jmethodID digestMd = env->GetMethodID(messageDigestClz, 
        _XOR_({ 0x3e, 0x33, 0x3d, 0x3f, 0x29, 0x2e }).c_str(), 
        _XOR_({ 0x28, 0x5b, 0x42, 0x29, 0x5b, 0x42 }).c_str());
    HARD_ASSERT(env, digestMd, 122);
    
    auto currentHashBytes = (jbyteArray)env->CallObjectMethod(messageDigest, digestMd, certificateBytes);
    HARD_ASSERT(env, currentHashBytes, 123);

    jsize shaLen = env->GetArrayLength(currentHashBytes);
    if (shaLen != 32) { std::exit(402); }

    jbyte* hashes = env->GetByteArrayElements(currentHashBytes, nullptr);
    HARD_ASSERT(env, hashes, 124);

    bool match = true;
    for (int i = 0; i < 32; ++i) {
        if ((static_cast<uint8_t>(hashes[i])) != EXPECTED_SHA256[i]) {
            match = false;
            break;
        }
    }
    env->ReleaseByteArrayElements(currentHashBytes, hashes, JNI_ABORT);

    if (!match) {
        std::exit(402);
    }
}
