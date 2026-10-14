/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#include "lj_loudness.h"
#include "vendor/libebur128/ebur128.h"
#include <algorithm>
#include <cmath>
#include <mutex>
#include <new>

struct lj_loudness {
    ebur128_state *state;
    uint64_t frames = 0;
    bool invalid = false;
    bool finished = false;
};
namespace {
// libebur128 1.2.6 reinitializes shared lookup tables in every init(). Serialize ALL calls,
// including readers, so creating a second meter cannot race an existing analysis. No audio
// render callback uses this lock. The upstream sources remain unmodified.
std::mutex library_mutex;
constexpr int max_chunk = 4096;
}
extern "C" lj_loudness *lj_loudness_create(int rate, int channels) {
    if (rate < 8000 || rate > 192000 || channels < 1 || channels > 2) return nullptr;
    std::lock_guard<std::mutex> lock(library_mutex);
    auto *state = ebur128_init(channels, rate,
        EBUR128_MODE_I | EBUR128_MODE_TRUE_PEAK | EBUR128_MODE_HISTOGRAM);
    if (!state) return nullptr;
    auto *meter = new (std::nothrow) lj_loudness{state};
    if (!meter) ebur128_destroy(&state);
    return meter;
}
extern "C" int lj_loudness_add(lj_loudness *m, const float *samples, int frames) {
    if (!m || m->invalid || m->finished) return 0;
    if (!samples || frames <= 0 || frames > max_chunk ||
        m->frames + uint64_t(frames) > uint64_t(m->state->samplerate) * 6 * 3600) {
        m->invalid = true; return 0;
    }
    for (int i = 0; i < frames * int(m->state->channels); ++i) {
        // Retain finite decoder overshoot, but reject corrupt PCM before it poisons filter state.
        if (!std::isfinite(samples[i]) || std::abs(samples[i]) > 32.f) {
            m->invalid = true; return 0;
        }
    }
    std::lock_guard<std::mutex> lock(library_mutex);
    if (ebur128_add_frames_float(m->state, samples, frames) != EBUR128_SUCCESS) {
        m->invalid = true; return 0;
    }
    m->frames += frames;
    return 1;
}
extern "C" int lj_loudness_finish(lj_loudness *m, double *lufs, double *peak) {
    if (!m || !lufs || !peak || m->invalid || m->finished) return 0;
    m->finished = true;
    if (m->frames < (m->state->samplerate + 5) / 10 * 4) return 0;
    std::lock_guard<std::mutex> lock(library_mutex);
    double integrated;
    if (ebur128_loudness_global(m->state, &integrated) != EBUR128_SUCCESS ||
        !std::isfinite(integrated)) return 0;
    // Flush the true-peak FIR tail AFTER taking integrated loudness. These padding frames must
    // not influence the reported programme loudness or the caller's complete-decode count.
    float zeros[64 * 2] = {};
    if (ebur128_add_frames_float(m->state, zeros, 64) != EBUR128_SUCCESS) return 0;
    double maximum = 0;
    for (unsigned c = 0; c < m->state->channels; ++c) {
        double value;
        if (ebur128_true_peak(m->state, c, &value) != EBUR128_SUCCESS) return 0;
        maximum = std::max(maximum, value);
    }
    if (!(maximum > 0) || !std::isfinite(maximum)) return 0;
    *lufs = integrated; *peak = 20 * std::log10(maximum);
    return 1;
}
extern "C" void lj_loudness_destroy(lj_loudness *m) {
    if (!m) return;
    std::lock_guard<std::mutex> lock(library_mutex);
    ebur128_destroy(&m->state);
    delete m;
}
