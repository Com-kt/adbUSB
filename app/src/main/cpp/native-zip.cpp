#include <jni.h>
#include <vector>
#include <string>
#include <string_view>
#include <thread>
#include <future>
#include <map>
#include <mutex>
#include <condition_variable>
#include <fstream>
#include <cstring>
#include <cstdint>
#include <zlib.h>
#include <filesystem>

namespace fs = std::filesystem;

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

struct VirtualFileEntry {
    std::string name;
    uint64_t streamOffset;
    uint64_t fileSize;
};

struct CompressedBlock {
    int blockId;
    std::vector<uint8_t> data;
};

auto CompressAndEncryptWorker(int blockId, const uint8_t* rawData, size_t size, int level, bool encrypt, const uint32_t key[4]) {
    CompressedBlock block{ blockId, {} };

    z_stream stream{};
    if (deflateInit2(&stream, level, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        return block;
    }

    stream.next_in = const_cast<uint8_t*>(rawData);
    stream.avail_in = size;

    size_t bound = deflateBound(&stream, size) + 16;
    block.data.resize(bound);
    stream.next_out = block.data.data();
    stream.avail_out = bound;

    deflate(&stream, Z_FINISH);
    block.data.resize(bound - stream.avail_out);
    deflateEnd(&stream);

    if (encrypt) {
        CryptoXteaCtr(block.data.data(), block.data.size(), key, static_cast<uint64_t>(blockId));
    }

    return block;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_compressToKBA(
        JNIEnv *env, jobject thiz, jobjectArray file_paths, jobjectArray entry_names, jstring output_kba_path, jint level, jstring password) {

    int fileCount = env->GetArrayLength(file_paths);
    if (fileCount == 0) return JNI_FALSE;

    bool useEncryption = false;
    uint32_t cryptoKey[4] = {0};
    if (password != nullptr) {
        const char* c_pass = env->GetStringUTFChars(password, nullptr);
        if (std::string_view passStr(c_pass); !passStr.empty()) {
            useEncryption = true;
            DeriveKey(passStr, cryptoKey);
        }
        env->ReleaseStringUTFChars(password, c_pass);
    }

    const char* c_out_path = env->GetStringUTFChars(output_kba_path, nullptr);
    
    if (std::ofstream outFile(c_out_path, std::ios::binary); !outFile.is_open()) {
        env->ReleaseStringUTFChars(output_kba_path, c_out_path);
        return JNI_FALSE;
    } else {
        uint32_t magic = 0x3241424B;
        uint32_t flags = useEncryption ? 1 : 0;
        uint64_t indexOffsetPlaceholder = 0;
        outFile.write(reinterpret_cast<const char*>(&magic), 4);
        outFile.write(reinterpret_cast<const char*>(&flags), 4);
        outFile.write(reinterpret_cast<const char*>(&indexOffsetPlaceholder), 8);

        std::vector<VirtualFileEntry> registry;
        uint64_t currentGlobalStreamOffset = 0;
        
        std::vector<std::future<void>> compressFutures;

        const size_t CHUNK_SIZE = 4 * 1024 * 1024;
        std::vector<uint8_t> chunkBuffer;
        chunkBuffer.reserve(CHUNK_SIZE);

        int blockIdCounter = 0;
        std::map<int, std::vector<uint8_t>> writeQueue;
        int nextBlockToWrite = 0;
        std::mutex queueMutex;
        std::condition_variable queueCv;

        auto diskWriter = std::thread([&]() {
            while (true) {
                std::unique_lock<std::mutex> lock(queueMutex);
                queueCv.wait(lock, [&] { 
                    return writeQueue.count(nextBlockToWrite) > 0 || nextBlockToWrite == blockIdCounter; 
                });

                if (nextBlockToWrite == blockIdCounter && writeQueue.empty()) break;

                if (auto node = writeQueue.extract(nextBlockToWrite)) {
                    lock.unlock();
                    queueCv.notify_all();

                    uint32_t compSize = static_cast<uint32_t>(node.mapped().size());
                    outFile.write(reinterpret_cast<const char*>(&compSize), 4);
                    outFile.write(reinterpret_cast<const char*>(node.mapped().data()), compSize);
                    
                    nextBlockToWrite++;
                }
            }
        });

        for (int i = 0; i < fileCount; ++i) {
            jstring j_path = (jstring)env->GetObjectArrayElement(file_paths, i);
            jstring j_name = (jstring)env->GetObjectArrayElement(entry_names, i);
            
            const char* c_path = env->GetStringUTFChars(j_path, nullptr);
            const char* c_name = env->GetStringUTFChars(j_name, nullptr);

            if (fs::path filePath(c_path); fs::exists(filePath) && !fs::is_directory(filePath)) {
                uint64_t size = fs::file_size(filePath);

                registry.push_back({c_name, currentGlobalStreamOffset, size});
                currentGlobalStreamOffset += size;

                if (std::ifstream inFile(filePath, std::ios::binary); inFile.is_open()) {
                    size_t bytesProcessed = 0;
                    while (bytesProcessed < size) {
                        size_t remainInChunk = CHUNK_SIZE - chunkBuffer.size();
                        size_t toRead = std::min(remainInChunk, static_cast<size_t>(size - bytesProcessed));
                        
                        size_t oldSize = chunkBuffer.size();
                        chunkBuffer.resize(oldSize + toRead);
                        inFile.read(reinterpret_cast<char*>(chunkBuffer.data() + oldSize), toRead);
                        bytesProcessed += toRead;
                        
                        if (chunkBuffer.size() == CHUNK_SIZE) {
                            int bId = blockIdCounter++;
                            
                            if (bId - nextBlockToWrite > std::thread::hardware_concurrency() * 2) {
                                std::unique_lock<std::mutex> lock(queueMutex);
                                queueCv.wait(lock, [&] { return bId - nextBlockToWrite <= std::thread::hardware_concurrency() * 2; });
                            }

                            compressFutures.push_back(std::async(std::launch::async, [bId, chunkBuffer, level, useEncryption, cryptoKey, &writeQueue, &queueMutex, &queueCv]() {
                                auto cb = CompressAndEncryptWorker(bId, chunkBuffer.data(), chunkBuffer.size(), level, useEncryption, cryptoKey);
                                {
                                    std::scoped_lock lock(queueMutex);
                                    writeQueue[cb.blockId] = std::move(cb.data);
                                }
                                queueCv.notify_all();
                            }));

                            chunkBuffer.clear();
                        }
                    }
                    inFile.close();
                }
            }
            env->ReleaseStringUTFChars(j_path, c_path);
            env->ReleaseStringUTFChars(j_name, c_name);
        }

        if (!chunkBuffer.empty()) {
            int bId = blockIdCounter++;
            compressFutures.push_back(std::async(std::launch::async, [bId, chunkBuffer, level, useEncryption, cryptoKey, &writeQueue, &queueMutex, &queueCv]() {
                auto cb = CompressAndEncryptWorker(bId, chunkBuffer.data(), chunkBuffer.size(), level, useEncryption, cryptoKey);
                {
                    std::scoped_lock lock(queueMutex);
                    writeQueue[cb.blockId] = std::move(cb.data);
                }
                queueCv.notify_all();
            }));
        }

        if (diskWriter.joinable()) diskWriter.join();

        std::vector<uint8_t> indexBytes;
        uint32_t totalFiles = static_cast<uint32_t>(registry.size());
        
        auto appendToVector = [](std::vector<uint8_t>& dest, const void* src, size_t size) {
            const uint8_t* p = reinterpret_cast<const uint8_t*>(src);
            dest.insert(dest.end(), p, p + size);
        };

        appendToVector(indexBytes, &totalFiles, 4);
        for (const auto& entry : registry) {
            uint16_t nameLen = static_cast<uint16_t>(entry.name.size());
            appendToVector(indexBytes, &nameLen, 2);
            indexBytes.insert(indexBytes.end(), entry.name.begin(), entry.name.end());
            appendToVector(indexBytes, &entry.streamOffset, 8);
            appendToVector(indexBytes, &entry.fileSize, 8);
        }

        if (useEncryption) {
            CryptoXteaCtr(indexBytes.data(), indexBytes.size(), cryptoKey, 0xFFFFFFFFFFFFFFFFULL);
        }

        uint64_t indexOffset = outFile.tellp();
        outFile.write(reinterpret_cast<const char*>(indexBytes.data()), indexBytes.size());

        outFile.seekp(8, std::ios::beg);
        outFile.write(reinterpret_cast<const char*>(&indexOffset), 8);

        outFile.flush();
        outFile.close();
        env->ReleaseStringUTFChars(output_kba_path, c_out_path);
        return JNI_TRUE;
    }
}
