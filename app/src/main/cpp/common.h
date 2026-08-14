#ifndef COMMON_H
#define COMMON_H

#include <jni.h>
#include <iostream>
#include <string>
#include <vector>
#include <unordered_map>
#include <fstream>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>

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
    void transform(const uint8_t* block);

public:
    StandaloneSHA256();
    void update(const uint8_t* data, size_t len);
    std::string finalize();
};

uint32_t read_uint32_le(const uint8_t*& ptr);

// 提取指定索引 (signer_index) 的签名者证书 SHA256 (用于 v3.2 提取 Classical 和 PQC)
std::string get_signer_cert_sha256(const uint8_t* payload, size_t payload_size, size_t signer_index = 0);
size_t get_signer_count(const uint8_t* payload, size_t payload_size);
std::string get_block_cert_sha256(const uint8_t* payload, size_t payload_size);

bool parse_apk_signing_block(const std::string& apk_path, std::unordered_map<uint32_t, std::vector<uint8_t>>& out_pairs);

bool verify_v1_signature(const std::string& apk_path, const std::string& expected_sha256);
bool verify_v2_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256);
bool verify_v3_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256);
bool verify_v31_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256);

// v3.2 混合签名接收 2 个指纹（Classical + PQC）
bool verify_v32_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, 
                         const std::string& expected_classical_sha256, 
                         const std::string& expected_pqc_sha256);

#endif // COMMON_H
