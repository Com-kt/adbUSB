#include <jni.h>
#include <iostream>
#include <fstream>
#include <vector>
#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <algorithm>

#include <openssl/x509.h>
#include <openssl/evp.h>
#include <openssl/bn.h>
#include <openssl/bio.h>
#include <openssl/asn1.h>
#include <openssl/objects.h>
#include <openssl/core_names.h>

constexpr uint32_t APK_V2_SIGNATURE_SCHEME_ID   = 0x7109871a;
constexpr uint32_t APK_V3_SIGNATURE_SCHEME_ID   = 0xf05368c0;
constexpr uint32_t APK_V31_SIGNATURE_SCHEME_ID  = 0x1b93ad61;
constexpr uint32_t APK_V32_SIGNATURE_SCHEME_ID  = 0x70e1c89f;
constexpr uint32_t APK_SOURCE_STAMP_V1_ID       = 0x6dff800d;
constexpr uint32_t APK_SOURCE_STAMP_V2_ID       = 0x2146444e;
constexpr uint32_t APK_BUILD_METADATA_ID        = 0x42726577;
constexpr uint32_t APK_SDK_DEPENDENCY_INFO_ID   = 0x504b4453;

class BufferReader {
private:
    const uint8_t* data;
    size_t size;
    size_t pos = 0;

public:
    BufferReader(const uint8_t* d, size_t s) : data(d), size(s) {}

    bool hasRemaining() const { return pos < size; }
    size_t remaining() const { return size - pos; }

    uint32_t readU32() {
        if (pos + 4 > size) throw std::runtime_error("Read uint32 out of bounds");
        uint32_t val = data[pos] | (data[pos+1] << 8) | (data[pos+2] << 16) | (data[pos+3] << 24);
        pos += 4;
        return val;
    }

    uint64_t readU64() {
        uint64_t low = readU32();
        uint64_t high = readU32();
        return low | (high << 32);
    }

    BufferReader readLengthPrefixedSlice() {
        uint32_t len = readU32();
        if (pos + len > size) throw std::runtime_error("Read slice out of bounds");
        BufferReader slice(data + pos, len);
        pos += len;
        return slice;
    }

    std::vector<uint8_t> readBytes(size_t len) {
        if (pos + len > size) throw std::runtime_error("Read bytes out of bounds");
        std::vector<uint8_t> bytes(data + pos, data + pos + len);
        pos += len;
        return bytes;
    }
};

static std::string bytesToFullHex(const uint8_t* data, size_t len) {
    std::stringstream ss;
    for (size_t i = 0; i < len; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)data[i];
    }
    return ss.str();
}

static std::string asn1TimeToStr(const ASN1_TIME* time) {
    if (!time) return "Unknown";
    BIO* bio = BIO_new(BIO_s_mem());
    ASN1_TIME_print(bio, time);
    char buffer[256] = {0};
    BIO_read(bio, buffer, sizeof(buffer) - 1);
    BIO_free(bio);
    return std::string(buffer);
}

static std::string getSignatureAlgorithm(X509* cert) {
    int sig_nid = X509_get_signature_nid(cert);
    if (sig_nid != NID_undef) {
        const char* sn = OBJ_nid2sn(sig_nid);
        const char* ln = OBJ_nid2ln(sig_nid);
        if (sn) return std::string(sn);
        if (ln) return std::string(ln);
    }
    const X509_ALGOR* sig_alg;
    X509_get0_signature(nullptr, &sig_alg, cert);
    if (sig_alg) {
        const ASN1_OBJECT* obj;
        X509_ALGOR_get0(&obj, nullptr, nullptr, sig_alg);
        char oid_buf[128] = {0};
        OBJ_obj2txt(oid_buf, sizeof(oid_buf), obj, 1);
        return std::string("OID: ") + oid_buf;
    }
    return "Unknown";
}

static std::string getPublicKeyDetails(X509* cert) {
    EVP_PKEY* pkey = X509_get0_pubkey(cert);
    if (!pkey) return "Unknown Key";

    int type = EVP_PKEY_base_id(pkey);
    int bits = EVP_PKEY_bits(pkey);

    if (type == EVP_PKEY_RSA) {
        return std::to_string(bits) + "-bit RSA key";
    } 
    else if (type == EVP_PKEY_EC) {
        char group_name[64] = {0};
        size_t glen = 0;
        std::string curveName = "secp384r1";
        if (EVP_PKEY_get_utf8_string_param(pkey, OSSL_PKEY_PARAM_GROUP_NAME, group_name, sizeof(group_name), &glen)) {
            curveName = group_name;
        }
        return std::to_string(bits) + "-bit EC (" + curveName + ") key";
    } 
    else {
        int pkey_id = EVP_PKEY_id(pkey);
        if (pkey_id != NID_undef) {
            const char* name = OBJ_nid2sn(pkey_id);
            if (name) return std::string(name) + " key";
        }
        return "Custom/Unknown key";
    }
}

static std::string getCertDigest(X509* cert, const EVP_MD* mdType) {
    unsigned char md[EVP_MAX_MD_SIZE];
    unsigned int mdLen = 0;
    if (!X509_digest(cert, mdType, md, &mdLen)) return "Failed";
    std::stringstream ss;
    for (unsigned int i = 0; i < mdLen; i++) {
        ss << std::hex << std::setw(2) << std::setfill('0') << (int)md[i];
    }
    return ss.str();
}

static void printCertDetails(std::ostringstream& ss, const std::vector<uint8_t>& derCert) {
    const uint8_t* p = derCert.data();
    X509* cert = d2i_X509(nullptr, &p, derCert.size());
    if (!cert) {
        ss << "    * [!] Certificate DER parse failed\n";
        return;
    }

    char subject[512], issuer[512];
    X509_NAME_oneline(X509_get_subject_name(cert), subject, sizeof(subject));
    X509_NAME_oneline(X509_get_issuer_name(cert), issuer, sizeof(issuer));

    ss << "    * Owner / Subject  : " << subject << "\n";
    ss << "    * Issuer           : " << issuer << "\n";

    ASN1_INTEGER* serial = X509_get_serialNumber(cert);
    BIGNUM* bn = ASN1_INTEGER_to_BN(serial, nullptr);
    if (bn) {
        char* hexSerial = BN_bn2hex(bn);
        ss << "    * Serial Number    : " << hexSerial << "\n";
        OPENSSL_free(hexSerial);
        BN_free(bn);
    }

    ss << "    * Valid From       : " << asn1TimeToStr(X509_get0_notBefore(cert)) << "\n";
    ss << "    * Valid Until      : " << asn1TimeToStr(X509_get0_notAfter(cert)) << "\n";
    ss << "    * Signature Algo   : " << getSignatureAlgorithm(cert) << "\n";
    ss << "    * Public Key Algo  : " << getPublicKeyDetails(cert) << "\n";
    ss << "    * SHA-256 Digest   : " << getCertDigest(cert, EVP_sha256()) << "\n";
    ss << "    * SHA-1 Digest     : " << getCertDigest(cert, EVP_sha1()) << "\n";

    X509_free(cert);
}

static void parseSchemePayload(std::ostringstream& ss, const std::string& schemeName, const std::vector<uint8_t>& payload, bool isV3Family) {
    ss << "\n [ " << schemeName << " ] \n";
    try {
        BufferReader reader(payload.data(), payload.size());
        BufferReader signers = reader.readLengthPrefixedSlice();

        int signerIdx = 1;
        while (signers.hasRemaining()) {
            ss << " Signer #" << signerIdx++ << ":\n";
            BufferReader signer = signers.readLengthPrefixedSlice();
            
            BufferReader signedData = signer.readLengthPrefixedSlice();
            
            BufferReader digests = signedData.readLengthPrefixedSlice();
            while (digests.hasRemaining()) {
                BufferReader digestItem = digests.readLengthPrefixedSlice();
                uint32_t algId = digestItem.readU32();
                BufferReader rawDigest = digestItem.readLengthPrefixedSlice();
                std::vector<uint8_t> digestBytes = rawDigest.readBytes(rawDigest.remaining());
                ss << "    * Raw Digest       : [Alg ID 0x" << std::hex << algId << "] " 
                   << bytesToFullHex(digestBytes.data(), digestBytes.size()) << "\n";
            }

            BufferReader certs = signedData.readLengthPrefixedSlice();
            while (certs.hasRemaining()) {
                BufferReader certSlice = certs.readLengthPrefixedSlice();
                std::vector<uint8_t> certBytes = certSlice.readBytes(certSlice.remaining());
                printCertDetails(ss, certBytes);
            }

            if (isV3Family) {
                if (signedData.remaining() >= 8) {
                    uint32_t minSdkSigned = signedData.readU32();
                    uint32_t maxSdkSigned = signedData.readU32();
                    ss << "    * Target SDK       : minSdkVersion=" << std::dec << minSdkSigned 
                       << ", maxSdkVersion=" << maxSdkSigned << "\n";
                }
                if (signedData.hasRemaining()) {
                    BufferReader additionalAttrs = signedData.readLengthPrefixedSlice();
                }
                
                if (signer.remaining() >= 8) {
                    uint32_t minSdkSigner = signer.readU32();
                    uint32_t maxSdkSigner = signer.readU32();
                }
            }

            BufferReader signatures = signer.readLengthPrefixedSlice();
            int sigIdx = 1;
            while (signatures.hasRemaining()) {
                BufferReader sigItem = signatures.readLengthPrefixedSlice();
                uint32_t sigAlgId = sigItem.readU32();
                BufferReader rawSig = sigItem.readLengthPrefixedSlice();
                std::vector<uint8_t> sigBytes = rawSig.readBytes(rawSig.remaining());
                
                ss << "    * Raw Signature #" << sigIdx++ << " : [Alg ID 0x" << std::hex << sigAlgId 
                   << "] (" << std::dec << sigBytes.size() << " bytes)\n      " 
                   << bytesToFullHex(sigBytes.data(), sigBytes.size()) << "\n";
            }

            BufferReader rawPubKey = signer.readLengthPrefixedSlice();
            std::vector<uint8_t> pubKeyBytes = rawPubKey.readBytes(rawPubKey.remaining());
            ss << "    * Raw Public Key   : (" << std::dec << pubKeyBytes.size() << " bytes)\n      " 
               << bytesToFullHex(pubKeyBytes.data(), pubKeyBytes.size()) << "\n";
        }
    } catch (const std::exception& e) {
        ss << " [!] Struct Parsing Exception: " << e.what() << "\n";
    }
}

static void parseSourceStampV1Payload(std::ostringstream& ss, const std::vector<uint8_t>& payload) {
    ss << "\n [ Source Stamp V1 (0x6dff800d) ] \n";
    ss << "    * Block Size       : " << std::dec << payload.size() << " bytes\n";
    try {
        BufferReader reader(payload.data(), payload.size());
        if (reader.hasRemaining()) {
            BufferReader signedData = reader.readLengthPrefixedSlice();
            if (signedData.hasRemaining()) {
                BufferReader certs = signedData.readLengthPrefixedSlice();
                int certIdx = 1;
                while (certs.hasRemaining()) {
                    BufferReader certSlice = certs.readLengthPrefixedSlice();
                    std::vector<uint8_t> certBytes = certSlice.readBytes(certSlice.remaining());
                    ss << " Stamp Certificate #" << certIdx++ << ":\n";
                    printCertDetails(ss, certBytes);
                }
            }
        }
    } catch (const std::exception& e) {
        ss << "    * [!] Stamp V1 Parse Note: " << e.what() << "\n";
    }
}

static void parseSourceStampV2Payload(std::ostringstream& ss, const std::vector<uint8_t>& payload) {
    ss << "\n [ Source Stamp V2 / Lineage (0x2146444e) ] \n";
    ss << "    * Block Size       : " << std::dec << payload.size() << " bytes\n";
    try {
        BufferReader reader(payload.data(), payload.size());
        if (reader.hasRemaining()) {
            BufferReader lineageSlice = reader.readLengthPrefixedSlice();
            ss << "    * Lineage Data Size: " << std::dec << lineageSlice.remaining() << " bytes\n";
        }
    } catch (const std::exception& e) {
        ss << "    * [!] Stamp V2 Parse Note: " << e.what() << "\n";
    }
}

static bool checkBlockIdPresent(const std::string& apkPath, uint32_t targetId) {
    std::ifstream file(apkPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return false;

    size_t fileSize = file.tellg();
    if (fileSize < 22) return false;

    size_t maxSearchSize = std::min(fileSize, (size_t)65557);
    std::vector<uint8_t> buffer(maxSearchSize);
    file.seekg(fileSize - maxSearchSize);
    file.read((char*)buffer.data(), maxSearchSize);

    size_t eocdPos = 0;
    for (size_t i = maxSearchSize - 22; ; i--) {
        if (buffer[i] == 0x50 && buffer[i+1] == 0x4b && buffer[i+2] == 0x05 && buffer[i+3] == 0x06) {
            eocdPos = fileSize - maxSearchSize + i;
            break;
        }
        if (i == 0) break;
    }
    if (eocdPos == 0) return false;

    file.seekg(eocdPos + 16);
    uint32_t cdOffset;
    file.read((char*)&cdOffset, 4);
    if (cdOffset < 24) return false;

    file.seekg(cdOffset - 16);
    char magic[16];
    file.read(magic, 16);
    if (memcmp(magic, "APK Sig Block 42", 16) != 0) return false;

    file.seekg(cdOffset - 24);
    uint64_t blockSizeInFooter;
    file.read((char*)&blockSizeInFooter, 8);

    size_t blockStartPos = cdOffset - 8 - blockSizeInFooter;
    file.seekg(blockStartPos);
    
    std::vector<uint8_t> blockData(blockSizeInFooter - 16);
    file.read((char*)blockData.data(), blockData.size());

    BufferReader reader(blockData.data() + 8, blockData.size() - 8);
    while (reader.hasRemaining()) {
        try {
            uint64_t pairLen = reader.readU64();
            uint32_t id = reader.readU32();
            if (id == targetId) return true;
            reader.readBytes(pairLen - 4);
        } catch (...) {
            break;
        }
    }
    return false;
}

static bool checkV1Present(const std::string& apkPath) {
    std::ifstream file(apkPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) return false;

    size_t fileSize = file.tellg();
    if (fileSize < 22) return false;

    size_t maxSearchSize = std::min(fileSize, (size_t)65557);
    std::vector<uint8_t> buffer(maxSearchSize);
    file.seekg(fileSize - maxSearchSize);
    file.read((char*)buffer.data(), maxSearchSize);

    size_t eocdPos = 0;
    for (size_t i = maxSearchSize - 22; ; i--) {
        if (buffer[i] == 0x50 && buffer[i+1] == 0x4b && buffer[i+2] == 0x05 && buffer[i+3] == 0x06) {
            eocdPos = fileSize - maxSearchSize + i;
            break;
        }
        if (i == 0) break;
    }
    if (eocdPos == 0) return false;

    file.seekg(eocdPos + 10);
    uint16_t numEntries;
    file.read((char*)&numEntries, 2);
    
    file.seekg(eocdPos + 16);
    uint32_t cdOffset;
    file.read((char*)&cdOffset, 4);

    file.seekg(cdOffset);
    for (uint16_t i = 0; i < numEntries; ++i) {
        uint32_t sig;
        file.read((char*)&sig, 4);
        if (sig != 0x02014b50) break;

        file.seekg((size_t)file.tellg() + 24);
        uint16_t nameLen, extraLen, commentLen;
        file.read((char*)&nameLen, 2);
        file.read((char*)&extraLen, 2);
        file.read((char*)&commentLen, 2);
        file.seekg((size_t)file.tellg() + 12);

        std::string filename(nameLen, '\0');
        file.read(&filename[0], nameLen);
        file.seekg((size_t)file.tellg() + extraLen + commentLen);

        if (filename.rfind("META-INF/", 0) == 0) {
            if (filename.size() > 3 && (
                filename.substr(filename.size() - 3) == ".SF" ||
                filename.substr(filename.size() - 4) == ".RSA" ||
                filename.substr(filename.size() - 4) == ".DSA" ||
                filename.substr(filename.size() - 3) == ".EC")) {
                return true;
            }
        }
    }
    return false;
}

static std::string parseApkSigningBlock(const std::string& apkPath) {
    std::ostringstream ss;
    std::ifstream file(apkPath, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        return "Error: Cannot open APK file at " + apkPath;
    }

    size_t fileSize = file.tellg();
    if (fileSize < 22) return "Error: File size too small";

    size_t maxSearchSize = std::min(fileSize, (size_t)65557);
    std::vector<uint8_t> buffer(maxSearchSize);
    file.seekg(fileSize - maxSearchSize);
    file.read((char*)buffer.data(), maxSearchSize);

    size_t eocdPos = 0;
    for (size_t i = maxSearchSize - 22; ; i--) {
        if (buffer[i] == 0x50 && buffer[i+1] == 0x4b && buffer[i+2] == 0x05 && buffer[i+3] == 0x06) {
            eocdPos = fileSize - maxSearchSize + i;
            break;
        }
        if (i == 0) break;
    }

    if (eocdPos == 0) return "Error: EOCD not found";

    file.seekg(eocdPos + 16);
    uint32_t cdOffset;
    file.read((char*)&cdOffset, 4);

    if (cdOffset < 24) return "Error: Central Directory offset invalid";
    file.seekg(cdOffset - 16);
    char magic[16];
    file.read(magic, 16);

    if (memcmp(magic, "APK Sig Block 42", 16) != 0) {
        return "Warning: APK does not contain APK Signing Block (V2/V3 scheme missing or V1 only)";
    }

    file.seekg(cdOffset - 24);
    uint64_t blockSizeInFooter;
    file.read((char*)&blockSizeInFooter, 8);

    size_t blockStartPos = cdOffset - 8 - blockSizeInFooter;
    file.seekg(blockStartPos);
    
    std::vector<uint8_t> blockData(blockSizeInFooter - 16);
    file.read((char*)blockData.data(), blockData.size());

    BufferReader reader(blockData.data() + 8, blockData.size() - 8);
    while (reader.hasRemaining()) {
        uint64_t pairLen = reader.readU64();
        uint32_t id = reader.readU32();
        std::vector<uint8_t> value = reader.readBytes(pairLen - 4);

        switch (id) {
            case APK_V2_SIGNATURE_SCHEME_ID:
                parseSchemePayload(ss, "v2 Scheme", value, false);
                break;
            case APK_V3_SIGNATURE_SCHEME_ID:
                parseSchemePayload(ss, "v3.0 Scheme", value, true);
                break;
            case APK_V31_SIGNATURE_SCHEME_ID:
                parseSchemePayload(ss, "v3.1 Scheme", value, true);
                break;
            case APK_V32_SIGNATURE_SCHEME_ID:
                parseSchemePayload(ss, "v3.2 Scheme", value, true);
                break;
            case APK_SOURCE_STAMP_V1_ID:
                parseSourceStampV1Payload(ss, value);
                break;
            case APK_SOURCE_STAMP_V2_ID:
                parseSourceStampV2Payload(ss, value);
                break;
            case APK_BUILD_METADATA_ID:
                ss << "\n [ APK Build Metadata (0x42726577) ] \n";
                ss << "    * Description      : AGP / R8 Compiler Build Metadata (Brew)\n";
                ss << "    * Raw Payload Size : " << std::dec << value.size() << " bytes\n";
                if (!value.empty()) {
                    ss << "    * Full Hex Data    : " << bytesToFullHex(value.data(), value.size()) << "\n";
                }
                break;
            case APK_SDK_DEPENDENCY_INFO_ID:
                ss << "\n [ SDK Dependency Info (0x504b4453) ] \n";
                ss << "    * Description      : AGP / Google Play SDK Dependency Metadata (Encrypted)\n";
                ss << "    * Raw Payload Size : " << std::dec << value.size() << " bytes\n";
                break;
            default: {
                std::stringstream idSs;
                idSs << "0x" << std::hex << id;
                ss << "\n [ Block ID " << idSs.str() << " ] \n";
                ss << "    * Raw Payload Size : " << std::dec << value.size() << " bytes\n";
                if (!value.empty()) {
                    size_t previewLen = std::min(value.size(), (size_t)32);
                    ss << "    * Data Preview     : " << bytesToFullHex(value.data(), previewLen);
                    if (value.size() > 32) ss << "...";
                    ss << "\n";
                }
                break;
            }
        }
    }
    return ss.str();
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_ApkSignature(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) {
        return env->NewStringUTF("Error: apkPath is null");
    }

    const char* c_apk_path = env->GetStringUTFChars(apk_path, nullptr);
    std::string result = parseApkSigningBlock(std::string(c_apk_path));
    env->ReleaseStringUTFChars(apk_path, c_apk_path);

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_hasV1Scheme(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(apk_path, nullptr);
    bool res = checkV1Present(std::string(path));
    env->ReleaseStringUTFChars(apk_path, path);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_hasV2Scheme(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(apk_path, nullptr);
    bool res = checkBlockIdPresent(std::string(path), APK_V2_SIGNATURE_SCHEME_ID);
    env->ReleaseStringUTFChars(apk_path, path);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_hasV3Scheme(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(apk_path, nullptr);
    bool res = checkBlockIdPresent(std::string(path), APK_V3_SIGNATURE_SCHEME_ID);
    env->ReleaseStringUTFChars(apk_path, path);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_hasV31Scheme(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(apk_path, nullptr);
    bool res = checkBlockIdPresent(std::string(path), APK_V31_SIGNATURE_SCHEME_ID);
    env->ReleaseStringUTFChars(apk_path, path);
    return res ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_hasV32Scheme(JNIEnv *env, jobject thiz, jstring apk_path) {
    if (!apk_path) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(apk_path, nullptr);
    bool res = checkBlockIdPresent(std::string(path), APK_V32_SIGNATURE_SCHEME_ID);
    env->ReleaseStringUTFChars(apk_path, path);
    return res ? JNI_TRUE : JNI_FALSE;
}

}
