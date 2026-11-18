/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
// JNI over Apple's ALAC decoder (vendor/alac, Apache-2.0). One handle decodes one track's packets into
// interleaved little-endian PCM: 16-bit, packed 24-bit (20- and 24-bit sources) or 32-bit.
#include "vendor/alac/ALACDecoder.h"
#include "vendor/alac/ALACBitUtilities.h"
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <new>
#include <vector>

#define JNI(name) Java_io_github_nikitasud_latentjam_playback_NativeAlacJni_##name

namespace {
// Bounds for a sane stream: Apple's encoder writes 4,096-sample frames; the format allows at most 8 channels.
// The decoder's predictor (dp_dec.c, unpc_block) writes its warm-up samples, up to 31 of them by a count the packet
// gives, whatever the frame length, into buffers of one frame. A shorter frame would be a write past them; real
// encoders write 4096.
constexpr uint32_t kMinFrameLength = 32;
constexpr uint32_t kMaxFrameLength = 65536;
constexpr uint32_t kMaxChannels = 8;
constexpr uint32_t kMaxSampleRate = 768000;
// The decoder peeks at bytes 4..7 of the cookie twice before it checks the size; reading from a zeroed copy of
// at least this many bytes keeps those peeks inside the buffer whatever the container handed over.
constexpr size_t kCookieFloor = 64;

struct AlacHandle {
    ALACDecoder decoder;
    uint32_t bytesPerSample = 0;
    // Apple's bit reader checks its end only between elements; a packet is decoded from a copy with this much
    // zeroed slack behind it, so a damaged packet reads zeros instead of the heap before the check stops it.
    size_t slack = 0;
    std::vector<uint8_t> input;
};

// The frame length a cookie declares, read as ALACDecoder::Init finds it (after an optional 'frma' and 'alac'
// atom header, 12 bytes each), or 0 when the cookie is too short. [cookie] holds at least kCookieFloor bytes.
uint32_t declaredFrameLength(const uint8_t *cookie, size_t size) {
    size_t at = 0;
    if (!memcmp(cookie + 4, "frma", 4)) at += 12;
    if (!memcmp(cookie + at + 4, "alac", 4)) at += 12;
    if (size < at + sizeof(ALACSpecificConfig)) return 0;
    return (uint32_t(cookie[at]) << 24) | (uint32_t(cookie[at + 1]) << 16) | (uint32_t(cookie[at + 2]) << 8) | cookie[at + 3];
}

uint32_t bytesFor(uint32_t bitDepth) {
    switch (bitDepth) {
        case 16: return 2;
        case 20:
        case 24: return 3;
        case 32: return 4;
        default: return 0;
    }
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *env, jobject, jbyteArray cookie) {
    if (!cookie) return 0;
    const jsize size = env->GetArrayLength(cookie);
    if (size < static_cast<jsize>(sizeof(ALACSpecificConfig)) || size > 4096) return 0;
    std::vector<uint8_t> copy(static_cast<size_t>(size) < kCookieFloor ? kCookieFloor : static_cast<size_t>(size), 0);
    env->GetByteArrayRegion(cookie, 0, size, reinterpret_cast<jbyte *>(copy.data()));
    if (env->ExceptionCheck()) return 0;
    // Init allocates three buffers of frameLength * 4 bytes before anything checks the number: refuse it first.
    const uint32_t declared = declaredFrameLength(copy.data(), static_cast<size_t>(size));
    if (declared < kMinFrameLength || declared > kMaxFrameLength) return 0;
    auto *handle = new (std::nothrow) AlacHandle();
    if (!handle) return 0;
    const ALACSpecificConfig &config = handle->decoder.mConfig;
    const bool ok = handle->decoder.Init(copy.data(), static_cast<uint32_t>(size)) == 0 &&
        config.frameLength >= kMinFrameLength && config.frameLength <= kMaxFrameLength &&
        config.numChannels >= 1 && config.numChannels <= kMaxChannels &&
        config.sampleRate >= 1 && config.sampleRate <= kMaxSampleRate &&
        bytesFor(config.bitDepth) != 0;
    if (!ok) {
        delete handle;
        return 0;
    }
    handle->bytesPerSample = bytesFor(config.bitDepth);
    // A shifted-bytes block is skipped before any end check: at most 2 bytes per sample per channel.
    handle->slack = static_cast<size_t>(config.frameLength) * config.numChannels * 4 + 1024;
    return reinterpret_cast<intptr_t>(handle);
}

/** [frame length, bit depth, channels, sample rate], or null for a closed handle. */
extern "C" JNIEXPORT jintArray JNICALL JNI(config)(JNIEnv *env, jobject, jlong handle) {
    if (!handle) return nullptr;
    const ALACSpecificConfig &config = reinterpret_cast<AlacHandle *>(handle)->decoder.mConfig;
    const jint values[4] = {
        static_cast<jint>(config.frameLength), config.bitDepth, config.numChannels, static_cast<jint>(config.sampleRate),
    };
    jintArray result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}

/** Decodes one packet into [output]: the bytes written, or -1 when the packet cannot be decoded. */
extern "C" JNIEXPORT jint JNICALL JNI(decode)(JNIEnv *env, jobject, jlong handle, jbyteArray input, jint length,
        jbyteArray output) {
    if (!handle || !input || !output || length < 1 || env->GetArrayLength(input) < length) return -1;
    auto *alac = reinterpret_cast<AlacHandle *>(handle);
    const ALACSpecificConfig &config = alac->decoder.mConfig;
    const size_t frameBytes = static_cast<size_t>(config.frameLength) * config.numChannels * alac->bytesPerSample;
    if (static_cast<size_t>(env->GetArrayLength(output)) < frameBytes) return -1;
    // Twice the packet: the entropy decoder's own end check counts from the element it is in, so by itself it lets a
    // damaged packet read up to one packet length past where that element's data starts.
    alac->input.assign(static_cast<size_t>(length) * 2 + alac->slack, 0);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(alac->input.data()));
    if (env->ExceptionCheck()) return -1;
    std::vector<uint8_t> pcm(frameBytes, 0);
    BitBuffer bits;
    BitBufferInit(&bits, alac->input.data(), static_cast<uint32_t>(length));
    uint32_t samples = 0;
    const int32_t status = alac->decoder.Decode(&bits, pcm.data(), config.frameLength, config.numChannels, &samples);
    if (status != 0 || samples > config.frameLength) return -1;
    const size_t written = static_cast<size_t>(samples) * config.numChannels * alac->bytesPerSample;
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte *>(pcm.data()));
    return env->ExceptionCheck() ? -1 : static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL JNI(destroy)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<AlacHandle *>(handle);
}
