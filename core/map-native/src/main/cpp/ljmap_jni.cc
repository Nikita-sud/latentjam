// SPDX-License-Identifier: Apache-2.0
#include "ljmap.h"
#include <jni.h>
#include <vector>
namespace {
struct Cancellation { JNIEnv* env; jobject callback; jmethodID method; };
int active(void* opaque) {
    auto* c = static_cast<Cancellation*>(opaque);
    if (c->env->ExceptionCheck()) return 0;
    const bool value = c->env->CallBooleanMethod(c->callback, c->method);
    return !c->env->ExceptionCheck() && value;
}
}
extern "C" JNIEXPORT jint JNICALL
Java_io_github_nikitasud_latentjam_smart_cluster_NativeMapBridge_embed(
    JNIEnv* env, jobject, jfloatArray input, jint n, jint dim, jfloatArray initial,
    jfloatArray output, jobject callback) {
    if (!input || !output || !callback || n < 61 || n > 3000 || dim < 1 || dim > 50) return 2;
    if (env->GetArrayLength(input) != n * dim || env->GetArrayLength(output) != n * 2 ||
        (initial && env->GetArrayLength(initial) != n * 2)) return 2;
    auto clazz = env->GetObjectClass(callback);
    auto method = env->GetMethodID(clazz, "isActive", "()Z");
    env->DeleteLocalRef(clazz);
    if (!method || env->ExceptionCheck()) return 3;
    try {
        std::vector<float> rows(n * dim), y(n * 2), warm(initial ? n * 2 : 0);
        env->GetFloatArrayRegion(input, 0, n * dim, rows.data());
        if (initial) env->GetFloatArrayRegion(initial, 0, n * 2, warm.data());
        if (env->ExceptionCheck()) return 3;
        Cancellation c{env, callback, method};
        int result = LjMapEmbed(rows.data(), n, dim, initial ? warm.data() : nullptr, y.data(), active, &c);
        if (env->ExceptionCheck()) return 3; // Preserve the pending Java exception.
        if (result == 0) env->SetFloatArrayRegion(output, 0, n * 2, y.data());
        return result;
    } catch (...) { return 3; }
}

