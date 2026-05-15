// Fast-path Opus decoder: bypasses libopusfile and calls libopus directly.
//
// Why: profiling shows libopusfile spends ~80 ms per track in `op_open_memory`
// (end-of-file bisection to compute pcm_total, CRC32 on every page) plus
// ~25 ms in `op_pcm_seek` (granule-position bisection). Actual codec work
// (`opus_decode_float`) is only ~30 ms. That makes libopusfile responsible
// for 75% of our Opus decode time — wrapper overhead, not codec cost.
//
// This file replaces the open+seek wrapper with a 200-line Ogg page walker:
//   1. One linear pass over the mmap'd file, recording each page's byte
//      offset, granule position, and packet-boundary state. No CRC checks.
//      ~3 ms on a 5 MB file.
//   2. Binary search the page table for the page covering our target sample.
//   3. Walk forward through pages, reassembling packets (Opus packets may
//      span pages via 255-byte segment continuation), and feeding each
//      packet to `opus_decode_float` directly.
//
// We trust local files: skipping CRC32 validation is safe and is exactly
// what Chromium's `media/filters/opus_audio_decoder.cc` and ffmpeg's
// `libavformat` Ogg demuxer do.

#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <vector>

#include "opus.h"
#include "audio_decode_common.h"

namespace {
inline int64_t monotonic_ns() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}
}  // namespace

#define LOG_TAG "NativeAudioDec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace latentjam_audio {

namespace {

constexpr int OUT_SR        = 48000;
constexpr int MAX_FRAME     = 5760;   // 120 ms @ 48 kHz, repacketized worst case
constexpr int MAX_CHANNELS  = 2;      // we downmix anything bigger after decode

struct PageInfo {
    std::size_t byte_offset;     // start of "OggS" capture pattern
    std::size_t header_size;     // 27 + segment_count
    std::size_t total_size;      // header_size + sum(segment sizes)
    std::uint64_t granule;       // end-of-page granule (UINT64_MAX = none)
    bool continues_prev_packet;  // header_type bit 0
    bool last_segment_is_255;    // trailing packet runs into next page
};

// Walk the mmap and record each Ogg page. No CRC, no validation beyond
// "the page header fits". Returns false if we couldn't find at least the
// OpusHead + OpusTags pages (i.e. it's not a valid Ogg/Opus stream).
bool build_index(const std::uint8_t* data, std::size_t size,
                 std::vector<PageInfo>& pages) {
    std::size_t off = 0;
    while (off + 27 <= size) {
        if (data[off]   != 'O' || data[off+1] != 'g' ||
            data[off+2] != 'g' || data[off+3] != 'S') {
            // Resync: skip ahead 1 byte and try again. Necessary on the
            // very first iteration only if the file starts with ID3-like
            // metadata, which is rare for Ogg/Opus.
            ++off;
            continue;
        }
        const std::uint8_t header_type = data[off + 5];
        std::uint64_t granule = 0;
        std::memcpy(&granule, data + off + 6, 8);   // LE on arm64
        const std::uint8_t n_segs = data[off + 26];
        const std::size_t header_size = 27 + n_segs;
        if (off + header_size > size) break;

        std::size_t payload = 0;
        bool last_seg_255 = false;
        for (std::uint8_t i = 0; i < n_segs; ++i) {
            const std::uint8_t s = data[off + 27 + i];
            payload += s;
            last_seg_255 = (s == 255);
        }
        const std::size_t page_size = header_size + payload;
        if (off + page_size > size) break;

        PageInfo pi;
        pi.byte_offset = off;
        pi.header_size = header_size;
        pi.total_size  = page_size;
        pi.granule     = granule;
        pi.continues_prev_packet = (header_type & 0x01) != 0;
        pi.last_segment_is_255   = last_seg_255;
        pages.push_back(pi);

        off += page_size;
    }
    return pages.size() >= 2;
}

// Parse the OpusHead packet (first page's payload) for channel count and
// pre_skip. Output sample rate is always 48 kHz at the decoder.
struct OpusHead {
    int channels;
    int pre_skip;
};
bool parse_opushead(const std::uint8_t* data, const PageInfo& page,
                    OpusHead& head) {
    const std::uint8_t* p = data + page.byte_offset + page.header_size;
    if (page.total_size - page.header_size < 19) return false;
    if (std::memcmp(p, "OpusHead", 8) != 0) return false;
    // Layout: 8-byte magic | version(1) | channel_count(1) | pre_skip(2 LE) |
    //         input_sr(4 LE) | output_gain(2) | channel_mapping(1) | ...
    head.channels = p[9];
    head.pre_skip = p[10] | (static_cast<int>(p[11]) << 8);
    return head.channels >= 1 && head.channels <= 8;
}

// Binary-search the page index for the last page whose granule is <= target.
// Pages with granule == UINT64_MAX (continuation-only pages with no completed
// packet) are rare for Opus; skip them by treating them as "not less than" so
// the search lands on the previous page that does carry a granule.
std::size_t page_for_granule(const std::vector<PageInfo>& pages,
                             std::uint64_t target_granule) {
    const std::size_t START = 2;  // pages 0/1 are OpusHead / OpusTags
    if (pages.size() <= START) return 0;
    std::size_t lo = START, hi = pages.size();
    while (lo < hi) {
        std::size_t mid = lo + (hi - lo) / 2;
        const std::uint64_t g = pages[mid].granule;
        if (g != UINT64_MAX && g < target_granule) lo = mid + 1;
        else hi = mid;
    }
    return lo > START ? lo - 1 : START;
}

// Average N-channel float interleaved → mono.
void downmix_to_mono(const float* interleaved, std::size_t samples_per_channel,
                     int channels, float* dst) {
    if (channels == 1) {
        std::memcpy(dst, interleaved, samples_per_channel * sizeof(float));
        return;
    }
    const float scale = 1.0f / static_cast<float>(channels);
    for (std::size_t i = 0; i < samples_per_channel; ++i) {
        float acc = 0.0f;
        const float* row = interleaved + i * channels;
        for (int c = 0; c < channels; ++c) acc += row[c];
        dst[i] = acc * scale;
    }
}

}  // namespace

bool decode_opus_fast(const std::uint8_t* data, std::size_t size,
                      std::int64_t start_us, std::int64_t duration_us,
                      Decoded& out) {
    const int64_t t0 = monotonic_ns();
    std::vector<PageInfo> pages;
    pages.reserve(1024);
    if (!build_index(data, size, pages)) return false;
    const int64_t t_idx = monotonic_ns();

    OpusHead head{};
    if (!parse_opushead(data, pages[0], head)) return false;
    int channels = head.channels > MAX_CHANNELS ? MAX_CHANNELS : head.channels;

    int err = 0;
    OpusDecoder* dec = opus_decoder_create(OUT_SR, channels, &err);
    if (!dec || err != OPUS_OK) {
        if (dec) opus_decoder_destroy(dec);
        return false;
    }
    const int64_t t_dec_open = monotonic_ns();

    // Map start_us → absolute granule. Opus granule = output PCM sample count
    // since the start of the stream, *including* pre_skip samples.
    const std::uint64_t start_granule =
        static_cast<std::uint64_t>(start_us) * OUT_SR / 1000000 + head.pre_skip;
    const std::size_t target_per_channel =
        static_cast<std::size_t>(duration_us) * OUT_SR / 1000000;
    if (target_per_channel == 0) {
        opus_decoder_destroy(dec);
        return false;
    }

    float* interleaved = static_cast<float*>(
        std::malloc(target_per_channel * channels * sizeof(float)));
    if (!interleaved) { opus_decoder_destroy(dec); return false; }

    // Reusable per-frame decode buffer (worst-case 120 ms × 2 channels).
    float frame_buf[MAX_FRAME * MAX_CHANNELS];

    // Walk pages, reassembling packets via segment lacing. State machine:
    //   - `pkt`: in-progress packet bytes (built from 255-byte segment chains)
    //   - `started`: have we seen a page whose first segment begins a fresh
    //     packet? Mid-stream we must discard any continuation packet we don't
    //     have the start of.
    std::vector<std::uint8_t> pkt;
    pkt.reserve(8 * 1024);
    bool started = false;
    bool any_decoded = false;
    std::size_t produced = 0;
    std::uint64_t cursor_granule = 0;
    bool cursor_known = false;

    const std::size_t first_page = page_for_granule(pages, start_granule);

    for (std::size_t pi = first_page;
         pi < pages.size() && produced < target_per_channel; ++pi) {
        const PageInfo& page = pages[pi];

        // If the page starts a fresh packet (no continuation flag), we can
        // safely begin emitting from here. Otherwise we have to discard any
        // dangling packet bytes we accumulated before being aligned.
        if (!page.continues_prev_packet) {
            started = true;
            pkt.clear();
        } else if (!started) {
            // Skip pages until we find one with a fresh packet boundary.
            continue;
        }

        const std::uint8_t* seg_tbl = data + page.byte_offset + 27;
        const std::uint8_t n_segs = data[page.byte_offset + 26];
        const std::uint8_t* seg_data = data + page.byte_offset + page.header_size;

        std::size_t seg_off = 0;
        for (std::uint8_t i = 0; i < n_segs; ++i) {
            const std::uint8_t s = seg_tbl[i];
            pkt.insert(pkt.end(), seg_data + seg_off, seg_data + seg_off + s);
            seg_off += s;
            if (s < 255) {
                // Complete packet. Decode it.
                if (!pkt.empty()) {
                    int n = opus_decode_float(
                        dec, pkt.data(), static_cast<opus_int32>(pkt.size()),
                        frame_buf, MAX_FRAME, /*decode_fec=*/0);
                    pkt.clear();
                    if (n <= 0) continue;

                    // Advance granule cursor for output trimming.
                    if (!cursor_known) {
                        // First successful decode after seek — assume this
                        // page's granule sits at the end of the *previous*
                        // page's content. We don't have it directly; fall
                        // back to using start_granule and let trimming
                        // catch up.
                        cursor_granule = start_granule;
                        cursor_known   = true;
                    }
                    std::size_t emit = static_cast<std::size_t>(n);
                    std::size_t skip = 0;
                    // Discard samples earlier than start_granule.
                    if (cursor_granule < start_granule) {
                        std::uint64_t need = start_granule - cursor_granule;
                        if (need >= emit) {
                            cursor_granule += emit;
                            continue;
                        }
                        skip = static_cast<std::size_t>(need);
                    }
                    cursor_granule += emit;
                    emit -= skip;
                    if (emit > target_per_channel - produced) {
                        emit = target_per_channel - produced;
                    }
                    std::memcpy(
                        interleaved + produced * channels,
                        frame_buf + skip * channels,
                        emit * channels * sizeof(float));
                    produced += emit;
                    any_decoded = true;
                    if (produced >= target_per_channel) break;
                }
            }
        }
        // After processing all segments: if last seg was < 255, pkt is empty
        // and we're packet-aligned. If last seg was 255, pkt carries the
        // partial packet into the next page.
    }

    opus_decoder_destroy(dec);

    if (!any_decoded || produced == 0) {
        std::free(interleaved);
        return false;
    }

    out.mono = static_cast<float*>(std::malloc(produced * sizeof(float)));
    if (!out.mono) { std::free(interleaved); return false; }
    downmix_to_mono(interleaved, produced, channels, out.mono);
    std::free(interleaved);

    out.samples     = produced;
    out.sample_rate = OUT_SR;
    const int64_t t_end = monotonic_ns();
    LOGI("opus(fast) OK ch=%d pages=%zu got=%zu | idx=%.2fms open=%.2fms decode+downmix=%.2fms total=%.2fms",
         channels, pages.size(), produced,
         (t_idx - t0) / 1e6,
         (t_dec_open - t_idx) / 1e6,
         (t_end - t_dec_open) / 1e6,
         (t_end - t0) / 1e6);
    return true;
}

}  // namespace latentjam_audio
