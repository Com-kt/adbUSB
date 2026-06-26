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

void CryptoXteaCtr(uint8_t* data, size_t size, const uint32_t key[4], uint64_t blockId);
void DeriveKey(const std::string& password, uint32_t key[4]);

struct DecompressFileEntry {
    std::string name;
    uint64_t streamOffset;
    uint64_t fileSize;
};

void EnsureParentDirs(const fs::path& filePath) {
    if (filePath.has_parent_path()) {
        fs::create_directories(filePath.parent_path());
    }
}

auto DecompressBlockWorker(int blockId, std::vector<uint8_t> compData, bool decrypt, const uint32_t key[4]) {
    struct Result { int bId; std::vector<uint8_t> data; bool success; };
    
    if (decrypt) {
        CryptoXteaCtr(compData.data(), compData.size(), key, static_cast<uint64_t>(blockId));
    }

    std::vector<uint8_t> decompData(4 * 1024 * 1024);

    z_stream stream{};
    if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) return Result{blockId, {}, false};

    stream.next_in = compData.data();
    stream.avail_in = compData.size();
    stream.next_out = decompData.data();
    stream.avail_out = decompData.size();

    int ret = inflate(&stream, Z_FINISH);
    decompData.resize(decompData.size() - stream.avail_out);
    inflateEnd(&stream);

    return Result{blockId, std::move(decompData), (ret == Z_STREAM_END || ret == Z_OK)};
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_decompressKBA(
        JNIEnv *env, jobject thiz, jstring kba_path, jstring output_dir, jstring password) {

    const char* c_kba_path = env->GetStringUTFChars(kba_path, nullptr);
    const char* c_out_dir = env->GetStringUTFChars(output_dir, nullptr);

    if (std::ifstream inFile(c_kba_path, std::ios::binary); !inFile.is_open()) {
        env->ReleaseStringUTFChars(kba_path, c_kba_path);
        env->ReleaseStringUTFChars(output_dir, c_out_dir);
        return JNI_FALSE;
    } else {
        uint32_t magic = 0, flags = 0;
        uint64_t indexOffset = 0;
        inFile.read(reinterpret_cast<char*>(&magic), 4);
        inFile.read(reinterpret_cast<char*>(&flags), 4);
        inFile.read(reinterpret_cast<char*>(&indexOffset), 8);

        if (magic != 0x3241424B) return JNI_FALSE;

        bool isEncrypted = (flags == 1);
        uint32_t cryptoKey[4] = {0};
        if (isEncrypted && password != nullptr) {
            const char* c_pass = env->GetStringUTFChars(password, nullptr);
            DeriveKey(c_pass, cryptoKey);
            env->ReleaseStringUTFChars(password, c_pass);
        }

        inFile.seekg(indexOffset, std::ios::beg);
        std::vector<uint8_t> indexBytes((std::istreambuf_iterator<char>(inFile)), std::istreambuf_iterator<char>());
        if (isEncrypted) {
            CryptoXteaCtr(indexBytes.data(), indexBytes.size(), cryptoKey, 0xFFFFFFFFFFFFFFFFULL);
        }

        size_t indexPtr = 0;
        uint32_t totalFiles = *reinterpret_cast<uint32_t*>(&indexBytes[indexPtr]);
        indexPtr += 4;

        std::vector<DecompressFileEntry> registry;
        for (uint32_t i = 0; i < totalFiles; ++i) {
            uint16_t nameLen = *reinterpret_cast<uint16_t*>(&indexBytes[indexPtr]); indexPtr += 2;
            std::string name(reinterpret_cast<char*>(&indexBytes[indexPtr]), nameLen); indexPtr += nameLen;
            uint64_t streamOffset = *reinterpret_cast<uint64_t*>(&indexBytes[indexPtr]); indexPtr += 8;
            uint64_t fileSize = *reinterpret_cast<uint64_t*>(&indexBytes[indexPtr]); indexPtr += 8;
            registry.push_back({name, streamOffset, fileSize});
        }

        inFile.seekg(16, std::ios::beg);

        std::map<int, std::vector<uint8_t>> unwrapQueue;
        int nextBlockToUnwrap = 0, submittedBlockCount = 0;
        std::mutex queueMutex;
        std::condition_variable queueCv;
        bool readAllBlocks = false;

        auto fileWriter = std::thread([&]() {
            size_t currentFileIdx = 0;
            uint64_t fileBytesRemaining = registry.empty() ? 0 : registry[0].fileSize;
            std::ofstream outCurrentFile;

            while (currentFileIdx < registry.size() && fileBytesRemaining == 0) {
                fs::path fullPath = fs::path(c_out_dir) / registry[currentFileIdx].name;
                EnsureParentDirs(fullPath);
                std::ofstream(fullPath, std::ios::binary);
                if (++currentFileIdx < registry.size()) fileBytesRemaining = registry[currentFileIdx].fileSize;
            }

            while (true) {
                std::unique_lock<std::mutex> lock(queueMutex);
                queueCv.wait(lock, [&] {
                    return unwrapQueue.count(nextBlockToUnwrap) > 0 || (readAllBlocks && nextBlockToUnwrap == submittedBlockCount);
                });

                if (readAllBlocks && nextBlockToUnwrap == submittedBlockCount && unwrapQueue.empty()) {
                    if (outCurrentFile.is_open()) outCurrentFile.close();
                    break;
                }

                if (auto node = unwrapQueue.extract(nextBlockToUnwrap)) {
                    std::vector<uint8_t> blockData = std::move(node.mapped());
                    lock.unlock();
                    queueCv.notify_all();

                    size_t bytesConsumed = 0;
                    size_t decompSize = blockData.size();

                    while (bytesConsumed < decompSize && currentFileIdx < registry.size()) {
                        if (fileBytesRemaining == 0) {
                            if (outCurrentFile.is_open()) outCurrentFile.close();
                            if (++currentFileIdx >= registry.size()) break;
                            fileBytesRemaining = registry[currentFileIdx].fileSize;
                        }

                        if (fileBytesRemaining == 0) {
                            fs::path fullPath = fs::path(c_out_dir) / registry[currentFileIdx].name;
                            EnsureParentDirs(fullPath);
                            std::ofstream(fullPath, std::ios::binary);
                            continue;
                        }

                        size_t toWrite = std::min(static_cast<size_t>(fileBytesRemaining), decompSize - bytesConsumed);
                        if (!outCurrentFile.is_open()) {
                            fs::path fullPath = fs::path(c_out_dir) / registry[currentFileIdx].name;
                            EnsureParentDirs(fullPath);
                            outCurrentFile.open(fullPath, std::ios::binary);
                        }

                        if (outCurrentFile.is_open() && toWrite > 0) {
                            outCurrentFile.write(reinterpret_cast<const char*>(blockData.data() + bytesConsumed), toWrite);
                        }

                        fileBytesRemaining -= toWrite;
                        bytesConsumed += toWrite;
                    }
                    nextBlockToUnwrap++;
                }
            }
        });

        while (static_cast<uint64_t>(inFile.tellg()) < indexOffset) {
            uint32_t compSize = 0;
            inFile.read(reinterpret_cast<char*>(&compSize), 4);
            if (compSize == 0 || inFile.gcount() < 4) break;

            std::vector<uint8_t> compBuffer(compSize);
            inFile.read(reinterpret_cast<char*>(compBuffer.data()), compSize);

            int bId = submittedBlockCount++;

            std::unique_lock<std::mutex> lock(queueMutex);
            if (bId - nextBlockToUnwrap > std::thread::hardware_concurrency() * 2) {
                queueCv.wait(lock, [&] { return bId - nextBlockToUnwrap <= std::thread::hardware_concurrency() * 2; });
            }
            lock.unlock();

            std::async(std::launch::async, [bId, compBuffer = std::move(compBuffer), isEncrypted, cryptoKey, &unwrapQueue, &queueMutex, &queueCv]() {
                auto [id, data, success] = DecompressBlockWorker(bId, std::move(compBuffer), isEncrypted, cryptoKey);
                if (success) {
                    std::scoped_lock lock(queueMutex);
                    unwrapQueue[id] = std::move(data);
                }
                queueCv.notify_all();
            });
        }

        {
            std::scoped_lock lock(queueMutex);
            readAllBlocks = true;
        }
        queueCv.notify_all();
        if (fileWriter.joinable()) fileWriter.join();

        inFile.close();
        env->ReleaseStringUTFChars(kba_path, c_kba_path);
        env->ReleaseStringUTFChars(output_dir, c_out_dir);
        return JNI_TRUE;
    }
}
