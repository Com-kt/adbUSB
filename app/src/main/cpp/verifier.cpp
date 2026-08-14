#include "common.h"

// v2 / v3 / v3.1 签名块 ID
constexpr uint32_t APK_SIGNATURE_SCHEME_V2_BLOCK_ID  = 0x7109871a;
constexpr uint32_t APK_SIGNATURE_SCHEME_V3_BLOCK_ID  = 0xf05368c0;
constexpr uint32_t APK_SIGNATURE_SCHEME_V31_BLOCK_ID = 0x1b93ad61;

// v3.2 混合签名块 ID
constexpr uint32_t APK_SIGNATURE_SCHEME_V32_BLOCK_ID = 0x70e1c89f;

// v3.0 / v3.1 附加属性中的 v3.2 剥离保护 (Strip Protection) 属性 ID
constexpr uint32_t V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID = 0xbf940529;
constexpr uint32_t V32_STRIP_PROTECTION_MAX_SDK_ATTR_ID = 0x9f06b79e;

bool verify_v2_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V2_BLOCK_ID);
    if (it == pairs.end()) return false;

    const auto& payload = it->second;
    std::string cert_sha256 = get_block_cert_sha256(payload.data(), payload.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

bool verify_v3_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
    if (it == pairs.end()) return false;

    const auto& payload = it->second;
    std::string cert_sha256 = get_block_cert_sha256(payload.data(), payload.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

bool verify_v31_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, const std::string& expected_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V31_BLOCK_ID);
    if (it == pairs.end()) return false;

    const auto& payload = it->second;
    std::string cert_sha256 = get_block_cert_sha256(payload.data(), payload.size());
    return (!cert_sha256.empty() && cert_sha256 == expected_sha256);
}

/**
 * @brief 校验 v3.2 PQC 混合签名
 * 依据文档：必须包含 2 个签名者（Classical 与 PQC），且需匹配传入的证书哈希（支持匹配任意一个合法签名者或要求两者同时校验）
 */
bool verify_v32_signature(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs, 
                         const std::string& expected_classical_sha256,
                         const std::string& expected_pqc_sha256) {
    auto it = pairs.find(APK_SIGNATURE_SCHEME_V32_BLOCK_ID);
    if (it == pairs.end()) return false;

    const auto& payload = it->second;

    // 1. 检查 v3.2 是否恰好包含 2 个签名者 (文档要求：少于或多于 2 个均为验证失败)
    size_t signer_count = get_v32_signer_count(payload.data(), payload.size());
    if (signer_count != 2) {
        return false;
    }

    // 2. 分别提取 Classical (经典) 签名者和 PQC 签名者的证书 SHA-256
    std::string classical_cert_sha256 = get_v32_signer_cert_sha256(payload.data(), payload.size(), /* signer_index = */ 0);
    std::string pqc_cert_sha256       = get_v32_signer_cert_sha256(payload.data(), payload.size(), /* signer_index = */ 1);

    if (classical_cert_sha256.empty() || pqc_cert_sha256.empty()) {
        return false;
    }

    // 3. 校验经典证书与 PQC 证书指纹
    bool classical_match = (classical_cert_sha256 == expected_classical_sha256);
    bool pqc_match       = (pqc_cert_sha256 == expected_pqc_sha256);

    return classical_match && pqc_match;
}

/**
 * @brief v3.2 防剥离攻击校验 (Strip Protection Check)
 * 检查 v3.0 / v3.1 的附加属性区中是否含有 0xbf940529 / 0x9f06b79e 属性。
 * 如果存在该属性，但 pairs 中缺失 v3.2 分块，说明 APK 被篡改剥离了 v3.2 签名。
 */
bool has_v32_strip_protection_violation(const std::unordered_map<uint32_t, std::vector<uint8_t>>& pairs) {
    bool v32_exists = (pairs.find(APK_SIGNATURE_SCHEME_V32_BLOCK_ID) != pairs.end());

    // 检查 v3.0 或 v3.1 是否声明了 v3.2 防剥离保护标志
    auto v3_it = pairs.find(APK_SIGNATURE_SCHEME_V3_BLOCK_ID);
    auto v31_it = pairs.find(APK_SIGNATURE_SCHEME_V31_BLOCK_ID);

    bool v3_has_protection = (v3_it != pairs.end()) && 
        has_additional_attribute(v3_it->second, V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID);
    bool v31_has_protection = (v31_it != pairs.end()) && 
        has_additional_attribute(v31_it->second, V32_STRIP_PROTECTION_MIN_SDK_ATTR_ID);

    // 如果 v3/v3.1 标记了防剥离属性，但 v3.2 签名块不存在，即为非法剥离
    if ((v3_has_protection || v31_has_protection) && !v32_exists) {
        return true; // 存在剥离违规
    }

    return false;
}
