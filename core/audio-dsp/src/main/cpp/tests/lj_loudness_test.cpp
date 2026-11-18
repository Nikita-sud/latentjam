// Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0
#include "lj_loudness.h"
#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <cmath>
#include <limits>
#include <thread>
#include <vector>

// Do not compile checks or the calls being checked out of Release builds.
#define REQUIRE(condition) do { if (!(condition)) { std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); std::abort(); } } while (0)

static void feed(lj_loudness *meter, int rate, int frames, float amplitude = 0.1f) {
    std::vector<float> chunk(4096);
    for (int offset = 0; offset < frames; offset += 4096) {
        const int count = std::min(4096, frames - offset);
        for (int i = 0; i < count; ++i) chunk[i] = amplitude * std::sin(2 * 3.141592653589793 * 1000 * (i + offset) / rate);
        REQUIRE(lj_loudness_add(meter, chunk.data(), count));
    }
}

static void boundaries() {
    for (int rate : {8000, 44100, 48000, 96000, 192000}) {
        auto *meter = lj_loudness_create(rate, 1); REQUIRE(meter);
        feed(meter, rate, rate);
        double lufs = 0, peak = 0;
        REQUIRE(lj_loudness_finish(meter, &lufs, &peak));
        REQUIRE(std::isfinite(lufs) && lufs > -25 && lufs < -18);
        REQUIRE(std::abs(peak + 20) < 0.5);
        lj_loudness_destroy(meter);
    }
    double lufs = 0, peak = 0;
    for (int frames : {3199, 3200}) {
        auto *meter = lj_loudness_create(8000, 1);
        feed(meter, 8000, frames);
        REQUIRE(lj_loudness_finish(meter, &lufs, &peak) == (frames == 3200));
        lj_loudness_destroy(meter);
    }
    auto *silent = lj_loudness_create(48000, 1);
    feed(silent, 48000, 48000, 0);
    REQUIRE(!lj_loudness_finish(silent, &lufs, &peak));
    lj_loudness_destroy(silent);
    for (int frames : {4096, 4097}) {
        auto *meter = lj_loudness_create(48000, 1);
        std::vector<float> input(frames, 0.1f);
        REQUIRE(lj_loudness_add(meter, input.data(), frames) == (frames == 4096));
        REQUIRE(!lj_loudness_finish(meter, &lufs, &peak));
        lj_loudness_destroy(meter);
    }
    for (float invalid : {32.01f, -32.01f, std::numeric_limits<float>::infinity()}) {
        auto *meter = lj_loudness_create(48000, 1);
        REQUIRE(!lj_loudness_add(meter, &invalid, 1));
        REQUIRE(!lj_loudness_finish(meter, &lufs, &peak));
        lj_loudness_destroy(meter);
    }
}

static void scan() {
    for (int iteration = 0; iteration < 12; ++iteration) {
        auto *m = lj_loudness_create(48000, 2);
        REQUIRE(m);
        float samples[960];
        for (int i = 0; i < 480; ++i) {
            samples[2*i] = float(0.1 * std::sin(2 * 3.141592653589793 * 1000 * i / 48000));
            samples[2*i+1] = -samples[2*i];
        }
        for (int i = 0; i < 200; ++i) REQUIRE(lj_loudness_add(m, samples, 480));
        double lufs, peak;
        REQUIRE(lj_loudness_finish(m, &lufs, &peak));
        REQUIRE(std::abs(lufs + 20) < 0.15 && std::abs(peak + 20) < 0.15);
        REQUIRE(!lj_loudness_finish(m, &lufs, &peak));
        lj_loudness_destroy(m);
    }
}
// A one-channel file plays from both speakers, each at the file's level: it is as loud as the same samples in two
// equal channels, and must measure so (BS.1770 sums the channels' powers, so one channel counted once reads
// 10*log10(2) = 3.01 LU lower). Its peak is one channel's either way.
static double measured(int channels, double *peak_out) {
    auto *meter = lj_loudness_create(48000, channels); REQUIRE(meter);
    std::vector<float> chunk(4096 * channels);
    for (int offset = 0; offset < 48000 * 5; offset += 4096) {
        const int count = std::min(4096, 48000 * 5 - offset);
        for (int i = 0; i < count; ++i) {
            // Two levels, so the relative gate has something to drop: the same blocks must go in both layouts.
            const double amplitude = (offset + i) < 48000 * 2 ? 0.01 : 0.2;
            const float sample = float(amplitude * std::sin(2 * 3.141592653589793 * 1000 * (i + offset) / 48000));
            for (int c = 0; c < channels; ++c) chunk[i * channels + c] = sample;
        }
        REQUIRE(lj_loudness_add(meter, chunk.data(), count));
    }
    double lufs = 0;
    REQUIRE(lj_loudness_finish(meter, &lufs, peak_out));
    lj_loudness_destroy(meter);
    return lufs;
}
static void mono_is_as_loud_as_it_plays() {
    double mono_peak = 0, stereo_peak = 0;
    const double mono = measured(1, &mono_peak), stereo = measured(2, &stereo_peak);
    // The histogram's bins are 0.1 LU wide.
    REQUIRE(std::abs(mono - stereo) < 0.11);
    REQUIRE(std::abs(mono_peak - stereo_peak) < 0.01);
    // A 1 kHz sine at 0.2 in both channels: 10*log10(2 * 0.2^2 / 2) = -13.98 LUFS (at 1 kHz the K-weighting
    // gain and the -0.691 offset cancel), the quiet two seconds gated out. The blocks that straddle the change of
    // level pass the gate and pull it down a little, hence the wider bound.
    REQUIRE(std::abs(stereo + 13.98) < 0.3);
}
int main() {
    boundaries();
    mono_is_as_loud_as_it_plays();
    REQUIRE(!lj_loudness_create(0, 2));
    REQUIRE(!lj_loudness_create(192001, 2));
    REQUIRE(!lj_loudness_create(48000, 6));
    auto *m = lj_loudness_create(48000, 1);
    float invalid = std::numeric_limits<float>::quiet_NaN();
    REQUIRE(!lj_loudness_add(m, &invalid, 1));
    double lufs, peak;
    REQUIRE(!lj_loudness_finish(m, &lufs, &peak));
    lj_loudness_destroy(m);
    // Exercise simultaneous construction and reading of upstream's shared histogram constants.
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i) threads.emplace_back(scan);
    for (auto &t : threads) t.join();
}
