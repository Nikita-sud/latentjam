/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
#include "ljdots.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <random>
#include <vector>
#pragma clang fp contract(off)

static void Portable(const float* m, int dim, const float* q, const int32_t* rows, int count, float* out) {
  for (int r = 0; r < count; ++r) {
    float sum = 0;
    for (int d = 0; d < dim; ++d) sum += m[rows[r] * dim + d] * q[d];
    out[rows[r]] = sum;
  }
}
static uint32_t Bits(float x) { uint32_t bits; memcpy(&bits, &x, 4); return bits; }
static bool Same(float a, float b) { return Bits(a) == Bits(b) || (std::isnan(a) && std::isnan(b)); }

int main(int argc, char**) {
  std::mt19937 random(71236);
  std::uniform_real_distribution<float> values(-1, 1);
  size_t checked = 0;
  for (int dim : {1, 3, 7, 16, 17, 33, 384, 768, 960}) {
    const int n = 103;
    std::vector<float> matrix(n * dim), query(dim + 7);
    for (float& v : matrix) v = values(random);
    for (float& v : query) v = values(random);
    std::vector<int32_t> rows(n);
    for (int i = 0; i < n; ++i) rows[i] = i;
    std::shuffle(rows.begin(), rows.end(), random);
    for (int count : {0, 1, 7, 8, 9, 15, 16, 17, 101, 103}) {
      for (int variant = 0; variant < 3; ++variant) {
        // Variant 1 aliases query into matrix; variant 2 covers infinities, NaNs and vector/scalar tails.
        auto testMatrix = matrix, testQuery = query;
        const float* q = variant == 1 ? testMatrix.data() + dim : testQuery.data() + 7;
        if (variant == 2) {
          testQuery.back() = std::numeric_limits<float>::infinity();
          testMatrix[rows[0] * dim + dim - 1] = 0.f;
          testMatrix[rows[7] * dim + dim - 1] = -std::numeric_limits<float>::infinity();
        }
        std::vector<float> expected(n, -1234.5f), actual = expected;
        Portable(testMatrix.data(), dim, q, rows.data(), count, expected.data());
        if (!LjBatchDots(testMatrix.data(), dim, q, rows.data(), count, actual.data())) {
          if (actual != std::vector<float>(n, -1234.5f)) return 2;
          puts("LjBatchDots unsupported on this architecture; output preserved");
          return 0;
        }
        for (int i = 0; i < n; ++i) {
          if (!Same(expected[i], actual[i])) {
            fprintf(stderr, "Mismatch dim=%d count=%d variant=%d row=%d expected=%08x got=%08x\n",
                    dim, count, variant, i, Bits(expected[i]), Bits(actual[i]));
            return 1;
          }
          ++checked;
        }
      }
    }
  }
  printf("LjBatchDots parity passed: %zu outputs (finite bits exact; nonfinite classes equal)\n", checked);
  if (argc < 2) return 0;
  for (int n : {3000, 10000}) {
    constexpr int dim = 960;
    std::vector<float> matrix(n * dim), query(dim), output(n);
    std::vector<int32_t> rows(n);
    for (float& v : matrix) v = values(random);
    for (float& v : query) v = values(random);
    for (int r = 0; r < n; ++r) rows[r] = r;
    for (int implementation = 0; implementation < 2; ++implementation) {
      std::vector<double> times;
      double first = 0;
      volatile float consume = 0;
      for (int run = 0; run < 36; ++run) {
        const auto start = std::chrono::steady_clock::now();
        if (implementation) LjBatchDots(matrix.data(), dim, query.data(), rows.data(), n, output.data());
        else Portable(matrix.data(), dim, query.data(), rows.data(), n, output.data());
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        if (run == 0) first = ms;
        if (run >= 5) times.push_back(ms);
        consume = consume + output[run % n];
      }
      std::sort(times.begin(), times.end());
      printf("LjBatchDots bench rows=%d kernel=%s first_ms=%.3f median_ms=%.3f checksum=%.8g\n",
             n, implementation ? "AoS8" : "scalar", first, times[times.size()/2], float(consume));
    }
  }
}
