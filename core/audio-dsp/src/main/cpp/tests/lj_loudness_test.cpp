// Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0
#include "lj_loudness.h"
#include <cassert>
#include <cmath>
#include <limits>
#include <thread>
#include <vector>

static void scan() {
    for (int iteration = 0; iteration < 12; ++iteration) {
        auto *m = lj_loudness_create(48000, 2);
        assert(m);
        float samples[960];
        for (int i = 0; i < 480; ++i) {
            samples[2*i] = float(0.1 * std::sin(2 * 3.141592653589793 * 1000 * i / 48000));
            samples[2*i+1] = -samples[2*i];
        }
        for (int i = 0; i < 200; ++i) assert(lj_loudness_add(m, samples, 480));
        double lufs, peak;
        assert(lj_loudness_finish(m, &lufs, &peak));
        assert(std::abs(lufs + 20) < 0.15 && std::abs(peak + 20) < 0.15);
        assert(!lj_loudness_finish(m, &lufs, &peak));
        lj_loudness_destroy(m);
    }
}
int main() {
    assert(!lj_loudness_create(0, 2));
    assert(!lj_loudness_create(192001, 2));
    assert(!lj_loudness_create(48000, 6));
    auto *m = lj_loudness_create(48000, 1);
    float invalid = std::numeric_limits<float>::quiet_NaN();
    assert(!lj_loudness_add(m, &invalid, 1));
    double lufs, peak;
    assert(!lj_loudness_finish(m, &lufs, &peak));
    lj_loudness_destroy(m);
    // Exercise simultaneous construction and reading of upstream's shared histogram constants.
    std::vector<std::thread> threads;
    for (int i = 0; i < 4; ++i) threads.emplace_back(scan);
    for (auto &t : threads) t.join();
}
