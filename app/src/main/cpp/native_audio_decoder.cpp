/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * native_audio_decoder.cpp is part of LatentJam.
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
#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstddef>

#define MINIMP3_FLOAT_OUTPUT
#define MINIMP3_IMPLEMENTATION
#include "minimp3.h"
#define MINIMP3_EX_IMPLEMENTATION
#include "minimp3_ex.h"

// Native audio decoder that bypasses `android.media.MediaExtractor` for every
// popular audio container we ship support for. The platform extractor charges
// 3-15 s/track for random-offset seek on VBR mp3 (no seek table), Ogg streams
// (linear page walk), M4A (sample-table I/O over SAF), and FLAC (block index
// walk over SAF). That is an order of magnitude above the encoder's ~95 ms
// budget. Native decoders on mmap()'d data run all of these in 30-150 ms.
//
// Format dispatch:
//   "fLaC"            → dr_flac      (SEEKTABLE-aware sample seek)
//   "RIFF"...WAVE     → dr_wav       (PCM offset arithmetic, trivial)
//   "OggS"+OpusHead   → libopusfile  (binary-search granule seek)
//   "OggS"+"\x01vorbis" → stb_vorbis (pushdata, sample-accurate seek)
//   "ID3" / mp3 sync  → minimp3_ex   (byte-estimate seek, ~10 ms)
//   "....ftyp"        → minimp4 demux + fdk-aac decoder  (sample-table seek)
//
// Returns mono float32 PCM at the source sample rate. Anything we can't
// identify or fail to decode falls back to MediaExtractor on the Kotlin side.
// Guard against a regression on MINIMP3_FLOAT_OUTPUT: if the macro is not
// effective at this include site (e.g. minimp3.h was pulled in transitively
// earlier without the define), `mp3d_sample_t` resolves to `int16_t` and
// mp3dec_ex_read() writes int16-range values into our float* buffer — which is
// exactly the silent corruption that produced energy=1.0 / bpm=117 saturation
// for 78 % of the library on the first CLAP-on-device run.
static_assert(sizeof(mp3d_sample_t) == sizeof(float),
        "MINIMP3_FLOAT_OUTPUT not effective: mp3d_sample_t is not float");

#include <opusfile.h>

#define DR_FLAC_IMPLEMENTATION
#include "dr_flac.h"
#define DR_WAV_IMPLEMENTATION
#include "dr_wav.h"

// stb_vorbis is also compiled as its own TU in CMakeLists. Pull in declarations
// only here — defining `STB_VORBIS_HEADER_ONLY` makes the same file emit only
// prototypes when included.
#define STB_VORBIS_HEADER_ONLY
#define STB_VORBIS_NO_PUSHDATA_API
#define STB_VORBIS_NO_STDIO
#include "stb_vorbis.c"

// M4A/AAC lives in `m4a_aac_decoder.cpp` because minimp4 and minimp3 declare
// incompatible `bs_t` structs in the same TU. See audio_decode_common.h.
#include "audio_decode_common.h"

#define LOG_TAG "NativeAudioDec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

using latentjam_audio::Decoded;

namespace {

void free_decoded(Decoded &d) {
    if (d.mono) {
        std::free(d.mono);
        d.mono = nullptr;
    }
    d.samples = 0;
}

void downmix_to_mono(const float *interleaved, size_t samples_per_channel,
        int channels, float *dst) {
    if (channels == 1) {
        std::memcpy(dst, interleaved, samples_per_channel * sizeof(float));
        return;
    }
    const float scale = 1.0f / static_cast<float>(channels);
    for (size_t i = 0; i < samples_per_channel; ++i) {
        float acc = 0.0f;
        const float *row = interleaved + i * channels;
        for (int c = 0; c < channels; ++c)
            acc += row[c];
        dst[i] = acc * scale;
    }
}

// ============================================================
// mp3 (minimp3_ex on mmap'd buffer)
// ============================================================
bool decode_mp3(const uint8_t *data, size_t size, int64_t start_us,
        int64_t duration_us, Decoded &out) {
    // Under-report by SAFETY_PAD bytes: malformed mp3s (truncated downloads, bad
    // ID3v2 size fields) make minimp3 walk past the end while resyncing. The
    // tail we drop is shorter than our analysis window anyway.
    static constexpr size_t SAFETY_PAD = 16 * 1024;
    if (size <= SAFETY_PAD + 1024)
        return false;
    size_t safe_size = size - SAFETY_PAD;

    mp3dec_ex_t dec;
    if (mp3dec_ex_open_buf(&dec, data, safe_size, MP3D_SEEK_TO_SAMPLE) != 0) {
        return false;
    }

    int channels = dec.info.channels > 0 ? dec.info.channels : 1;
    int sample_rate = dec.info.hz > 0 ? dec.info.hz : 44100;
    uint64_t total_samples = dec.samples;

    uint64_t start_sample_interleaved = static_cast<uint64_t>(start_us
            * sample_rate / 1000000) * static_cast<uint64_t>(channels);
    if (total_samples > 0 && start_sample_interleaved >= total_samples) {
        start_sample_interleaved =
                total_samples > static_cast<uint64_t>(channels) ?
                        total_samples - static_cast<uint64_t>(channels) : 0;
    }
    if (mp3dec_ex_seek(&dec, start_sample_interleaved) != 0) {
        mp3dec_ex_close(&dec);
        return false;
    }

    size_t target_per_channel = static_cast<size_t>(duration_us * sample_rate
            / 1000000);
    if (target_per_channel == 0) {
        mp3dec_ex_close(&dec);
        return false;
    }
    size_t target_interleaved = target_per_channel
            * static_cast<size_t>(channels);

    float *interleaved = static_cast<float*>(std::malloc(
            target_interleaved * sizeof(float)));
    if (!interleaved) {
        mp3dec_ex_close(&dec);
        return false;
    }

    size_t got = mp3dec_ex_read(&dec, interleaved, target_interleaved);
    mp3dec_ex_close(&dec);

    if (got == 0) {
        std::free(interleaved);
        return false;
    }
    size_t got_per_channel = got / static_cast<size_t>(channels);

    // DIAGNOSTIC: scan for actual min/max so we can prove whether mp3dec_ex_read
    // honored MINIMP3_FLOAT_OUTPUT or wrote int16-range values into the float buffer.
    float dbg_min = 1e30f, dbg_max = -1e30f;
    size_t dbg_n = got < 2048 ? got : 2048;
    for (size_t i = 0; i < dbg_n; ++i) {
        if (interleaved[i] < dbg_min)
            dbg_min = interleaved[i];
        if (interleaved[i] > dbg_max)
            dbg_max = interleaved[i];
    }
    LOGI("mp3 raw scan: min=%.4f max=%.4f (over %zu samples)", dbg_min, dbg_max,
            dbg_n);

    out.mono =
            static_cast<float*>(std::malloc(got_per_channel * sizeof(float)));
    if (!out.mono) {
        std::free(interleaved);
        return false;
    }
    downmix_to_mono(interleaved, got_per_channel, channels, out.mono);
    std::free(interleaved);

    out.samples = got_per_channel;
    out.sample_rate = sample_rate;
    LOGI("mp3 OK sr=%d ch=%d got=%zu", sample_rate, channels, got_per_channel);
    return true;
}

// ============================================================
// opus (libopusfile on mmap'd buffer)
// ============================================================
bool decode_opus(const uint8_t *data, size_t size, int64_t start_us,
        int64_t duration_us, Decoded &out) {
    int err = 0;
    OggOpusFile *of = op_open_memory(data, size, &err);
    if (!of)
        return false;

    constexpr int OPUS_SR = 48000;
    int channels = op_channel_count(of, -1);
    if (channels < 1)
        channels = 2;

    ogg_int64_t start_sample = static_cast<ogg_int64_t>(start_us * OPUS_SR
            / 1000000);
    if (op_pcm_seek(of, start_sample) != 0)
        op_pcm_seek(of, 0);

    size_t target_per_channel = static_cast<size_t>(duration_us * OPUS_SR
            / 1000000);
    if (target_per_channel == 0) {
        op_free(of);
        return false;
    }

    float *interleaved = static_cast<float*>(std::malloc(
            target_per_channel * static_cast<size_t>(channels) * sizeof(float)));
    if (!interleaved) {
        op_free(of);
        return false;
    }

    size_t produced = 0;
    while (produced < target_per_channel) {
        size_t remaining = target_per_channel - produced;
        int n = op_read_float(of,
                interleaved + produced * static_cast<size_t>(channels),
                static_cast<int>(remaining * static_cast<size_t>(channels)),
                nullptr);
        if (n < 0) {
            std::free(interleaved);
            op_free(of);
            return false;
        }
        if (n == 0)
            break;
        produced += static_cast<size_t>(n);
    }
    op_free(of);

    if (produced == 0) {
        std::free(interleaved);
        return false;
    }

    out.mono = static_cast<float*>(std::malloc(produced * sizeof(float)));
    if (!out.mono) {
        std::free(interleaved);
        return false;
    }
    downmix_to_mono(interleaved, produced, channels, out.mono);
    std::free(interleaved);

    out.samples = produced;
    out.sample_rate = OPUS_SR;
    LOGI("opus OK ch=%d got=%zu", channels, produced);
    return true;
}

// ============================================================
// FLAC (dr_flac on mmap'd buffer)
// ============================================================
bool decode_flac(const uint8_t *data, size_t size, int64_t start_us,
        int64_t duration_us, Decoded &out) {
    drflac *f = drflac_open_memory(data, size, nullptr);
    if (!f)
        return false;

    int channels = static_cast<int>(f->channels);
    int sample_rate = static_cast<int>(f->sampleRate);
    drflac_uint64 total_frames = f->totalPCMFrameCount;
    if (channels < 1 || sample_rate <= 0) {
        drflac_close(f);
        return false;
    }

    drflac_uint64 start_frame = static_cast<drflac_uint64>(start_us)
            * sample_rate / 1000000;
    if (total_frames > 0 && start_frame >= total_frames) {
        start_frame = total_frames > 1 ? total_frames - 1 : 0;
    }
    if (!drflac_seek_to_pcm_frame(f, start_frame)) {
        drflac_close(f);
        return false;
    }

    drflac_uint64 target_frames = static_cast<drflac_uint64>(duration_us)
            * sample_rate / 1000000;
    if (target_frames == 0) {
        drflac_close(f);
        return false;
    }

    float *interleaved = static_cast<float*>(std::malloc(
            target_frames * static_cast<size_t>(channels) * sizeof(float)));
    if (!interleaved) {
        drflac_close(f);
        return false;
    }

    drflac_uint64 got = drflac_read_pcm_frames_f32(f, target_frames,
            interleaved);
    drflac_close(f);

    if (got == 0) {
        std::free(interleaved);
        return false;
    }

    size_t got_sz = static_cast<size_t>(got);
    out.mono = static_cast<float*>(std::malloc(got_sz * sizeof(float)));
    if (!out.mono) {
        std::free(interleaved);
        return false;
    }
    downmix_to_mono(interleaved, got_sz, channels, out.mono);
    std::free(interleaved);

    out.samples = got_sz;
    out.sample_rate = sample_rate;
    LOGI("flac OK sr=%d ch=%d got=%zu", sample_rate, channels, got_sz);
    return true;
}

// ============================================================
// WAV (dr_wav on mmap'd buffer)
// ============================================================
bool decode_wav(const uint8_t *data, size_t size, int64_t start_us,
        int64_t duration_us, Decoded &out) {
    drwav wav;
    if (!drwav_init_memory(&wav, data, size, nullptr))
        return false;

    int channels = static_cast<int>(wav.channels);
    int sample_rate = static_cast<int>(wav.sampleRate);
    drwav_uint64 total_frames = wav.totalPCMFrameCount;
    if (channels < 1 || sample_rate <= 0) {
        drwav_uninit(&wav);
        return false;
    }

    drwav_uint64 start_frame = static_cast<drwav_uint64>(start_us) * sample_rate
            / 1000000;
    if (total_frames > 0 && start_frame >= total_frames) {
        start_frame = total_frames > 1 ? total_frames - 1 : 0;
    }
    if (!drwav_seek_to_pcm_frame(&wav, start_frame)) {
        drwav_uninit(&wav);
        return false;
    }

    drwav_uint64 target_frames = static_cast<drwav_uint64>(duration_us)
            * sample_rate / 1000000;
    if (target_frames == 0) {
        drwav_uninit(&wav);
        return false;
    }

    float *interleaved = static_cast<float*>(std::malloc(
            target_frames * static_cast<size_t>(channels) * sizeof(float)));
    if (!interleaved) {
        drwav_uninit(&wav);
        return false;
    }

    drwav_uint64 got = drwav_read_pcm_frames_f32(&wav, target_frames,
            interleaved);
    drwav_uninit(&wav);

    if (got == 0) {
        std::free(interleaved);
        return false;
    }

    size_t got_sz = static_cast<size_t>(got);
    out.mono = static_cast<float*>(std::malloc(got_sz * sizeof(float)));
    if (!out.mono) {
        std::free(interleaved);
        return false;
    }
    downmix_to_mono(interleaved, got_sz, channels, out.mono);
    std::free(interleaved);

    out.samples = got_sz;
    out.sample_rate = sample_rate;
    LOGI("wav OK sr=%d ch=%d got=%zu", sample_rate, channels, got_sz);
    return true;
}

// ============================================================
// Ogg/Vorbis (stb_vorbis on mmap'd buffer)
// ============================================================
bool decode_vorbis(const uint8_t *data, size_t size, int64_t start_us,
        int64_t duration_us, Decoded &out) {
    int err = 0;
    // const_cast: stb_vorbis_open_memory takes non-const for its internal data
    // pointer but does not write through it.
    stb_vorbis *v = stb_vorbis_open_memory(const_cast<uint8_t*>(data),
            static_cast<int>(size), &err, nullptr);
    if (!v)
        return false;

    stb_vorbis_info info = stb_vorbis_get_info(v);
    int channels = info.channels;
    int sample_rate = static_cast<int>(info.sample_rate);
    if (channels < 1 || sample_rate <= 0) {
        stb_vorbis_close(v);
        return false;
    }

    unsigned int start_sample = static_cast<unsigned int>(start_us * sample_rate
            / 1000000);
    // stb_vorbis_seek seeks to a sample (per channel). It expects an unsigned
    // sample index; clamping past EOF makes it fail silently — we accept that.
    stb_vorbis_seek(v, start_sample);

    size_t target_per_channel = static_cast<size_t>(duration_us * sample_rate
            / 1000000);
    if (target_per_channel == 0) {
        stb_vorbis_close(v);
        return false;
    }

    float *interleaved = static_cast<float*>(std::malloc(
            target_per_channel * static_cast<size_t>(channels) * sizeof(float)));
    if (!interleaved) {
        stb_vorbis_close(v);
        return false;
    }

    size_t produced = 0;
    while (produced < target_per_channel) {
        int remaining_per_channel = static_cast<int>(target_per_channel
                - produced);
        // get_samples_float_interleaved expects buffer length in *interleaved*
        // values and returns samples *per channel*.
        int n = stb_vorbis_get_samples_float_interleaved(v, channels,
                interleaved + produced * static_cast<size_t>(channels),
                remaining_per_channel * channels);
        if (n <= 0)
            break;
        produced += static_cast<size_t>(n);
    }
    stb_vorbis_close(v);

    if (produced == 0) {
        std::free(interleaved);
        return false;
    }

    out.mono = static_cast<float*>(std::malloc(produced * sizeof(float)));
    if (!out.mono) {
        std::free(interleaved);
        return false;
    }
    downmix_to_mono(interleaved, produced, channels, out.mono);
    std::free(interleaved);

    out.samples = produced;
    out.sample_rate = sample_rate;
    LOGI("vorbis OK sr=%d ch=%d got=%zu", sample_rate, channels, produced);
    return true;
}

// ============================================================
// M4A / AAC — implementation lives in m4a_aac_decoder.cpp (separate TU due to
// minimp3/minimp4 bs_t struct collision). Forward-declared here for dispatch.
// ============================================================
// (no body needed in this TU)

// ============================================================
// format sniff
// ============================================================
// Sniff codes — kept in this order for backwards compat with the Kotlin
// `isHandledFormat` check (any non-zero means "we accelerate this format").
enum Format {
    FMT_UNKNOWN = 0,
    FMT_MP3 = 1,
    FMT_OPUS = 2,
    FMT_FLAC = 3,
    FMT_WAV = 4,
    FMT_VORBIS = 5,
    FMT_M4A = 6,
};

// ID3v2 tags are 10 bytes of header + a synchsafe (7-bit-per-byte) size,
// followed by the actual audio. Some FLAC files in the wild ship with an
// ID3v2 prefix that would otherwise route to the mp3 path; peek past it.
size_t id3v2_skip(const uint8_t *data, size_t size) {
    if (size < 10)
        return 0;
    if (data[0] != 'I' || data[1] != 'D' || data[2] != '3')
        return 0;
    // Synchsafe size: top bit of each of bytes [6..9] is reserved 0.
    uint32_t sz = (uint32_t(data[6]) << 21) | (uint32_t(data[7]) << 14)
            | (uint32_t(data[8]) << 7) | (uint32_t(data[9]));
    size_t total = static_cast<size_t>(sz) + 10;
    return total < size ? total : 0;
}

// For Ogg containers we need to peek at the first page payload to discriminate
// Opus from Vorbis. The codec ID lives within the first ~58 bytes of the file
// (Ogg header is 27 bytes + segment table). We scan up to 512 bytes for safety.
Format ogg_codec(const uint8_t *data, size_t size) {
    size_t n = size < 512 ? size : 512;
    if (n < 16)
        return FMT_OPUS; // not enough to tell; existing libopusfile fail-mode handles it
    for (size_t i = 0; i + 8 < n; ++i) {
        if (data[i] == 'O' && data[i + 1] == 'p' && data[i + 2] == 'u'
                && data[i + 3] == 's' && data[i + 4] == 'H'
                && data[i + 5] == 'e' && data[i + 6] == 'a'
                && data[i + 7] == 'd') {
            return FMT_OPUS;
        }
    }
    for (size_t i = 0; i + 7 < n; ++i) {
        if (data[i] == 0x01 && data[i + 1] == 'v' && data[i + 2] == 'o'
                && data[i + 3] == 'r' && data[i + 4] == 'b'
                && data[i + 5] == 'i' && data[i + 6] == 's') {
            return FMT_VORBIS;
        }
    }
    // Unrecognized Ogg payload (Speex, FLAC-in-Ogg, Theora...). Let it fall
    // through to MediaExtractor — none of these are common in music libraries.
    return FMT_UNKNOWN;
}

Format sniff(const uint8_t *data, size_t size) {
    if (size < 12)
        return FMT_UNKNOWN;

    // 1. Ogg container — discriminate Opus vs Vorbis.
    if (data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S') {
        return ogg_codec(data, size);
    }
    // 2. FLAC native ("fLaC" magic).
    if (data[0] == 'f' && data[1] == 'L' && data[2] == 'a' && data[3] == 'C') {
        return FMT_FLAC;
    }
    // 3. RIFF/WAVE.
    if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
            && data[8] == 'W' && data[9] == 'A' && data[10] == 'V'
            && data[11] == 'E') {
        return FMT_WAV;
    }
    // 4. ISO Base Media (.m4a / .mp4 / .m4b / .3gp): box at offset 0 is "ftyp".
    if (data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
        // Major brand sanity — we only want audio. Bail on obvious video brands
        // so MediaExtractor can complain instead of feeding random bytes to
        // fdk-aac. The complete set isn't worth enumerating; cover the common
        // audio brands and let everything else fall back.
        const uint8_t *brand = data + 8;
        auto is = [&](const char a, const char b, const char c, const char d) {
            return brand[0] == a && brand[1] == b && brand[2] == c
                    && brand[3] == d;
        };
        if (is('M', '4', 'A', ' ') || is('M', '4', 'B', ' ')
                || is('M', '4', 'P', ' ') || is('m', 'p', '4', '2')
                || is('m', 'p', '4', '1') || is('i', 's', 'o', 'm')
                || is('i', 's', 'o', '2') || is('i', 's', 'o', '5')
                || is('d', 'a', 's', 'h') || is('f', '4', 'a', ' ')) {
            return FMT_M4A;
        }
        // Unknown ftyp brand — punt.
        return FMT_UNKNOWN;
    }
    // 5. ID3v2 prefix — could be MP3 OR a FLAC with tag at front. Peek past.
    if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
        size_t skip = id3v2_skip(data, size);
        if (skip > 0 && skip + 4 <= size) {
            const uint8_t *p = data + skip;
            if (p[0] == 'f' && p[1] == 'L' && p[2] == 'a' && p[3] == 'C') {
                return FMT_FLAC;
            }
            if (p[0] == 0xFF && (p[1] & 0xE0) == 0xE0)
                return FMT_MP3;
        }
        // Best-effort default for a tagged file we couldn't see past: mp3.
        return FMT_MP3;
    }
    // 6. Raw MPEG-1/2/2.5 layer III frame sync.
    if (data[0] == 0xFF && (data[1] & 0xE0) == 0xE0) {
        return FMT_MP3;
    }
    return FMT_UNKNOWN;
}

}  // namespace

extern "C" {

JNIEXPORT __attribute__((visibility("default")))
jfloatArray JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeAudioDecoder_nativeDecode(
        JNIEnv* env, jclass /*clazz*/,
        jint fd, jlong fileLen, jlong startUs, jlong durationUs, jintArray outSr)
{
    if (fd < 0 || fileLen <= 0 || durationUs <= 0) return nullptr;

    void* mapped = ::mmap(nullptr, static_cast<size_t>(fileLen), PROT_READ,
            MAP_PRIVATE, fd, 0);
    if (mapped == MAP_FAILED) {
        LOGW("mmap failed");
        return nullptr;
    }
    const uint8_t* data = static_cast<const uint8_t*>(mapped);
    const size_t size = static_cast<size_t>(fileLen);

    Decoded out;
    Format fmt = sniff(data, size);
    bool ok = false;
    switch (fmt) {
        case FMT_MP3: ok = decode_mp3 (data, size, startUs, durationUs, out); break;
        case FMT_OPUS:
        // Fast path: custom Ogg walker + libopus direct, ~3× quicker than
        // op_open_memory+op_pcm_seek. Fall back to libopusfile on any
        // failure (malformed pages, chained streams, etc.).
        ok = latentjam_audio::decode_opus_fast(
                data, size, startUs, durationUs, out);
        if (!ok) ok = decode_opus(data, size, startUs, durationUs, out);
        break;
        case FMT_FLAC: ok = decode_flac (data, size, startUs, durationUs, out); break;
        case FMT_WAV: ok = decode_wav (data, size, startUs, durationUs, out); break;
        case FMT_VORBIS: ok = decode_vorbis(data, size, startUs, durationUs, out); break;
        case FMT_M4A: ok = latentjam_audio::decode_m4a_aac(
                data, size, startUs, durationUs, out); break;
        default: break;
    }

    ::munmap(mapped, size);

    if (!ok || out.mono == nullptr || out.samples == 0) {
        free_decoded(out);
        return nullptr;
    }

    jfloatArray jarr = env->NewFloatArray(static_cast<jsize>(out.samples));
    if (!jarr) {free_decoded(out); return nullptr;}
    env->SetFloatArrayRegion(jarr, 0, static_cast<jsize>(out.samples), out.mono);
    int sr_out = out.sample_rate;
    free_decoded(out);

    if (outSr != nullptr && env->GetArrayLength(outSr) >= 1) {
        jint sr = sr_out;
        env->SetIntArrayRegion(outSr, 0, 1, &sr);
    }
    return jarr;
}

/** Sniff codes:
 *    0 — unsupported / unknown (caller falls back to MediaExtractor)
 *    1 — mp3
 *    2 — opus  (Ogg/Opus)
 *    3 — flac
 *    4 — wav
 *    5 — vorbis (Ogg/Vorbis)
 *    6 — m4a / mp4 (AAC) */
JNIEXPORT __attribute__((visibility("default")))
jint JNICALL
Java_io_github_nikitasud_latentjam_ml_audio_NativeAudioDecoder_nativeSniff(
        JNIEnv* env, jclass /*clazz*/, jbyteArray header)
{
    if (header == nullptr) return 0;
    jsize n = env->GetArrayLength(header);
    if (n < 12) return 0;
    jbyte buf[64] = {};
    jsize take = n < 64 ? n : 64;
    env->GetByteArrayRegion(header, 0, take, buf);
    Format f = sniff(reinterpret_cast<const uint8_t*>(buf), static_cast<size_t>(take));
    return static_cast<jint>(f);
}

}
        // extern "C"