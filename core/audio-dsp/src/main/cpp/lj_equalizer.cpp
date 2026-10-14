/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#include "lj_equalizer.h"
#include "vendor/signalsmith/filters.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <new>
#include <vector>

namespace {
constexpr int kBands = 10, kFilters = 11, kMaxChannels = 32;
constexpr int kFrequencies[kBands] = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
using Filter = signalsmith::filters::BiquadStatic<double>;
using Design = signalsmith::filters::BiquadDesign;
static_assert(std::atomic<int>::is_always_lock_free, "Settings publication must be lock-free");
static_assert(std::atomic<float>::is_always_lock_free, "Meter publication must be lock-free");

struct ScopedDenormals {
#if defined(__aarch64__)
    uint64_t previous;
    ScopedDenormals() { asm volatile("mrs %0, fpcr" : "=r"(previous)); const uint64_t value = previous | (1ULL << 24); asm volatile("msr fpcr, %0" : : "r"(value)); }
    ~ScopedDenormals() { asm volatile("msr fpcr, %0" : : "r"(previous)); }
#elif defined(__SSE__)
    unsigned previous;
    ScopedDenormals() : previous(_mm_getcsr()) { _mm_setcsr(previous | 0x8040); }
    ~ScopedDenormals() { _mm_setcsr(previous); }
#endif
};

struct Config {
    std::array<Filter, kFilters> filters{};
    int count = 0;
    double headroom = 0, gain = 1;
    Config() = default;
    Config(int rate, int enabled, const int32_t *levels, int bass) {
        if (!enabled) return;
        double boost = 0;
        for (int i = 0; i < kBands; ++i) {
            if (kFrequencies[i] >= rate * 0.45) continue;
            const double db = std::clamp(levels[i], -1200, 1200) / 100.0;
            boost = std::max(boost, db);
            if (db != 0) filters[count++].peakDbQ(double(kFrequencies[i]) / rate, db, 1.4, Design::cookbook);
        }
        const double bassDb = std::clamp(bass, 0, 1000) * 0.009;
        if (bassDb > 0) {
            filters[count++].lowShelfDbQ(std::min(100.0, rate * 0.1) / rate, bassDb, std::sqrt(0.5), Design::cookbook);
            boost = std::max(boost, bassDb);
        }
        headroom = std::clamp(boost, 0.0, 2.0);
        gain = std::pow(10.0, -headroom / 20.0);
    }
    double response(double frequency, int rate) const {
        double result = -headroom;
        for (int i = 0; i < count; ++i) result += filters[i].responseDb(frequency / rate);
        return result;
    }
};

struct Bank {
    std::array<std::array<Filter, kFilters>, kMaxChannels> channels{};
    int count = 0;
    double gain = 1;
    void configure(const Config &config, int channelCount) {
        count = config.count; gain = config.gain;
        for (int c = 0; c < channelCount; ++c) for (int i = 0; i < count; ++i) {
            channels[c][i].copyFrom(config.filters[i]);
            channels[c][i].reset();
        }
    }
    double sample(double input, int channel) {
        double output = input * gain;
        for (int i = 0; i < count; ++i) output = channels[channel][i](output);
        return output;
    }
};

/* Fixed-size triple buffer. Dirty is part of the exchanged index, so a newer publication
 * cannot be lost between the consumer's observation and exchange. No mutex/allocation in render.
 */
struct ConfigMailbox {
    std::array<Config, 3> slots{};
    std::atomic<int> middle{1};
    int producer = 2, consumer = 0;
    void publish(const Config &value) {
        slots[producer] = value;
        producer = middle.exchange(producer | 4, std::memory_order_acq_rel) & 3;
    }
    bool consume() {
        if (!(middle.load(std::memory_order_acquire) & 4)) return false;
        consumer = middle.exchange(consumer, std::memory_order_acq_rel) & 3;
        return true;
    }
    const Config &current() const { return slots[consumer]; }
};

struct Limiter {
    int channels, latency, window, hold;
    std::vector<float> delayed;
    std::vector<double> gains, peaks;
    std::vector<uint64_t> frames;
    int head = 0, count = 0, position = 0;
    uint64_t frameNumber = 0;
    double sum, envelope = 1, release, currentGain = 1;
    Limiter(int rate, int channelCount) : channels(channelCount), latency(std::max(1, rate / 200)),
        window(latency + 1), hold(std::max(latency, rate / 50)), delayed(window * channels),
        gains(window, 1), peaks(hold + 2), frames(hold + 2), sum(window), release(std::exp(-1.0 / (rate * 0.1))) {}
    void reset() {
        std::fill(delayed.begin(), delayed.end(), 0); std::fill(gains.begin(), gains.end(), 1);
        head = count = position = 0; frameNumber = 0; sum = window; envelope = currentGain = 1;
    }
    void process(float *frame, double ceiling) {
        double peak = 0;
        for (int c = 0; c < channels; ++c) {
            const float sample = std::isfinite(frame[c]) ? frame[c] : 0;
            delayed[position * channels + c] = sample;
            peak = std::max(peak, std::abs(double(sample)));
        }
        peak /= ceiling;
        const int size = int(peaks.size());
        while (count && frameNumber > uint64_t(hold) && frames[head] < frameNumber - hold) { head = (head + 1) % size; --count; }
        while (count && peaks[(head + count - 1) % size] <= peak) --count;
        const int tail = (head + count) % size;
        peaks[tail] = peak; frames[tail] = frameNumber++; ++count;
        const double required = 1.0 / std::max(1.0, peaks[head]);
        envelope = std::min(required, 1.0 - (1.0 - envelope) * release);
        if (envelope > 0.999999 && required == 1) envelope = 1;
        sum += envelope - gains[position]; gains[position] = envelope;
        currentGain = std::clamp(sum / window, 0.0, 1.0);
        const int oldest = (position + 1) % window;
        for (int c = 0; c < channels; ++c) frame[c] = float(std::clamp(delayed[oldest * channels + c] * currentGain, -1.0, 1.0));
        position = oldest;
    }
};
uint64_t nowNanos() { return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count()); }
int16_t quantize(float sample) {
    return int16_t(std::clamp(std::floor(double(sample * 32768.0f) + 0.5), -32768.0, 32767.0));
}
}

struct lj_equalizer {
    int rate, channels, transitionFrames, transitionPosition = 0, active = 0;
    bool transitioning = false;
    ConfigMailbox mailbox;
    std::array<Bank, 2> banks;
    Limiter limiter;
    int priming, pending = 0;
    std::atomic<float> meterGain{1};
    std::atomic<uint64_t> lastRender{0};
    lj_equalizer(int sampleRate, int channelCount) : rate(sampleRate), channels(channelCount),
        transitionFrames(std::max(1, rate / 50)), limiter(rate, channels), priming(limiter.latency) {}
    void beginBlock() {
        if (!transitioning && mailbox.consume()) {
            banks[1 - active].configure(mailbox.current(), channels);
            transitionPosition = 0; transitioning = true;
        }
    }
    void frame(float *samples) {
        const double mix = transitioning ? double(transitionPosition + 1) / transitionFrames : 0;
        for (int c = 0; c < channels; ++c) {
            const double input = std::isfinite(samples[c]) ? double(samples[c]) : 0;
            const double old = banks[active].sample(input, c);
            samples[c] = float(transitioning ? old + mix * (banks[1 - active].sample(input, c) - old) : old);
        }
        if (transitioning && ++transitionPosition >= transitionFrames) { active = 1 - active; transitioning = false; }
        limiter.process(samples, banks[active].count == 0 && !transitioning ? 1.0 : double(0.9440609f));
    }
    void report(double gain) {
        float value = float(gain), old = meterGain.load(std::memory_order_relaxed);
        while (value < old && !meterGain.compare_exchange_weak(old, value, std::memory_order_relaxed)) {}
        lastRender.store(nowNanos(), std::memory_order_release);
    }
};

extern "C" {
lj_equalizer *lj_eq_create(int rate, int channels) {
    if (rate <= 0 || rate > 768000 || channels <= 0 || channels > kMaxChannels) return nullptr;
    try { return new lj_equalizer(rate, channels); } catch (...) { return nullptr; }
}
void lj_eq_destroy(lj_equalizer *eq) { delete eq; }
void lj_eq_set_settings(lj_equalizer *eq, int enabled, const int32_t *levels, int bass) {
    if (eq && levels) eq->mailbox.publish(Config(eq->rate, enabled, levels, bass));
}
void lj_eq_reset(lj_equalizer *eq) {
    if (!eq) return;
    eq->mailbox.consume(); eq->banks[0].configure(eq->mailbox.current(), eq->channels);
    eq->active = 0; eq->transitioning = false; eq->transitionPosition = 0;
    eq->limiter.reset(); eq->priming = eq->limiter.latency; eq->pending = 0;
    eq->meterGain.store(1); eq->lastRender.store(0);
}
int lj_eq_latency_frames(const lj_equalizer *eq) { return eq ? eq->limiter.latency : 0; }
void lj_eq_process_f32(lj_equalizer *eq, float *data, int count) {
    if (!eq || !data || count <= 0) return;
    ScopedDenormals denormals; eq->beginBlock(); double minimum = 1;
    for (int i = 0; i < count; ++i) { eq->frame(data + i * eq->channels); minimum = std::min(minimum, eq->limiter.currentGain); }
    eq->report(minimum);
}
void lj_eq_process_planar(lj_equalizer *eq, float *const *data, int count) {
    if (!eq || !data || count <= 0) return;
    ScopedDenormals denormals; eq->beginBlock(); float frame[kMaxChannels]; double minimum = 1;
    for (int i = 0; i < count; ++i) {
        for (int c = 0; c < eq->channels; ++c) frame[c] = data[c][i];
        eq->frame(frame); minimum = std::min(minimum, eq->limiter.currentGain);
        for (int c = 0; c < eq->channels; ++c) data[c][i] = frame[c];
    }
    eq->report(minimum);
}
int lj_eq_render_s16(lj_equalizer *eq, const void *input, void *output, int count) {
    if (!eq || !input || !output || count <= 0) return 0;
    ScopedDenormals denormals; eq->beginBlock(); float frame[kMaxChannels]; double minimum = 1; int written = 0;
    const auto *in = static_cast<const unsigned char *>(input); auto *out = static_cast<unsigned char *>(output);
    for (int i = 0; i < count; ++i) {
        for (int c = 0; c < eq->channels; ++c) { int16_t sample; std::memcpy(&sample, in + (i * eq->channels + c) * 2, 2); frame[c] = sample / 32768.0f; }
        eq->frame(frame); minimum = std::min(minimum, eq->limiter.currentGain); ++eq->pending;
        if (eq->priming > 0) --eq->priming;
        else {
            for (int c = 0; c < eq->channels; ++c) { int16_t sample = quantize(frame[c]); std::memcpy(out + (written * eq->channels + c) * 2, &sample, 2); }
            ++written; --eq->pending;
        }
    }
    eq->report(minimum); return written;
}
int lj_eq_pending_frames(const lj_equalizer *eq) { return eq ? eq->pending : 0; }
int lj_eq_drain_s16(lj_equalizer *eq, void *output, int capacity) {
    if (!eq || !output || capacity <= 0) return 0;
    ScopedDenormals denormals; float frame[kMaxChannels]; double minimum = 1; int written = 0;
    auto *out = static_cast<unsigned char *>(output);
    while (eq->pending && written < capacity) {
        std::fill(frame, frame + eq->channels, 0); eq->frame(frame); minimum = std::min(minimum, eq->limiter.currentGain);
        if (eq->priming > 0) --eq->priming;
        else {
            for (int c = 0; c < eq->channels; ++c) { int16_t sample = quantize(frame[c]); std::memcpy(out + (written * eq->channels + c) * 2, &sample, 2); }
            ++written; --eq->pending;
        }
    }
    if (written) eq->report(minimum); return written;
}
float lj_eq_take_meter_gain(lj_equalizer *eq) {
    if (!eq) return 1;
    const float value = eq->meterGain.exchange(1, std::memory_order_relaxed);
    const uint64_t last = eq->lastRender.load(std::memory_order_acquire);
    return last && nowNanos() - last <= 500000000 ? value : 1;
}
float lj_eq_frequency_response(int rate, int enabled, const int32_t *levels, int bass, const float *frequencies, float *out, int count) {
    if (rate <= 0 || !levels || !frequencies || !out || count < 0) return 0;
    const Config config(rate, enabled, levels, bass);
    for (int i = 0; i < count; ++i) out[i] = float(config.response(frequencies[i], rate));
    return float(config.headroom);
}
}
