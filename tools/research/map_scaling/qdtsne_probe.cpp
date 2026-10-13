// Copyright (c) 2026 LatentJam Project
// SPDX-License-Identifier: Apache-2.0
// Research executable only; not linked into the app. Reads synthetic/research f32 matrices.
#include "qdtsne/qdtsne.hpp"
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <sys/resource.h>

int main(int argc, char** argv) {
    if (argc != 6 && argc != 7) {
        std::fprintf(stderr, "usage: qdtsne_probe rows dimensions input.f32 output.f32 theta [eta=800]\n");
        return 2;
    }
    const int n = std::stoi(argv[1]);
    const int dim = std::stoi(argv[2]);
    if (n < 61 || n > 20000 || dim < 1 || dim > 1344) return 2;
    setpriority(PRIO_PROCESS, 0, 10);
    std::vector<float> rows(static_cast<std::size_t>(n) * dim);
    std::ifstream input(argv[3], std::ios::binary);
    input.read(reinterpret_cast<char*>(rows.data()), rows.size() * sizeof(float));
    if (!input || input.peek() != std::char_traits<char>::eof()) throw std::runtime_error("bad input size");
    // Match the application's cold initialization, seed and perplexity.
    std::vector<float> y(n * 2);
    std::uint64_t state = 0x1A7E27 | 1;
    for (auto& v : y) {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        v = (static_cast<float>(static_cast<double>(state >> 11) / (1ULL << 53)) * 2.f - 1.f) * 1e-2f;
    }
    qdtsne::Options options;
    options.perplexity = 20;
    options.theta = std::stod(argv[5]);
    if (!std::isfinite(options.theta) || options.theta < 0 || options.theta > 1) return 2;
    options.max_iterations = 1000;
    options.early_exaggeration_iterations = 250;
    options.momentum_switch_iterations = 250;
    // Kotlin's gradient includes the derivative's factor of 4; qdtsne's gradient omits it.
    // eta=800 here matches the effective step scale of Kotlin's learning rate 200.
    options.eta = argc == 7 ? std::stod(argv[6]) : 800;
    if (!std::isfinite(options.eta) || options.eta <= 0) return 2;
    options.num_threads = 1;
    options.leaf_approximation = false;
    knncolle::VptreeBuilder<int, float, float> neighbors(std::make_shared<knncolle::EuclideanDistance<float, float>>());
    const auto start = std::chrono::steady_clock::now();
    auto status = qdtsne::initialize<2>(dim, n, rows.data(), neighbors, options);
    const auto prepared = std::chrono::steady_clock::now();
    double longest = 0;
    for (int iteration = 1; iteration <= 1000; ++iteration) {
        const auto tick = std::chrono::steady_clock::now();
        status.run(y.data(), iteration);
        longest = std::max(longest, std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tick).count());
    }
    const auto end = std::chrono::steady_clock::now();
    for (float value : y) if (!std::isfinite(value)) throw std::runtime_error("non-finite embedding");
    std::ofstream output(argv[4], std::ios::binary);
    output.write(reinterpret_cast<const char*>(y.data()), y.size() * sizeof(float));
    output.close();
    if (!output) throw std::runtime_error("could not write output");
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
#ifdef __APPLE__
    const long maxrss_bytes = usage.ru_maxrss;
#else
    const long maxrss_bytes = usage.ru_maxrss * 1024L;
#endif
    std::printf("LJ_MAP_BH n=%d dim=%d theta=%.3f eta=%.1f affinity_ms=%.3f optimizer_ms=%.3f total_ms=%.3f max_iteration_ms=%.3f maxrss_bytes=%ld threads=1 iterations=1000\n",
        n, dim, options.theta, options.eta,
        std::chrono::duration<double, std::milli>(prepared - start).count(),
        std::chrono::duration<double, std::milli>(end - prepared).count(),
        std::chrono::duration<double, std::milli>(end - start).count(), longest, maxrss_bytes);
}
