/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
// Q4Conv1x1's arithmetic on the host, without ONNX Runtime: every kernel path this CPU can run, both weight
// formats and ragged shapes against exact sums, and the dot tiles where their 16-bit lanes come closest to
// overflowing. Build and run with the test target of ../../main/cpp/CMakeLists.txt (-DLJQ4_TESTS=ON).
#include "ljq4.cc"

#include <cstdio>
#include <random>

namespace {

int failures = 0;

void Expect(bool ok, const char* what) {
  if (!ok) {
    ++failures;
    fprintf(stderr, "FAIL %s\n", what);
  }
}

const char* Name(Path path) {
  return path == Path::kI8mm ? "i8mm" : path == Path::kDotprod ? "dotprod" : "portable";
}

// A random layer in the model's layout, its input rows, and its outputs computed exactly.
struct Layer {
  size_t block, m, k, n;
  std::vector<int8_t> q;
  std::vector<float> scale, bias;
  std::vector<uint8_t> w, x;
  float xs = 0.02f, ys = 0.f;
  uint8_t xz = 37;
  int32_t yz = 120;
  std::vector<double> exact;
};

Layer RandomLayer(size_t block, size_t m, size_t k, size_t n, std::mt19937& rng) {
  Layer layer{block, m, k, n};
  const size_t groups = block == 0 ? 1 : RoundUp(k, kGroup) / kGroup, span = block == 0 ? k : kGroup;
  layer.q.resize(n * k);
  for (auto& v : layer.q) v = static_cast<int8_t>(static_cast<int>(rng() % 16) - 8);
  std::uniform_real_distribution<float> scaleDistribution(0.001f, 0.021f);
  std::normal_distribution<float> biasDistribution(0.f, 0.5f);
  layer.scale.resize(n * groups);
  layer.bias.resize(n);
  for (auto& s : layer.scale) s = block ? Bf16ToFloat(FloatToBf16(scaleDistribution(rng))) : scaleDistribution(rng);
  for (auto& b : layer.bias) b = biasDistribution(rng);
  layer.w.resize(WeightBytes(n, k, block));
  PackWeights(n, k, block, kModelKr, layer.q.data(), layer.scale.data(), layer.bias.data(), layer.w.data());
  layer.x.resize(m * k);
  for (auto& v : layer.x) v = static_cast<uint8_t>(rng() & 0xFF);
  layer.exact.resize(m * n);
  double peak = 0;
  for (size_t r = 0; r < m; ++r) {
    for (size_t o = 0; o < n; ++o) {
      double sum = 0;
      for (size_t g = 0; g < groups; ++g) {
        int64_t acc = 0;
        for (size_t c = g * span; c < std::min(k, (g + 1) * span); ++c) {
          acc += (layer.x[r * k + c] - layer.xz) * layer.q[o * k + c];
        }
        sum += static_cast<double>(layer.scale[o * groups + g]) * static_cast<double>(acc);
      }
      layer.exact[r * n + o] = layer.xs * sum + layer.bias[o];
      peak = std::max(peak, std::fabs(layer.exact[r * n + o]));
    }
  }
  layer.ys = static_cast<float>(peak / 100);
  return layer;
}

std::vector<uint8_t> Run(Kernel& kernel, const Layer& layer) {
  std::vector<uint8_t> y(layer.m * layer.n, 0);
  kernel.Multiply(layer.x.data(), layer.m, layer.k, layer.xs, layer.xz, layer.w.data(), layer.ys, layer.yz, y.data());
  return y;
}

// One layer through one path, twice (the second call reuses decoded or repacked weights). An output may
// miss the exact value by one step only where the exact value sits at half a step.
void CheckLayer(Path path, size_t block, size_t m, size_t k, size_t n, std::mt19937& rng) {
  const Layer layer = RandomLayer(block, m, k, n, rng);
  const std::vector<double>& exact = layer.exact;
  const float ys = layer.ys;
  const int32_t yz = layer.yz;
  Kernel kernel{n, block, path};
  for (int call = 0; call < 2; ++call) {
    const std::vector<uint8_t> y = Run(kernel, layer);
    size_t steps = 0, wrong = 0;
    for (size_t i = 0; i < m * n; ++i) {
      const double unrounded = exact[i] / ys + yz;
      const int expected = static_cast<int>(std::min(255.0, std::max(0.0, std::nearbyint(unrounded))));
      const int difference = std::abs(static_cast<int>(y[i]) - expected);
      if (difference == 0) continue;
      const bool half = std::fabs(unrounded - std::floor(unrounded) - 0.5) < 2e-3;
      if (difference == 1 && half) ++steps;
      else ++wrong;
    }
    char what[160];
    snprintf(what, sizeof(what), "%-8s block %2zu M=%4zu K=%4zu N=%4zu call %d: %zu wrong, %zu half-step", Name(path),
             block, m, k, n, call, wrong, steps);
    if (call == 0) printf("%s\n", what);
    Expect(wrong == 0, what);
  }
}

// Every path this CPU has writes the same bytes as the first, values that sit half a step between two outputs
// included: the portable code repeats KleidiAI's float steps. Layers of the encoder's size, where such values
// occur.
void CheckPathsAgree(const std::vector<Path>& paths, size_t block, size_t m, size_t k, size_t n, std::mt19937& rng) {
  const Layer layer = RandomLayer(block, m, k, n, rng);
  Kernel first{n, block, paths[0]};
  const std::vector<uint8_t> reference = Run(first, layer);
  for (size_t p = 1; p < paths.size(); ++p) {
    Kernel kernel{n, block, paths[p]};
    const std::vector<uint8_t> y = Run(kernel, layer);
    size_t differ = 0;
    for (size_t i = 0; i < y.size(); ++i) differ += y[i] != reference[i];
    char what[160];
    snprintf(what, sizeof(what), "%-8s = %-8s block %2zu M=%4zu K=%4zu N=%4zu: %zu of %zu bytes differ",
             Name(paths[p]), Name(paths[0]), block, m, k, n, differ, y.size());
    printf("%s\n", what);
    Expect(differ == 0, what);
  }
}

// Every row x and every weight w over `padded` inputs: the largest sums the 16-bit lanes see.
template <size_t R, size_t C>
void CheckTileExtremes(uint8_t x, int8_t w, size_t padded) {
  std::vector<uint8_t> rows(R * padded, static_cast<uint8_t>(x ^ kCenter));
  std::vector<int8_t> weights(C * padded, w);
  int32_t dots[R * C];
  DotTile<R, C>(rows.data(), weights.data(), padded, dots);
  const int32_t expected = static_cast<int32_t>(padded) * (static_cast<int32_t>(x) - kCenter) * w;
  for (size_t i = 0; i < R * C; ++i) {
    char what[120];
    snprintf(what, sizeof(what), "tile %zux%zu x=%u w=%d K=%zu: %d, expected %d", R, C, x, w, padded, dots[i],
             expected);
    Expect(dots[i] == expected, what);
  }
}

template <size_t R, size_t C>
void CheckTileRandom(size_t padded, std::mt19937& rng) {
  std::vector<uint8_t> rows(R * padded);
  std::vector<int8_t> weights(C * padded);
  for (auto& v : rows) v = static_cast<uint8_t>(rng() & 0xFF);
  for (auto& v : weights) v = static_cast<int8_t>(static_cast<int>(rng() % 16) - 8);
  int32_t dots[R * C];
  DotTile<R, C>(rows.data(), weights.data(), padded, dots);
  for (size_t r = 0; r < R; ++r) {
    for (size_t j = 0; j < C; ++j) {
      int32_t expected = 0;
      for (size_t c = 0; c < padded; ++c) {
        const int32_t value = kCenter ? static_cast<int8_t>(rows[r * padded + c]) : rows[r * padded + c];
        expected += value * weights[j * padded + c];
      }
      char what[100];
      snprintf(what, sizeof(what), "tile %zux%zu random K=%zu (%zu, %zu): %d, expected %d", R, C, padded, r, j,
               dots[r * C + j], expected);
      Expect(dots[r * C + j] == expected, what);
    }
  }
}

template <size_t R, size_t C>
void CheckTiles(std::mt19937& rng) {
  const uint8_t xs[] = {0, 1, 127, 128, 255};
  const int8_t ws[] = {-8, -1, 7};
  for (size_t padded : {16, 112, 128, 144, 1024, 2048}) {
    for (uint8_t x : xs) {
      for (int8_t w : ws) CheckTileExtremes<R, C>(x, w, padded);
    }
    CheckTileRandom<R, C>(padded, rng);
  }
}

}  // namespace

int main() {
  std::mt19937 rng(7);
  CheckTiles<4, 2>(rng);
  CheckTiles<4, 4>(rng);
  CheckTiles<kTileRows, kTileChannels>(rng);

  std::vector<Path> paths{Path::kPortable};
#if defined(__aarch64__)
  const Path detected = DetectPath();
  if (detected != Path::kPortable) paths.push_back(Path::kDotprod);
  if (detected == Path::kI8mm) paths.push_back(Path::kI8mm);
#endif
  struct Shape { size_t m, k, n; };
  const Shape shapes[] = {{197, 48, 192}, {63, 128, 48}, {128, 960, 256}, {1, 960, 1280}, {15, 80, 50},
                          {1, 1280, 960}, {130, 1024, 256}, {3, 33, 9}, {64, 16, 8}, {5, 7, 3}};
  for (Path path : paths) {
    for (size_t block : {0, 32}) {
      for (const Shape& shape : shapes) CheckLayer(path, block, shape.m, shape.k, shape.n, rng);
    }
  }
  if (paths.size() > 1) {
    const Shape layers[] = {{2016, 80, 240}, {2016, 240, 80}, {504, 160, 480}, {126, 512, 160}};
    for (const Shape& shape : layers) CheckPathsAgree({paths.rbegin(), paths.rend()}, 0, shape.m, shape.k, shape.n, rng);
    CheckPathsAgree({paths.rbegin(), paths.rend()}, 32, 64, 960, 640, rng);
    CheckPathsAgree({paths.rbegin(), paths.rend()}, 32, 64, 640, 960, rng);
  }
  printf("%s: %d failures\n", failures ? "FAILED" : "passed", failures);
  return failures ? 1 : 0;
}
