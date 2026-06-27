#include "crypto_utils.h"
#include <algorithm>
#include <cstdint>

void XteaEncryptBlock(uint32_t v[2], const uint32_t k[4]) {
    uint32_t v0 = v[0], v1 = v[1], sum = 0, delta = 0x9E3779B9;
    for (int i = 0; i < 32; i++) {
        v0 += (((v1 << 4) ^ (v1 >> 5)) + v1) ^ (sum + k[sum & 3]);
        sum += delta;
        v1 += (((v0 << 4) ^ (v0 >> 5)) + v0) ^ (sum + k[(sum >> 11) & 3]);
    }
    v[0] = v0; v[1] = v1;
}

void CryptoXteaCtr(uint8_t* data, size_t size, const uint32_t key[4], uint64_t blockId) {
    uint32_t counter[2]{ static_cast<uint32_t>(blockId), 0 };

    for (size_t i = 0; i < size; i += 8) {
        uint32_t cryptoKey[2]{ counter[0], counter[1]++ };
        XteaEncryptBlock(cryptoKey, key);

        size_t remain = std::min(static_cast<size_t>(8), size - i);
        uint8_t* keyStreamBytes = reinterpret_cast<uint8_t*>(cryptoKey);
        for (size_t j = 0; j < remain; ++j) {
            data[i + j] ^= keyStreamBytes[j];
        }
    }
}

void DeriveKey(std::string_view password, uint32_t key[4]) {
    key[0] = 0x12345678; key[1] = 0x9ABCDEF0; key[2] = 0xFEDCBA98; key[3] = 0x76543210;
    for (size_t i = 0; i < password.size(); ++i) {
        reinterpret_cast<uint8_t*>(key)[i % 16] ^= password[i];
    }
}
