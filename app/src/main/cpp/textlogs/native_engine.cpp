#include <jni.h>
#include <string>
#include <string_view>
#include <vector>
#include <mutex>
#include <atomic>
#include <memory>
#include <cstring>
#include <sys/mman.h>
#include <malloc.h>

extern "C" int malloc_trim(size_t pad);

class NativeLogEngine {
private:
    std::mutex engine_mutex;
    std::vector<char> off_heap_buffer;
    std::atomic<size_t> write_head{0};
    size_t capacity;

public:
    explicit NativeLogEngine(size_t cap) : capacity(cap) {
        off_heap_buffer.resize(cap, 0);
    }

    ~NativeLogEngine() {
        off_heap_buffer.clear();
        off_heap_buffer.shrink_to_fit();
        malloc_trim(0);
    }

    void append_fast(std::string_view raw_log) {
        size_t len = raw_log.size();
        if (len == 0 || len > capacity) return;

        std::scoped_lock lock(engine_mutex);

        size_t current_pos = write_head.load(std::memory_order_relaxed);
        if (current_pos + len > capacity) {
            current_pos = 0;
            write_head.store(0, std::memory_order_relaxed);
        }

        std::memcpy(off_heap_buffer.data() + current_pos, raw_log.data(), len);
        write_head.fetch_add(len, std::memory_order_release);
    }

    void* get_raw_buffer_ptr() {
        return off_heap_buffer.data();
    }

    size_t get_capacity() const {
        return capacity;
    }

    size_t get_write_offset() const {
        return write_head.load(std::memory_order_acquire);
    }

    void clear() {
        std::scoped_lock lock(engine_mutex);
        write_head.store(0, std::memory_order_release);

        if (!off_heap_buffer.empty()) {
            madvise(off_heap_buffer.data(), capacity, MADV_DONTNEED);
        }
    }
};

static std::unique_ptr<NativeLogEngine> g_log_engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_initNativeEngine(JNIEnv* env, jobject thiz, jint capacity) {
    g_log_engine = std::make_unique<NativeLogEngine>(static_cast<size_t>(capacity));
}

JNIEXPORT jobject JNICALL
Java_com_adb_kitty_data_NativeLibs_getDirectBuffer(JNIEnv* env, jobject thiz) {
    if (!g_log_engine) return nullptr;
    return env->NewDirectByteBuffer(g_log_engine->get_raw_buffer_ptr(), g_log_engine->get_capacity());
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_appendNativeLog(JNIEnv* env, jobject thiz, jstring log_str) {
    if (!g_log_engine || !log_str) return;

    const char* chars = env->GetStringUTFChars(log_str, nullptr);
    jsize len = env->GetStringUTFLength(log_str);

    if (chars) {
        g_log_engine->append_fast(std::string_view(chars, static_cast<size_t>(len)));
        env->ReleaseStringUTFChars(log_str, chars);
    }
}

JNIEXPORT jlong JNICALL
Java_com_adb_kitty_data_NativeLibs_getWriteOffset(JNIEnv* env, jobject thiz) {
    return g_log_engine ? static_cast<jlong>(g_log_engine->get_write_offset()) : 0L;
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_clearNativeBuffer(JNIEnv* env, jobject thiz) {
    if (g_log_engine) {
        g_log_engine->clear();
    }
}

JNIEXPORT void JNICALL
Java_com_adb_kitty_data_NativeLibs_releaseNativeEngine(JNIEnv* env, jobject thiz) {
    if (g_log_engine) {
        g_log_engine.reset();
        malloc_trim(0);
    }
}

} // extern "C"
