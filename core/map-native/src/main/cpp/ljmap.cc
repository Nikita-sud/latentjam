// SPDX-License-Identifier: Apache-2.0
#include "ljmap.h"
#include "qdtsne/qdtsne.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

extern "C" int LjMapEmbed(const float* rows, int n, int dim, const float* initial,
                         float* output, LjMapIsActive active, void* context) {
    if (!rows || !output || n < 61 || n > 3000 || dim < 1 || dim > 50) return 2;
    auto cancelled = [&]() { return active && !active(context); };
    if (cancelled()) return 1;
    for (int i = 0; i < n * dim; ++i) if (!std::isfinite(rows[i])) return 2;
    if (initial) for (int i = 0; i < n * 2; ++i) if (!std::isfinite(initial[i])) return 2;
    try {
        qdtsne::NeighborList<int, float> nearest(n);
        {
            knncolle::VptreeBuilder<int, float, float> builder(
                std::make_shared<knncolle::EuclideanDistance<float, float>>());
            auto index = builder.build_unique(knncolle::SimpleMatrix<int, float>(dim, n, rows));
            auto search = index->initialize();
            std::vector<int> indices;
            std::vector<float> distances;
            for (int i = 0; i < n; ++i) {
                if (cancelled()) return 1;
                search->search(i, 60, &indices, &distances);
                auto& neighbors = nearest[i];
                neighbors.reserve(60);
                for (std::size_t k = 0; k < indices.size(); ++k) {
                    neighbors.emplace_back(indices[k], distances[k]);
                }
            }
        } // Free the VP-tree and its data copy before optimizer allocation.
        if (cancelled()) return 1;
        qdtsne::Options options;
        options.perplexity = 20;
        options.theta = 0.5;
        options.max_iterations = 1000;
        options.eta = 800;
        options.num_threads = 1;
        options.leaf_approximation = false;
        options.early_exaggeration_iterations = 250;
        options.momentum_switch_iterations = 250;
        auto status = qdtsne::initialize<2>(std::move(nearest), options);
        std::vector<float> y(n * 2);
        std::uint64_t seed = 0x1A7E27 | 1;
        for (int i = 0; i < n * 2; ++i) {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            const float random = static_cast<float>(static_cast<double>(seed >> 11) / (1ULL << 53)) * 2.f - 1.f;
            y[i] = initial ? initial[i] + random * 1e-3f : random * 1e-2f;
        }
        for (int iteration = 1; iteration <= 1000; ++iteration) {
            if (cancelled()) return 1;
            status.run(y.data(), iteration);
        }
        if (cancelled()) return 1;
        for (float value : y) if (!std::isfinite(value)) return 3;
        std::copy(y.begin(), y.end(), output);
        return 0;
    } catch (...) {
        return 3; // Includes bad_alloc. Every container owns and releases its memory.
    }
}

