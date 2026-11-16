#include "native_log.h"

#include <android/log.h>
#include <jni.h>

#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

extern JavaVM* g_jvm;
extern jclass g_debugLogClass;
extern jmethodID g_debugLogAddMethod;

namespace {

constexpr size_t kMaxQueued = 512;  // newest lines win; never grows unbounded

std::mutex g_mutex;
std::condition_variable g_cv;
std::deque<std::string> g_queue;
std::once_flag g_started;

// NewStringUTF aborts under CheckJNI on invalid UTF-8 (SF2 metadata is often
// Latin-1). Anything that is not valid UTF-8 becomes '?'.
std::string toValidUtf8(const char* in) {
    std::string out;
    const unsigned char* s = reinterpret_cast<const unsigned char*>(in);
    while (*s) {
        const unsigned char c = *s;
        size_t n = 0;
        if (c < 0x80) n = 1;
        else if (c >= 0xC2 && c <= 0xDF) n = 2;
        else if (c >= 0xE0 && c <= 0xEF) n = 3;
        else if (c >= 0xF0 && c <= 0xF4) n = 4;
        bool ok = n > 0;
        for (size_t j = 1; ok && j < n; ++j) {
            if (s[j] < 0x80 || s[j] > 0xBF) ok = false;  // also catches the terminating NUL
        }
        if (ok) {
            out.append(reinterpret_cast<const char*>(s), n);
            s += n;
        } else {
            out.push_back('?');
            ++s;
        }
    }
    return out;
}

void worker() {
    JNIEnv* env = nullptr;
    for (;;) {
        std::string msg;
        {
            std::unique_lock<std::mutex> lock(g_mutex);
            g_cv.wait(lock, [] { return !g_queue.empty(); });
            msg = std::move(g_queue.front());
            g_queue.pop_front();
        }
        if (!g_jvm || !g_debugLogClass || !g_debugLogAddMethod) continue;
        if (!env) {
            if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                // Attached once for the lifetime of this (never-exiting) thread.
                if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                    env = nullptr;
                    continue;
                }
            }
        }
        jstring jmsg = env->NewStringUTF(toValidUtf8(msg.c_str()).c_str());
        if (jmsg) {
            env->CallStaticVoidMethod(g_debugLogClass, g_debugLogAddMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

}  // namespace

void nativeLogcatOnly(const char* tag, const char* msg) {
    __android_log_print(ANDROID_LOG_DEBUG, tag, "%s", msg);
}

void nativeLogPost(const char* tag, const char* msg) {
    __android_log_print(ANDROID_LOG_INFO, tag, "%s", msg);
    if (!g_jvm || !g_debugLogClass || !g_debugLogAddMethod) return;

    std::call_once(g_started, [] { std::thread(worker).detach(); });
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_queue.size() >= kMaxQueued) g_queue.pop_front();
        g_queue.emplace_back(msg);
    }
    g_cv.notify_one();
}
