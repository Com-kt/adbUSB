#include <system_error>
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

#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>

const char* USER_KITTY_CONSTANT = "FAILunknown";

extern "C" JNIEXPORT jstring JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_UserString(JNIEnv *env, jobject) {
    return env->NewStringUTF(USER_KITTY_CONSTANT);
}

class StandaloneSHA256 {
private:
    uint32_t state[8];
    uint64_t count;
    uint8_t buffer[64];

    static inline uint32_t rotr(uint32_t x, uint32_t n) { return (x >> n) | (x << (32 - n)); }
    static inline uint32_t choose(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (~x & z); }
    static inline uint32_t majority(uint32_t x, uint32_t y, uint32_t z) { return (x & y) ^ (x & z) ^ (y & z); }
    static inline uint32_t sig0(uint32_t x) { return rotr(x, 7) ^ rotr(x, 18) ^ (x >> 3); }
    static inline uint32_t sig1(uint32_t x) { return rotr(x, 17) ^ rotr(x, 19) ^ (x >> 10); }
    static inline uint32_t eps0(uint32_t x) { return rotr(x, 2) ^ rotr(x, 13) ^ rotr(x, 22); }
    static inline uint32_t eps1(uint32_t x) { return rotr(x, 6) ^ rotr(x, 11) ^ rotr(x, 25); }

    void transform(const uint8_t* block) {
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (block[i * 4] << 24) | (block[i * 4 + 1] << 16) | (block[i * 4 + 2] << 8) | block[i * 4 + 3];
        }
        for (int i = 16; i < 64; ++i) {
            w[i] = sig1(w[i - 2]) + w[i - 7] + sig0(w[i - 15]) + w[i - 16];
        }
        uint32_t a = state[0], b = state[1], c = state[2], d = state[3];
        uint32_t e = state[4], f = state[5], g = state[6], h = state[7];

        const uint32_t k[64] = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
        };

        for (int i = 0; i < 64; ++i) {
            uint32_t t1 = h + eps1(e) + choose(e, f, g) + k[i] + w[i];
            uint32_t t2 = eps0(a) + majority(a, b, c);
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        state[0] += a; state[1] += b; state[2] += c; state[3] += d;
        state[4] += e; state[5] += f; state[6] += g; state[7] += h;
    }

public:
    StandaloneSHA256() {
        state[0] = 0x6a09e667; state[1] = 0xbb67ae85; state[2] = 0x3c6ef372; state[3] = 0xa54ff53a;
        state[4] = 0x510e527f; state[5] = 0x9b05688c; state[6] = 0x1f83d9ab; state[7] = 0x5be0cd19;
        count = 0;
    }

    void update(const uint8_t* data, size_t len) {
        size_t index = (count >> 3) & 63;
        count += (len << 3);
        size_t partLen = 64 - index;
        size_t i = 0;
        if (len >= partLen) {
            memcpy(&buffer[index], data, partLen);
            transform(buffer);
            for (i = partLen; i + 63 < len; i += 64) {
                transform(&data[i]);
            }
            index = 0;
        }
        if (i < len) {
            memcpy(&buffer[index], &data[i], len - i);
        }
    }

    std::string finalize() {
        uint8_t final_count[8];
        for (int i = 0; i < 8; ++i) {
            final_count[i] = (count >> ((7 - i) * 8)) & 0xFF;
        }
        uint8_t pad[64];
        memset(pad, 0, 64);
        pad[0] = 0x80;
        size_t index = (count >> 3) & 63;
        size_t padLen = (index < 56) ? (56 - index) : (120 - index);
        update(pad, padLen);
        update(final_count, 8);

        std::stringstream ss;
        for (int i = 0; i < 8; ++i) {
            ss << std::hex << std::setw(8) << std::setfill('0') << state[i];
        }
        return ss.str();
    }
};

uint32_t read_uint32_le(const uint8_t*& ptr) {
    uint32_t value = ptr[0] | (ptr[1] << 8) | (ptr[2] << 16) | (ptr[3] << 24);
    ptr += 4;
    return value;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_V3Signature(JNIEnv* env, jobject thiz, jstring apk_path_obj) {
    if (!apk_path_obj) return JNI_FALSE;
    const char* apk_path = env->GetStringUTFChars(apk_path_obj, nullptr);
    if (!apk_path) return JNI_FALSE;

    std::ifstream file(apk_path, std::ios::binary | std::ios::ate);
    env->ReleaseStringUTFChars(apk_path_obj, apk_path);

    if (!file.is_open()) return JNI_FALSE;

    std::streampos file_size = file.tellg();
    if (file_size < 22) return JNI_FALSE;

    size_t read_len = (file_size > (65535 + 22)) ? (65535 + 22) : static_cast<size_t>(file_size);
    std::vector<uint8_t> buffer(read_len);
    file.seekg(file_size - static_cast<std::streamoff>(read_len));
    file.read(reinterpret_cast<char*>(buffer.data()), read_len);

    long long cd_offset = -1;
    for (long long i = static_cast<long long>(read_len) - 22; i >= 0; --i) {
        if (buffer[i] == 0x50 && buffer[i + 1] == 0x4b && buffer[i + 2] == 0x05 && buffer[i + 3] == 0x06) {
            uint32_t offset = buffer[i + 16] | (buffer[i + 17] << 8) | (buffer[i + 18] << 16) | (buffer[i + 19] << 24);
            cd_offset = offset;
            break;
        }
    }
    if (cd_offset < 24) return JNI_FALSE;

    file.seekg(cd_offset - 24);
    uint8_t footer[24];
    file.read(reinterpret_cast<char*>(footer), 24);

    uint64_t size_of_block_le = footer[0] | 
                                (static_cast<uint64_t>(footer[1]) << 8) |
                                (static_cast<uint64_t>(footer[2]) << 16) |
                                (static_cast<uint64_t>(footer[3]) << 24) |
                                (static_cast<uint64_t>(footer[4]) << 32) |
                                (static_cast<uint64_t>(footer[5]) << 40) |
                                (static_cast<uint64_t>(footer[6]) << 48) |
                                (static_cast<uint64_t>(footer[7]) << 56);

    const char* magic = "APK Sig Block 42";
    if (memcmp(footer + 8, magic, 16) != 0) return JNI_FALSE;

    long long block_start_offset = cd_offset - size_of_block_le - 8;
    file.seekg(block_start_offset);
    
    std::vector<uint8_t> block_data(size_of_block_le);
    file.read(reinterpret_cast<char*>(block_data.data()), size_of_block_le);

    uint64_t size_in_header = block_data[0] | 
                              (static_cast<uint64_t>(block_data[1]) << 8) |
                              (static_cast<uint64_t>(block_data[2]) << 16) |
                              (static_cast<uint64_t>(block_data[3]) << 24) |
                              (static_cast<uint64_t>(block_data[4]) << 32) |
                              (static_cast<uint64_t>(block_data[5]) << 40) |
                              (static_cast<uint64_t>(block_data[6]) << 48) |
                              (static_cast<uint64_t>(block_data[7]) << 56);

    if (size_in_header != size_of_block_le) return JNI_FALSE;

    size_t remaining = size_of_block_le - 24;
    size_t offset = 8;

    const uint8_t* v3_payload = nullptr;
    size_t v3_payload_size = 0;

    while (remaining > 12) {
        uint64_t pair_size = block_data[offset] | (static_cast<uint64_t>(block_data[offset+1]) << 8) |
                             (static_cast<uint64_t>(block_data[offset+2]) << 16) | (static_cast<uint64_t>(block_data[offset+3]) << 24) |
                             (static_cast<uint64_t>(block_data[offset+4]) << 32) | (static_cast<uint64_t>(block_data[offset+5]) << 40) |
                             (static_cast<uint64_t>(block_data[offset+6]) << 48) | (static_cast<uint64_t>(block_data[offset+7]) << 56);
        uint32_t pair_id = block_data[offset+8] | (block_data[offset+9] << 8) | (block_data[offset+10] << 16) | (block_data[offset+11] << 24);

        if (pair_id == 0xf05368c0 || pair_id == 0x1b93ad61 || pair_id == 0x7109871a) { 
            v3_payload = &block_data[offset + 12];
            v3_payload_size = pair_size - 4;
            break;
        }

        size_t consumed = 8 + pair_size;
        if (remaining < consumed) return JNI_FALSE;
        remaining -= consumed;
        offset += consumed;
    }

    if (!v3_payload || v3_payload_size < 32) return JNI_FALSE;

    const uint8_t* ptr = v3_payload;
    try {
        uint32_t total_signers_size = read_uint32_le(ptr);
        uint32_t signer_size = read_uint32_le(ptr);
        uint32_t signed_data_size = read_uint32_le(ptr);
        
        uint32_t digests_sequence_size = read_uint32_le(ptr);
        ptr += digests_sequence_size;
        
        uint32_t certs_sequence_size = read_uint32_le(ptr);
        uint32_t cert_size = read_uint32_le(ptr);
        
        size_t read_bytes = ptr - v3_payload;
        if (read_bytes + cert_size > v3_payload_size) return JNI_FALSE;

        StandaloneSHA256 sha;
        sha.update(ptr, cert_size);
        std::string current_sha256 = sha.finalize();

        std::string expected_sha256 = "926f6e0360e3e8f6ddc4e9d9fff0749d73085a6478d461a763ac489f4c879576";
        
        if (current_sha256 == expected_sha256) {
            return JNI_TRUE;
        }
    } catch (...) {
        return JNI_FALSE;
    }

    return JNI_FALSE;
}
