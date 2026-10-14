/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#include "lj_loudness.h"
#include <jni.h>
#include <cstdint>
#define JNI(name) Java_io_github_nikitasud_latentjam_playback_NativeLoudnessJni_##name
extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *, jobject, jint rate, jint channels) {
    return reinterpret_cast<intptr_t>(lj_loudness_create(rate, channels));
}
extern "C" JNIEXPORT void JNICALL JNI(destroy)(JNIEnv *, jobject, jlong handle) {
    lj_loudness_destroy(reinterpret_cast<lj_loudness *>(handle));
}
extern "C" JNIEXPORT jboolean JNICALL JNI(add)(JNIEnv *env, jobject, jlong handle,
        jfloatArray input, jint frames, jint channels) {
    if (!handle || !input || channels < 1 || channels > 2 || frames < 1 || frames > 4096 ||
        env->GetArrayLength(input) < frames * channels) return JNI_FALSE;
    // Small bounded copy; don't hold a critical JVM array across a library mutex/long calculation.
    float samples[4096 * 2];
    env->GetFloatArrayRegion(input, 0, frames * channels, samples);
    if (env->ExceptionCheck()) return JNI_FALSE;
    return lj_loudness_add(reinterpret_cast<lj_loudness *>(handle), samples, frames) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jdoubleArray JNICALL JNI(finish)(JNIEnv *env, jobject, jlong handle) {
    double result[2];
    if (!lj_loudness_finish(reinterpret_cast<lj_loudness *>(handle), result, result + 1)) return nullptr;
    auto output = env->NewDoubleArray(2);
    if (output) env->SetDoubleArrayRegion(output, 0, 2, result);
    return output;
}
