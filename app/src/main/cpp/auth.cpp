#include <sys/stat.h>   
#include <openssl/evp.h>
#include <openssl/objects.h>
#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <openssl/sha.h>

#include <fstream> 

#include "adb_auth.h"
#include "log.h"

 std::string adb_auth_sign(RSA* key, const char* token, size_t token_size) {
    if (token_size != TOKEN_SIZE) {
        LOGW("Unexpected token size= %zu\n", token_size);
        return std::string();
    }

    std::string result;
    result.resize(MAX_PAYLOAD);

    unsigned int len;
    if (!RSA_sign(NID_sha1, reinterpret_cast<const uint8_t*>(token), token_size,
                  reinterpret_cast<uint8_t*>(&result[0]), &len, key)) {
        return std::string();
    }

    result.resize(len);

    LOGV("adb_auth_sign len=%u\n", len);
    return result;
}
