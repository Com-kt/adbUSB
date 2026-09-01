#include "common.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_data_NativeLibs_VerifyAllSignatures(JNIEnv* env, jobject thiz, jstring apk_path_obj) {
    if (!apk_path_obj) return JNI_FALSE;
    const char* apk_path = env->GetStringUTFChars(apk_path_obj, nullptr);
    if (!apk_path) return JNI_FALSE;

    std::string path_str(apk_path);
    env->ReleaseStringUTFChars(apk_path_obj, apk_path);

    std::unordered_map<uint32_t, std::vector<uint8_t>> pairs;
    if (!parse_apk_signing_block(path_str, pairs)) {
        return JNI_FALSE;
    }

    const std::string expected_v2_sha256            = "926f6e0360e3e8f6ddc4e9d9fff0749d73085a6478d461a763ac489f4c879576";
    const std::string expected_v3_sha256            = "926f6e0360e3e8f6ddc4e9d9fff0749d73085a6478d461a763ac489f4c879576";
    
    const std::string expected_v31_sha256           = "a5ba3eccfc226ecb62d593cfbb826c0be79b59480cf7bec2a8c54659dc0a55fa";
    
    const std::string expected_v32_classical_sha256 = "d94ad857dd9ce54f60825db5e8731b55fbe9269d4640c0abdac3bd6ab86e60ec";
    const std::string expected_v32_pqc_sha256       = "18ec5654a9e10967eb5a7fa0ffd3ce96f7710441054372d975d582d68da3c943";

    bool v2_ok  = verify_v2_signature(pairs, expected_v2_sha256);
    bool v3_ok  = verify_v3_signature(pairs, expected_v3_sha256);
    bool v31_ok = verify_v31_signature(pairs, expected_v31_sha256);

    bool v32_ok = verify_v32_signature(pairs, expected_v32_classical_sha256, expected_v32_pqc_sha256);

    if (v2_ok && v3_ok && v31_ok && v32_ok) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}
