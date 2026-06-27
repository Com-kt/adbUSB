#pragma once
#include <cstdint>
#include <string_view>

void XteaEncryptBlock(uint32_t v[2], const uint32_t k[4]);

void CryptoXteaCtr(uint8_t* data, size_t size, const uint32_t key[4], uint64_t blockId);

void DeriveKey(std::string_view password, uint32_t key[4]);
