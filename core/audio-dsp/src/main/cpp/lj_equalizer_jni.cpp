/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#include "lj_equalizer.h"
#include <jni.h>
#include <cstdint>
#include <limits>
namespace {
lj_equalizer *core(jlong handle) { return reinterpret_cast<lj_equalizer *>(uintptr_t(handle)); }
void fail(JNIEnv *env, const char *message) { if (auto type = env->FindClass("java/lang/IllegalArgumentException")) env->ThrowNew(type, message); }
bool levels(JNIEnv *env, jintArray input, int32_t *out) {
    if (!input || env->GetArrayLength(input) != 10) { fail(env, "EQ requires ten band levels"); return false; }
    env->GetIntArrayRegion(input, 0, 10, out); return !env->ExceptionCheck();
}
void *buffer(JNIEnv *env, jobject data, jint offset, int64_t bytes) {
    if (!data || offset < 0 || bytes < 0 || env->GetDirectBufferCapacity(data) < int64_t(offset) + bytes) { fail(env, "EQ buffer bounds are invalid"); return nullptr; }
    void *base = env->GetDirectBufferAddress(data);
    if (!base) { fail(env, "EQ requires a direct PCM buffer"); return nullptr; }
    return static_cast<unsigned char *>(base) + offset;
}
}
#define JNI_METHOD(name) Java_io_github_nikitasud_latentjam_playback_NativeEqualizerJni_##name
extern "C" {
JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv *env, jobject, jint rate, jint channels) {
    auto *eq = lj_eq_create(rate, channels);
    if (!eq) fail(env, "Unsupported EQ format or allocation failure");
    return jlong(reinterpret_cast<uintptr_t>(eq));
}
JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv *, jobject, jlong h) { lj_eq_destroy(core(h)); }
JNIEXPORT void JNICALL JNI_METHOD(update)(JNIEnv *env, jobject, jlong h, jboolean enabled, jintArray input, jint bass) {
    int32_t data[10]; if (levels(env, input, data)) lj_eq_set_settings(core(h), enabled, data, bass);
}
JNIEXPORT void JNICALL JNI_METHOD(reset)(JNIEnv *, jobject, jlong h) { lj_eq_reset(core(h)); }
JNIEXPORT void JNICALL JNI_METHOD(fadeIn)(JNIEnv *, jobject, jlong h) { lj_eq_fade_in(core(h)); }
JNIEXPORT jint JNICALL JNI_METHOD(latency)(JNIEnv *, jobject, jlong h) { return lj_eq_latency_frames(core(h)); }
JNIEXPORT void JNICALL JNI_METHOD(processFloat)(JNIEnv *env, jobject, jlong h, jfloatArray input, jint frames, jint channels) {
    if (!h || !input || frames < 0 || channels != lj_eq_channel_count(core(h)) || int64_t(frames) * channels > env->GetArrayLength(input)) { fail(env, "EQ float buffer bounds are invalid"); return; }
    auto *data = static_cast<jfloat *>(env->GetPrimitiveArrayCritical(input, nullptr));
    if (!data) return;
    lj_eq_process_f32(core(h), data, frames);
    env->ReleasePrimitiveArrayCritical(input, data, 0);
}
JNIEXPORT jint JNICALL JNI_METHOD(processPcm16)(JNIEnv *env, jobject, jlong h, jobject input, jint inputOffset, jobject output, jint outputOffset, jint frames, jint channels) {
    if (!h || frames < 0 || channels != lj_eq_channel_count(core(h))) { fail(env, "Invalid EQ stream"); return 0; }
    const int64_t bytes = int64_t(frames) * channels * 2;
    void *in = buffer(env, input, inputOffset, bytes); if (!in) return 0;
    void *out = buffer(env, output, outputOffset, bytes); if (!out) return 0;
    return lj_eq_render_s16(core(h), in, out, frames);
}
JNIEXPORT jint JNICALL JNI_METHOD(pending)(JNIEnv *, jobject, jlong h) { return lj_eq_pending_frames(core(h)); }
JNIEXPORT jint JNICALL JNI_METHOD(drain)(JNIEnv *env, jobject, jlong h, jobject output, jint offset, jint capacity, jint channels) {
    if (!h || capacity < 0 || channels != lj_eq_channel_count(core(h))) { fail(env, "Invalid EQ drain"); return 0; }
    void *out = buffer(env, output, offset, int64_t(capacity) * channels * 2); if (!out) return 0;
    return lj_eq_drain_s16(core(h), out, capacity);
}
JNIEXPORT jfloat JNICALL JNI_METHOD(takeGain)(JNIEnv *, jobject, jlong h) { return lj_eq_take_meter_gain(core(h)); }
JNIEXPORT jfloat JNICALL JNI_METHOD(response)(JNIEnv *env, jobject, jint rate, jboolean enabled, jintArray input, jint bass, jfloatArray frequencies, jfloatArray output) {
    if (rate <= 0 || rate > 768000) { fail(env, "Unsupported EQ response sample rate"); return 0; }
    int32_t data[10]; if (!levels(env, input, data)) return 0;
    if (!frequencies || !output) { fail(env, "Missing EQ response buffers"); return 0; }
    const int count = env->GetArrayLength(frequencies);
    if (count > 512 || env->GetArrayLength(output) < count) { fail(env, "EQ response buffer bounds are invalid"); return 0; }
    float points[512] = {}, values[512] = {}; env->GetFloatArrayRegion(frequencies, 0, count, points);
    if (env->ExceptionCheck()) return 0;
    const float headroom = lj_eq_frequency_response(rate, enabled, data, bass, points, values, count);
    env->SetFloatArrayRegion(output, 0, count, values);
    return env->ExceptionCheck() ? 0 : headroom;
}
}
