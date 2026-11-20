// Debug-only JNI over the pinned evaluation engine. Models remain outside the APK.
#include <jni.h>
#include <cstdint>
#include <memory>
#include <new>

extern "C" void* friday_wake_create(const uint8_t*, size_t, float, int, int);
extern "C" int friday_wake_process(void*, const int16_t*, size_t);
extern "C" void friday_wake_destroy(void*);

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_assistant_debug_wake_MicroWakeWordNative_create(
        JNIEnv* env, jobject, jbyteArray model, jfloat threshold) {
    if (!model || env->GetArrayLength(model) != 60832) return 0;
    const auto size = env->GetArrayLength(model);
    auto bytes = std::unique_ptr<uint8_t[]>(new (std::nothrow) uint8_t[size]);
    if (!bytes) return 0;
    env->GetByteArrayRegion(model, 0, size, reinterpret_cast<jbyte*>(bytes.get()));
    if (env->ExceptionCheck()) return 0;
    return reinterpret_cast<jlong>(friday_wake_create(bytes.get(), size, threshold, 10, 10));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_assistant_debug_wake_MicroWakeWordNative_process(
        JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count) {
    if (!handle || !samples || count < 1 || count > 480 || count > env->GetArrayLength(samples)) return -1;
    int16_t frame[480];
    env->GetShortArrayRegion(samples, 0, count, frame);
    if (env->ExceptionCheck()) return -1;
    return friday_wake_process(reinterpret_cast<void*>(handle), frame, count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_assistant_debug_wake_MicroWakeWordNative_destroy(JNIEnv*, jobject, jlong handle) {
    if (handle) friday_wake_destroy(reinterpret_cast<void*>(handle));
}
