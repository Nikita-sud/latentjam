/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * linear_resampler.cpp is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
struct owned by the native heap. The Kotlin side holds
// an opaque `long` handle and is responsible for calling nativeDestroy() in
// onCleanup() — we don't use phantom references / Cleaner here to keep the
// JNI surface trivial and avoid the JVM threading overhead.
#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>

namespace {

    struct ResamplerState {
        double ratio;        // src_sr / dst_sr; advance per output sample
        double src_cursor;// current position in global source stream
        int64_t chunk_base;// index of the first sample of the current chunk in the global stream
        float prev_sample;// last sample of the previous chunk (for cross-chunk interpolation)
        bool has_prev;
    };

// Pack (consumed, produced) into a single jlong: high 32 bits = consumed, low 32 = produced.
    static inline jlong pack_result(int32_t consumed, int32_t produced) {
        return (static_cast<int64_t>(consumed) << 32)
        | static_cast<uint32_t>(produced);
    }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeLinearResampler_nativeCreate(
        JNIEnv* /*env*/, jclass /*clazz*/, jint fromSr, jint toSr) {
    if (fromSr <= 0 || toSr <= 0) return 0;
    auto* s = new (std::nothrow) ResamplerState();
    if (!s) return 0;
    s->ratio = static_cast<double>(fromSr) / static_cast<double>(toSr);
    s->src_cursor = 0.0;
    s->chunk_base = 0;
    s->prev_sample = 0.0f;
    s->has_prev = false;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeLinearResampler_nativeDestroy(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* s = reinterpret_cast<ResamplerState*>(handle);
    delete s;
}

// Returns: high 32 bits = bytes_consumed (always == srcLen here), low 32 = produced.
// `src` and `dst` are pinned via GetFloatArrayElements / GetPrimitiveArrayCritical;
// we use GetFloatArrayRegion + SetFloatArrayRegion which copy small windows and let the GC
// move the underlying buffer freely. This is the safest pattern for short-lived JNI ops
// and avoids potential mode=0 deadlocks on some Android runtimes.
JNIEXPORT jlong JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeLinearResampler_nativeFeed(
        JNIEnv* env, jclass /*clazz*/,
        jlong handle,
        jfloatArray srcArr, jint srcOff, jint srcLen,
        jfloatArray dstArr, jint dstOff, jint dstCap) {
    auto* s = reinterpret_cast<ResamplerState*>(handle);
    if (!s) return pack_result(0, 0);
    if (srcLen <= 0) return pack_result(0, 0);

    // Stack-allocate a small chunk if it fits; else heap. ~32 KB stack budget per
    // JNI call which is well below Android's 1 MB native stack.
    constexpr int STACK_BUDGET = 8192;
    float stack_src[STACK_BUDGET];
    float stack_dst[STACK_BUDGET];

    float* src = (srcLen <= STACK_BUDGET) ? stack_src : new float[srcLen];
    float* dst = (dstCap <= STACK_BUDGET) ? stack_dst : new float[dstCap];

    env->GetFloatArrayRegion(srcArr, srcOff, srcLen, src);

    int produced = 0;
    const double ratio = s->ratio;
    double cursor = s->src_cursor;
    const double chunk_base_d = static_cast<double>(s->chunk_base);
    const float prev = s->prev_sample;

    while (produced < dstCap) {
        const double local_f = cursor - chunk_base_d;
        // We can straddle the previous chunk by at most one sample (using
        // prev_sample as s0). If local_f < -1, the caller hasn't yet given us
        // enough new data — bail and let them re-feed. Without this guard we
        // would extrapolate with frac far outside [0, 1], producing values
        // tens of thousands of samples out of range.
        if (local_f < -1.0) break;
        const int i = (local_f >= 0.0) ? static_cast<int>(local_f) : -1;
        if (i + 1 >= srcLen) break;
        const float frac = static_cast<float>(local_f - static_cast<double>(i));
        const float s0 = (i < 0) ? prev : src[i];
        const float s1 = src[i + 1];
        dst[produced++] = s0 * (1.0f - frac) + s1 * frac;
        cursor += ratio;
    }

    env->SetFloatArrayRegion(dstArr, dstOff, produced, dst);

    // Compute "crossed": how many input samples we actually moved past in this
    // call. We keep one previous sample for cross-chunk interpolation, so the
    // caller can discard `crossed` samples from its source buffer.
    int64_t crossed_l = static_cast<int64_t>(std::floor(cursor)) - s->chunk_base;
    if (crossed_l < 0) crossed_l = 0;
    if (crossed_l > static_cast<int64_t>(srcLen)) crossed_l = srcLen;
    const int32_t crossed = static_cast<int32_t>(crossed_l);
    if (crossed > 0) {
        s->prev_sample = src[crossed - 1];
        s->has_prev = true;
    }
    s->chunk_base += crossed_l;
    s->src_cursor = cursor;

    if (src != stack_src) delete[] src;
    if (dst != stack_dst) delete[] dst;

    return pack_result(crossed, produced);
}

JNIEXPORT jint JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeLinearResampler_nativeFlush(
        JNIEnv* env, jclass /*clazz*/,
        jlong handle, jfloatArray dstArr, jint dstCap) {
    auto* s = reinterpret_cast<ResamplerState*>(handle);
    if (!s || !s->has_prev) return 0;

    constexpr int STACK_BUDGET = 8192;
    float stack_dst[STACK_BUDGET];
    float* dst = (dstCap <= STACK_BUDGET) ? stack_dst : new float[dstCap];

    int produced = 0;
    const double ratio = s->ratio;
    double cursor = s->src_cursor;
    const double chunk_base_d = static_cast<double>(s->chunk_base);
    const float prev = s->prev_sample;

    // With linear interp the only output we can still produce is one at the integer
    // position -1 (== prev_sample). After that we'd need future samples we don't have.
    while (produced < dstCap) {
        const double local_f = cursor - chunk_base_d;
        if (local_f >= 0.0) break;
        dst[produced++] = prev;
        cursor += ratio;
    }
    s->src_cursor = cursor;

    if (produced > 0) {
        env->SetFloatArrayRegion(dstArr, 0, produced, dst);
    }
    if (dst != stack_dst) delete[] dst;
    return static_cast<jint>(produced);
}

}
    // extern "C"
