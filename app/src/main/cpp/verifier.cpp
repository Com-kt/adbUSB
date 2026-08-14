#include "common.h"

constexpr uint32_t V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID = 0xbf940529;
constexpr uint32_t V32_STRIP_PROTECTION_MAX_SDK_ATTR_ID = 0x9f06b79e;

constexpr uint32_t APK_SIGNATURE_SCHEME_V2_BLOCK_ID  = 0x7109871a;
constexpr uint32_t APK_SIGNATURE_SCHEME_V3_BLOCK_ID  = 0xf05368c0;
constexpr uint32_t APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;
constexpr uint32_t APK_SIGNATURE_SCHEME_V32_BLOCK_ID = 0x70e1c89f;

static bool has_additional_attribute(const std::vector<uint8_t>& block_payload, uint32_t target_attr_id) {
    if (block_payload.size() < 4) return false;
    const uint8_t* ptr = block_payload.data();
    size_t size = block_payload.size();

    for (size_t i = 0; i + 4 <= size; ++i) {
        uint32_t id = ptr[i] | (ptr[i + 1] << 8) | (ptr[i + 2] << 16) | (ptr[i + 3] << 24);
        if (id == target_attr_id) {
            return true;
        }
    }
    return false;
}

bool verify_v2_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
    if (it == pairs.end()) return false;
    std::string cert_sha256 = get_block_cert_sha256(it->second.data(), it->second.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

bool verify_v3_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
    if (it == pairs.end()) return false;
    std::string cert_sha256 = get_block_cert_sha256(it->second.data(), it->second.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

bool verify_v31_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V31_BLOCK_ID);
    if (it == pairs.end()) return false;
    std::string cert_sha256 = get_block_cert_sha256(it->second.data(), it->second.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

bool verify_v32_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, 
                         const std::string& expected_classical_sha256,
                         const std::string& expected_pqc_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V32_BLOCK_ID);
    if (it == pairs.end()) return false;

    const auto& payload = it->second;

    size_t signer_count = get_signer_count(payload.data(), payload.size());
    if (signer_count != 2) {
        return false;
    }

    std::string classical_cert_sha256 = get_signer_cert_sha256(payload.data(), payload.size(), 0);
    std::string pqc_cert_sha256       = get_signer_cert_sha256(payload.data(), payload.size(), 1);

    if (classical_cert_sha256.empty() || pqc_cert_sha256.empty()) {
        return false;
    }

    return (classical_cert_sha256 == expected_classical_sha256) && (pqc_cert_sha256 == expected_pqc_sha256);
}

bool has_v32_strip_protection_violation(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs) {
    bool v32_exists = (pairs.find(APK_SIGNATURE_SCHEME_V32_BLOCK_ID) != pairs.end());

    auto v3_it  = pairs.find(APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
    auto v31_it = pairs.find(APK_SIGNATURE_SCHEME_V31_BLOCK_ID);

    bool v3_has_protection  = (v3_it != pairs.end()) && has_additional_attribute(v3_it->second, V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID);
    bool v31_has_protection = (v31_it != pairs.end()) && has_additional_attribute(v31_it->second, V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID);

    if ((v3_has_protection || v31_has_protection) && !v32_exists) {
        return true;
    }

    return false;
}
