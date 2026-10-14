// SPDX-License-Identifier: Apache-2.0
#include "ljmap.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>
struct Budget { int calls = 0; int limit; };
int active(void* context) { auto& b = *static_cast<Budget*>(context); return b.calls++ < b.limit; }
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "failed line %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main() {
    constexpr int n = 61, dim = 7;
    std::vector<float> rows(n * dim), a(n * 2, 123), b(n * 2);
    for (int i = 0; i < n * dim; ++i) rows[i] = (i * 19 % 23) / 23.f;
    CHECK(LjMapEmbed(rows.data(), 60, dim, nullptr, a.data(), nullptr, nullptr) == 2);
    CHECK(LjMapEmbed(rows.data(), 3001, dim, nullptr, a.data(), nullptr, nullptr) == 2);
    rows[0] = std::numeric_limits<float>::quiet_NaN();
    CHECK(LjMapEmbed(rows.data(), n, dim, nullptr, a.data(), nullptr, nullptr) == 2);
    rows[0] = 0;
    for (int limit : {0, 1, 30, n + 1, n + 20}) {
        Budget budget{0, limit};
        CHECK(LjMapEmbed(rows.data(), n, dim, nullptr, a.data(), active, &budget) == 1);
        CHECK(std::all_of(a.begin(), a.end(), [](float x) { return x == 123; }));
    }
    CHECK(LjMapEmbed(rows.data(), n, dim, nullptr, a.data(), nullptr, nullptr) == 0);
    CHECK(LjMapEmbed(rows.data(), n, dim, nullptr, b.data(), nullptr, nullptr) == 0);
    CHECK(a == b);
    CHECK(std::all_of(a.begin(), a.end(), [](float x) { return std::isfinite(x); }));
    CHECK(LjMapEmbed(rows.data(), n, dim, a.data(), b.data(), nullptr, nullptr) == 0);
    std::fill(rows.begin(), rows.end(), 0);
    CHECK(LjMapEmbed(rows.data(), n, dim, nullptr, a.data(), nullptr, nullptr) == 0);
    return 0;
}

