// M4A / AAC native fast-path decoder.
//
// Lives in its own translation unit because both `minimp3.h` (used in
// `native_audio_decoder.cpp`) and `minimp4.h` define a `struct bs_t` bitstream
// reader with conflicting layouts. Including them in one TU breaks at parse
// time, so we isolate minimp4's IMPLEMENTATION here.

#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>

#define MINIMP4_IMPLEMENTATION
#include "minimp4.h"
#include "aacdecoder_lib.h"

#include "audio_decode_common.h"

#define LOG_TAG "NativeAudioDec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace latentjam_audio {

namespace {

struct MmapCtx {
    const uint8_t* data;
    size_t         size;
};

int mmap_read_callback(int64_t offset, void* buffer, size_t want, void* token) {
    auto* ctx = static_cast<MmapCtx*>(token);
    if (offset < 0 || static_cast<size_t>(offset) >= ctx->size) return -1;
    size_t avail = ctx->size - static_cast<size_t>(offset);
    size_t take  = want < avail ? want : avail;
    std::memcpy(buffer, ctx->data + offset, take);
    return take == want ? 0 : -1;
}

// fdk-aac's INT_PCM type is int16_t in the default Android build. We downmix
// directly to mono float in the same pass to avoid an extra allocation.
void downmix_s16_to_mono(const int16_t* interleaved, size_t samples_per_channel,
                         int channels, float* dst) {
    const float inv = 1.0f / 32768.0f;
    if (channels == 1) {
        for (size_t i = 0; i < samples_per_channel; ++i) {
            dst[i] = static_cast<float>(interleaved[i]) * inv;
        }
        return;
    }
    const float scale = inv / static_cast<float>(channels);
    for (size_t i = 0; i < samples_per_channel; ++i) {
        int32_t acc = 0;
        const int16_t* row = interleaved + i * channels;
        for (int c = 0; c < channels; ++c) acc += row[c];
        dst[i] = static_cast<float>(acc) * scale;
    }
}

}  // namespace

bool decode_m4a_aac(const uint8_t* data, size_t size, int64_t start_us,
                    int64_t duration_us, Decoded& out) {
    MmapCtx ctx{data, size};
    MP4D_demux_t demux{};
    if (!MP4D_open(&demux, mmap_read_callback, &ctx, static_cast<int64_t>(size))) {
        return false;
    }

    // Locate an AAC audio track. M4A files normally have exactly one but we
    // walk all tracks defensively and pick the first with 'soun' handler and a
    // non-empty AudioSpecificConfig (DSI).
    int track_idx = -1;
    for (unsigned i = 0; i < demux.track_count; ++i) {
        if (demux.track[i].handler_type == MP4D_HANDLER_TYPE_SOUN &&
            demux.track[i].dsi != nullptr &&
            demux.track[i].dsi_bytes > 0 &&
            demux.track[i].sample_count > 0) {
            track_idx = static_cast<int>(i);
            break;
        }
    }
    if (track_idx < 0) { MP4D_close(&demux); return false; }
    const MP4D_track_t& tr = demux.track[track_idx];

    int channels    = static_cast<int>(tr.SampleDescription.audio.channelcount);
    int sample_rate = static_cast<int>(tr.SampleDescription.audio.samplerate_hz);
    if (channels < 1 || sample_rate <= 0) { MP4D_close(&demux); return false; }

    HANDLE_AACDECODER hAac = aacDecoder_Open(TT_MP4_RAW, 1);
    if (!hAac) { MP4D_close(&demux); return false; }
    UCHAR* asc_ptr[1] = { tr.dsi };
    UINT   asc_sz [1] = { tr.dsi_bytes };
    if (aacDecoder_ConfigRaw(hAac, asc_ptr, asc_sz) != AAC_DEC_OK) {
        aacDecoder_Close(hAac);
        MP4D_close(&demux);
        return false;
    }

    // mp4 timestamps are in the track's timescale, almost always equal to the
    // sample rate. Linear-scan the per-sample timestamps to find the first AAC
    // frame at-or-before start_us — fewer than a few thousand entries, costs
    // well under a millisecond and side-steps non-monotonic-timestamp edge
    // cases.
    uint64_t start_ts =
        static_cast<uint64_t>(start_us) * tr.timescale / 1000000;
    unsigned start_frame = 0;
    {
        unsigned best = 0;
        for (unsigned i = 0; i < tr.sample_count; ++i) {
            unsigned ts = 0, dur = 0, sz = 0;
            MP4D_frame_offset(&demux, track_idx, i, &sz, &ts, &dur);
            if (ts <= start_ts) best = i; else break;
        }
        start_frame = best;
    }

    size_t target_per_channel =
        static_cast<size_t>(duration_us * sample_rate / 1000000);
    if (target_per_channel == 0) {
        aacDecoder_Close(hAac);
        MP4D_close(&demux);
        return false;
    }

    out.mono = static_cast<float*>(std::malloc(target_per_channel * sizeof(float)));
    if (!out.mono) {
        aacDecoder_Close(hAac);
        MP4D_close(&demux);
        return false;
    }
    constexpr size_t PCM_BUF_SAMPLES = 8 * 2048;  // headroom for HE-AAC SBR
    INT_PCM pcm_buf[PCM_BUF_SAMPLES];
    size_t out_pos = 0;

    bool ok = false;
    int actual_sr = sample_rate;
    for (unsigned i = start_frame;
         i < tr.sample_count && out_pos < target_per_channel; ++i) {
        unsigned frame_bytes = 0, ts = 0, dur = 0;
        MP4D_file_offset_t off =
            MP4D_frame_offset(&demux, track_idx, i, &frame_bytes, &ts, &dur);
        if (frame_bytes == 0 || off + frame_bytes > size) break;

        UCHAR* inbuf[1]   = { const_cast<UCHAR*>(data + off) };
        UINT   in_size[1] = { frame_bytes };
        UINT   bytes_valid = frame_bytes;
        if (aacDecoder_Fill(hAac, inbuf, in_size, &bytes_valid) != AAC_DEC_OK) continue;

        AAC_DECODER_ERROR e =
            aacDecoder_DecodeFrame(hAac, pcm_buf, PCM_BUF_SAMPLES, 0);
        if (e == AAC_DEC_NOT_ENOUGH_BITS) continue;
        if (e != AAC_DEC_OK) continue;  // skip one frame on decode errors

        CStreamInfo* si = aacDecoder_GetStreamInfo(hAac);
        if (!si || si->frameSize <= 0) continue;
        int produced_per_channel = si->frameSize;
        int real_channels = si->numChannels > 0 ? si->numChannels : channels;
        actual_sr = si->sampleRate > 0 ? si->sampleRate : sample_rate;

        size_t emit = static_cast<size_t>(produced_per_channel);
        if (out_pos + emit > target_per_channel) {
            emit = target_per_channel - out_pos;
        }
        downmix_s16_to_mono(pcm_buf, emit, real_channels, out.mono + out_pos);
        out_pos += emit;
        ok = true;
    }

    aacDecoder_Close(hAac);
    MP4D_close(&demux);

    if (!ok || out_pos == 0) {
        if (out.mono) { std::free(out.mono); out.mono = nullptr; }
        out.samples = 0;
        return false;
    }
    out.samples     = out_pos;
    out.sample_rate = actual_sr;
    LOGI("m4a OK sr=%d ch=%d got=%zu (from frame %u/%u)",
         actual_sr, channels, out_pos, start_frame, tr.sample_count);
    return true;
}

}  // namespace latentjam_audio
