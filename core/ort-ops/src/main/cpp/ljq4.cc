/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
// Q4Conv1x1: the music encoder's pointwise convolutions with 4-bit weights, as an ONNX Runtime custom
// operator in the domain "latentjam". The weights stay 4-bit in memory and are multiplied by KleidiAI's
// int8 kernels: on CPUs with i8mm straight from the packed weights the model carries, on dotprod-only
// CPUs from a copy packed once for the dotprod kernels. Other CPUs (older 64-bit Arm, armv7, x86_64)
// decode the weights once to 8 bits and multiply them with NEON or SSSE3 code of this file.
//
// Inputs:  0 X            uint8 [..., K]  rows of an NHWC activation, statically quantized
//          1 x_scale      float []
//          2 x_zero_point uint8 []
//          3 W            uint8 [bytes]   symmetric 4-bit weights and a float bias per output channel, packed
//                                         by lj_q4_pack as KleidiAI packs them (nr 8, kr 16, sr 2): with a bf16
//                                         scale per 32 inputs (block 32, qsi4c32p) or a float scale per
//                                         output channel (block 0, qsi4cxp)
//          4 y_scale      float []
//          5 y_zero_point uint8 []
// Attributes: n, the number of output channels; block, 32 (the default) or 0.
// Output:  Y uint8 [..., n] = sat(round((x_scale * sum_k (x - x_zp) * w + bias) / y_scale) + y_zp)
//
// A scale per output channel costs nothing over an int8 matrix product; a scale per 32 inputs costs about
// 1.7x on a phone, so the encoder keeps blocks only where they buy accuracy and the work is small.
//
// Only ONNX Runtime's C API is used, at version 16, so one build works with every runtime from 1.16 on. Built
// without C++ exceptions: nothing can unwind into the runtime, and running out of memory aborts as it would there.
#include "onnxruntime_c_api.h"
#include "ljq4.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#elif defined(__SSSE3__)
#include <tmmintrin.h>
#endif
#if defined(__aarch64__)
#if defined(__APPLE__)
#include <sys/sysctl.h>
#else
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#include "kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod.h"
#include "kai_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod.h"
#include "kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm.h"
#include "kai_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm.h"
#endif
#include "kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.h"
#include "kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0.h"

namespace {

constexpr uint32_t kApiVersion = 16;
constexpr size_t kNr = 8, kSr = 2;
constexpr size_t kModelKr = 16;   // the layout the model carries: KleidiAI's i8mm kernels read it as is
constexpr size_t kDotKr = 8;      // the dotprod kernels' layout
constexpr size_t kGroup = 32;     // inputs per bf16 scale in the block format; K is padded to it in both
constexpr size_t kChunk = 64;     // rows per pass: the int8 rows and float results stay in cache

const OrtApi* api = nullptr;

size_t RoundUp(size_t a, size_t b) { return (a + b - 1) / b * b; }

// Per 8 output channels. Block format: per 32 inputs 16 bytes of values and 8 bf16 scales, then 8 sums and
// 8 biases. Channel format: K/2 bytes of values per channel, then 8 sums, 8 scales and 8 biases.
size_t WeightBytes(size_t n, size_t k, size_t block) {
  if (block == 0) return RoundUp(n, kNr) * (RoundUp(k, kGroup) / 2 + 12);
  return RoundUp(n, kNr) / kNr * (RoundUp(k, kGroup) / kGroup * kNr * 18 + kNr * 8);
}

float Bf16ToFloat(uint16_t bits) {
  const uint32_t widened = static_cast<uint32_t>(bits) << 16;
  float value;
  memcpy(&value, &widened, sizeof(value));
  return value;
}

uint16_t FloatToBf16(float value) {  // round to nearest, ties to even, as torch's bfloat16 cast
  uint32_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return static_cast<uint16_t>((bits + 0x7FFF + ((bits >> 16) & 1)) >> 16);
}

enum class Path { kI8mm, kDotprod, kPortable };

Path DetectPath() {
  if (const char* forced = getenv("LJ_Q4_PATH")) {  // testing: force a slower path
    if (!strcmp(forced, "portable")) return Path::kPortable;
#if defined(__aarch64__)
    if (!strcmp(forced, "dotprod")) return Path::kDotprod;
#endif
  }
#if defined(__aarch64__)
#if defined(__APPLE__)
  int value = 0;
  size_t size = sizeof(value);
  if (sysctlbyname("hw.optional.arm.FEAT_I8MM", &value, &size, nullptr, 0) == 0 && value) return Path::kI8mm;
  value = 0;
  size = sizeof(value);
  if (sysctlbyname("hw.optional.arm.FEAT_DotProd", &value, &size, nullptr, 0) == 0 && value) return Path::kDotprod;
#else
  if (getauxval(AT_HWCAP2) & HWCAP2_I8MM) return Path::kI8mm;
  if (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) return Path::kDotprod;
#endif
#endif
  return Path::kPortable;
}

// Signed 4-bit values [n, k] to the nibbles KleidiAI packs: value + 8, two per byte, the even input low.
std::vector<uint8_t> Nibbles(const int8_t* q, size_t n, size_t k, size_t padded) {
  const size_t stride = (padded + 1) / 2;
  std::vector<uint8_t> nibbles(n * stride, 0x88);
  for (size_t o = 0; o < n; ++o) {
    for (size_t c = 0; c < k; ++c) {
      const uint8_t u = static_cast<uint8_t>(q[o * k + c] + 8);
      uint8_t& byte = nibbles[o * stride + c / 2];
      byte = (c % 2 == 0) ? static_cast<uint8_t>((byte & 0xF0) | u) : static_cast<uint8_t>((byte & 0x0F) | (u << 4));
    }
  }
  return nibbles;
}

// values [n, k] in -8..7, scales ([n, ceil(k / 32)] for blocks, rounded to bf16 here; [n] for channels) and
// biases [n] -> KleidiAI's layout for kr.
void PackWeights(size_t n, size_t k, size_t block, size_t kr, const int8_t* q, const float* scale, const float* bias,
                 uint8_t* out) {
  if (block == 0) {
    std::vector<uint8_t> nibbles = Nibbles(q, n, k, k);
    kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0_params params{1, 8};
    kai_run_rhs_pack_nxk_qsi4cxp_qs4cxs1s0(1, n, k, kNr, kr, kSr, nibbles.data(), bias, scale, out, 0, &params);
    return;
  }
  const size_t padded = RoundUp(k, kGroup), groups = padded / kGroup;
  std::vector<uint8_t> nibbles = Nibbles(q, n, k, padded);
  std::vector<uint16_t> scales(n * groups);
  for (size_t i = 0; i < scales.size(); ++i) scales[i] = FloatToBf16(scale[i]);
  kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0_params params{1, 8, kai_dt_bf16};
  kai_run_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0(1, n, padded, kNr, kr, kSr, kGroup, nibbles.data(), padded / 2, bias,
                                            scales.data(), groups * sizeof(uint16_t), out, 0, &params);
}

// The inverse of PackWeights for the model's layout (kr 16), block format: per 32 inputs, two segments of 8
// channels x 4 words, each word the values at k, k + 16, k + 1, k + 17 (xor 0x8888); then 8 bf16 scales; at
// the end the zero-point sums (each channel's blocks' scales times their sums of values, as floats) and the
// biases.
void UnpackBlocks(const uint8_t* w, size_t n, size_t k, std::vector<int8_t>& q, std::vector<float>& scales,
                  std::vector<float>& bias, std::vector<float>& sums) {
  const size_t groups = RoundUp(k, kGroup) / kGroup;
  q.assign(n * k, 0);
  scales.assign(n * groups, 0.f);
  bias.assign(n, 0.f);
  sums.assign(n, 0.f);
  const uint8_t* src = w;
  for (size_t first = 0; first < n; first += kNr) {
    for (size_t g = 0; g < groups; ++g) {
      for (size_t segment = 0; segment < 2; ++segment) {
        for (size_t lane = 0; lane < kNr; ++lane) {
          for (size_t j = 0; j < 4; ++j, src += 2) {
            const uint16_t word = static_cast<uint16_t>((src[0] | (src[1] << 8)) ^ 0x8888);
            const size_t o = first + lane, k0 = g * kGroup + segment * 8 + 2 * j;
            if (o >= n) continue;
            const size_t ks[4] = {k0, k0 + 16, k0 + 1, k0 + 17};
            for (size_t v = 0; v < 4; ++v) {
              if (ks[v] < k) q[o * k + ks[v]] = static_cast<int8_t>((word >> (4 * v)) & 0x0F) - 8;
            }
          }
        }
      }
      for (size_t lane = 0; lane < kNr; ++lane, src += 2) {
        if (first + lane < n) scales[(first + lane) * groups + g] = Bf16ToFloat(static_cast<uint16_t>(src[0] | (src[1] << 8)));
      }
    }
    for (size_t lane = 0; lane < kNr; ++lane, src += 4) {
      if (first + lane < n) memcpy(&sums[first + lane], src, 4);
    }
    for (size_t lane = 0; lane < kNr; ++lane, src += 4) {
      if (first + lane < n) memcpy(&bias[first + lane], src, 4);
    }
  }
}

// The same for the channel format: per 8 channels, blocks of 8 bytes per channel interleaved over the 8
// channels, each byte the values at k (low) and k + 16 (high), xor 0x88; then sums x 16, scales / 16, biases.
void UnpackChannels(const uint8_t* w, size_t n, size_t k, std::vector<int8_t>& q, std::vector<float>& scales,
                    std::vector<float>& bias) {
  const size_t internal = RoundUp(k, kGroup), bytes = kNr * internal / 2, stride = bytes + kNr * 12;
  q.assign(n * k, 0);
  scales.assign(n, 0.f);
  bias.assign(n, 0.f);
  for (size_t first = 0; first < n; first += kNr) {
    const uint8_t* src = w + first / kNr * stride;
    for (size_t i = 0; i < bytes; ++i) {
      const size_t block = i / 8, super = block / kNr, lane = block % kNr, base = i % 8 + super * 8;
      const size_t k0 = base + base / 16 * 16, k1 = k0 + 16, o = first + lane;
      if (o >= n) continue;
      const uint8_t byte = src[i] ^ 0x88;
      if (k0 < k) q[o * k + k0] = static_cast<int8_t>(byte & 0x0F) - 8;
      if (k1 < k) q[o * k + k1] = static_cast<int8_t>(byte >> 4) - 8;
    }
    for (size_t lane = 0; lane < kNr && first + lane < n; ++lane) {
      float value;
      memcpy(&value, src + bytes + kNr * 4 + lane * 4, 4);
      scales[first + lane] = value * 16.f;
      memcpy(&bias[first + lane], src + bytes + kNr * 8 + lane * 4, 4);
    }
  }
}

// Rows of uint8 activations as KleidiAI's qai8dxp LHS: per group of 4 rows, interleaved blocks of `block`
// int8 values (x - 128), then each row's offset -(x_zp - 128) and multiplier x_scale. Exact: no rounding.
void PackRows(const uint8_t* x, size_t rows, size_t k, size_t block, float scale, uint8_t zero, uint8_t* out) {
  constexpr size_t mr = 4;
  const size_t internal = RoundUp(k, kGroup), stride = mr * (internal + 8);
  memset(out, 0, RoundUp(rows, mr) / mr * stride);
  for (size_t r = 0; r < rows; ++r) {
    uint8_t* group = out + (r / mr) * stride;
    const size_t lane = r % mr;
    const uint8_t* src = x + r * k;
    const size_t full = k / block;
    for (size_t b = 0; b < internal / block; ++b) {
      uint8_t* dst = group + (b * mr + lane) * block;
#if defined(__aarch64__)
      if (b < full && block == 8) {
        vst1_u8(dst, veor_u8(vld1_u8(src + b * 8), vdup_n_u8(0x80)));
        continue;
      }
#endif
      for (size_t i = 0; i < block; ++i) {  // past K: the zero point (the padded weights are zero)
        const size_t c = b * block + i;
        dst[i] = static_cast<uint8_t>((c < k ? src[c] : zero) ^ 0x80);
      }
    }
    reinterpret_cast<int32_t*>(group + mr * internal)[lane] = 128 - static_cast<int32_t>(zero);
    reinterpret_cast<float*>(group + mr * internal + mr * 4)[lane] = scale;
  }
}

void Requantize(const float* values, size_t count, float inverse, int32_t zero, uint8_t* out) {
  size_t i = 0;
#if defined(__aarch64__)
  const float32x4_t vi = vdupq_n_f32(inverse);
  const int32x4_t vz = vdupq_n_s32(zero);
  for (; i + 8 <= count; i += 8) {
    const int32x4_t a = vaddq_s32(vcvtnq_s32_f32(vmulq_f32(vld1q_f32(values + i), vi)), vz);
    const int32x4_t b = vaddq_s32(vcvtnq_s32_f32(vmulq_f32(vld1q_f32(values + i + 4), vi)), vz);
    vst1_u8(out + i, vqmovun_s16(vcombine_s16(vqmovn_s32(a), vqmovn_s32(b))));
  }
#endif
  for (; i < count; ++i) {
    const int32_t v = static_cast<int32_t>(std::nearbyint(values[i] * inverse)) + zero;
    out[i] = static_cast<uint8_t>(std::min(255, std::max(0, v)));
  }
}

// CPUs without dotprod multiply per-channel weights decoded to int8 rows of kStep-padded inputs, kTileRows
// activation rows by kTileChannels channels at a time. A 4-bit weight keeps products small enough for
// 16-bit lanes to sum a batch of kBatch inputs before widening: NEON multiplies x - 128 (int8) by w, 16
// products of at most 128 * 8 per lane; SSSE3's maddubs multiplies x (uint8) by w, 8 pairs of at most
// 2 * 255 * 8 per lane.
constexpr size_t kStep = 16;
constexpr size_t kBatch = 128;
#define LJ_UNROLL _Pragma("clang loop unroll(full)")  // the tile's accumulators live in registers
#if defined(__SSSE3__) && !defined(__ARM_NEON)
constexpr uint8_t kCenter = 0;    // activations as they are
constexpr size_t kTileRows = 4, kTileChannels = 2;
#else
constexpr uint8_t kCenter = 128;  // activations as int8 x - 128, flipped by xor
#if defined(__aarch64__)
constexpr size_t kTileRows = 4, kTileChannels = 4;
#else
constexpr size_t kTileRows = 4, kTileChannels = 2;  // armv7: 16 vector registers
#endif
#endif

// dots[r * C + j] = sum over `padded` inputs of rows[r] (prepared: x ^ kCenter) times weights[j].
template <size_t R, size_t C>
void DotTile(const uint8_t* rows, const int8_t* weights, size_t padded, int32_t* dots) {
#if defined(__ARM_NEON)
  const int8_t* x = reinterpret_cast<const int8_t*>(rows);
  int32x4_t wide[R * C];
  LJ_UNROLL for (size_t i = 0; i < R * C; ++i) wide[i] = vdupq_n_s32(0);
  for (size_t start = 0; start < padded; start += kBatch) {
    const size_t end = std::min(padded, start + kBatch);
    int16x8_t narrow[R * C];
    LJ_UNROLL for (size_t i = 0; i < R * C; ++i) narrow[i] = vdupq_n_s16(0);
    for (size_t c = start; c < end; c += kStep) {
      int8x16_t xv[R], wv[C];
      LJ_UNROLL for (size_t r = 0; r < R; ++r) xv[r] = vld1q_s8(x + r * padded + c);
      LJ_UNROLL for (size_t j = 0; j < C; ++j) wv[j] = vld1q_s8(weights + j * padded + c);
      // All low halves, then all high halves: no accumulator waits on its own last product (in-order cores).
      LJ_UNROLL for (size_t r = 0; r < R; ++r) {
        LJ_UNROLL for (size_t j = 0; j < C; ++j) {
          narrow[r * C + j] = vmlal_s8(narrow[r * C + j], vget_low_s8(xv[r]), vget_low_s8(wv[j]));
        }
      }
      LJ_UNROLL for (size_t r = 0; r < R; ++r) {
        LJ_UNROLL for (size_t j = 0; j < C; ++j) {
          narrow[r * C + j] = vmlal_s8(narrow[r * C + j], vget_high_s8(xv[r]), vget_high_s8(wv[j]));
        }
      }
    }
    LJ_UNROLL for (size_t i = 0; i < R * C; ++i) wide[i] = vpadalq_s16(wide[i], narrow[i]);
  }
  LJ_UNROLL for (size_t i = 0; i < R * C; i += 2) {  // two channels' sums per pairwise add
    const int32x2_t a = vadd_s32(vget_low_s32(wide[i]), vget_high_s32(wide[i]));
    const int32x2_t b = vadd_s32(vget_low_s32(wide[i + 1]), vget_high_s32(wide[i + 1]));
    vst1_s32(dots + i, vpadd_s32(a, b));
  }
#elif defined(__SSSE3__)
  const __m128i ones = _mm_set1_epi16(1);
  __m128i wide[R * C];
  LJ_UNROLL for (size_t i = 0; i < R * C; ++i) wide[i] = _mm_setzero_si128();
  for (size_t start = 0; start < padded; start += kBatch) {
    const size_t end = std::min(padded, start + kBatch);
    __m128i narrow[R * C];
    LJ_UNROLL for (size_t i = 0; i < R * C; ++i) narrow[i] = _mm_setzero_si128();
    for (size_t c = start; c < end; c += kStep) {
      __m128i xv[R], wv[C];
      LJ_UNROLL for (size_t r = 0; r < R; ++r) {
        xv[r] = _mm_loadu_si128(reinterpret_cast<const __m128i*>(rows + r * padded + c));
      }
      LJ_UNROLL for (size_t j = 0; j < C; ++j) {
        wv[j] = _mm_loadu_si128(reinterpret_cast<const __m128i*>(weights + j * padded + c));
      }
      LJ_UNROLL for (size_t r = 0; r < R; ++r) {
        LJ_UNROLL for (size_t j = 0; j < C; ++j) {
          narrow[r * C + j] = _mm_add_epi16(narrow[r * C + j], _mm_maddubs_epi16(xv[r], wv[j]));
        }
      }
    }
    LJ_UNROLL for (size_t i = 0; i < R * C; ++i) wide[i] = _mm_add_epi32(wide[i], _mm_madd_epi16(narrow[i], ones));
  }
  LJ_UNROLL for (size_t i = 0; i < R * C; ++i) {
    __m128i sum = _mm_add_epi32(wide[i], _mm_shuffle_epi32(wide[i], _MM_SHUFFLE(1, 0, 3, 2)));
    sum = _mm_add_epi32(sum, _mm_shuffle_epi32(sum, _MM_SHUFFLE(2, 3, 0, 1)));
    dots[i] = _mm_cvtsi128_si32(sum);
  }
#else
  LJ_UNROLL for (size_t r = 0; r < R; ++r) {
    LJ_UNROLL for (size_t j = 0; j < C; ++j) {
      int32_t sum = 0;
      for (size_t c = 0; c < padded; ++c) sum += static_cast<int8_t>(rows[r * padded + c]) * weights[j * padded + c];
      dots[r * C + j] = sum;
    }
  }
#endif
}

// One scratch per thread, shared by every layer: the runtime runs a session's nodes one at a time.
struct Scratch {
  std::vector<uint8_t> rows;
  std::vector<float> results;
  std::vector<int16_t> centered;
};
thread_local Scratch scratch;

struct Kernel {
  size_t n;
  size_t block;                    // 32: a bf16 scale per 32 inputs; 0: a float scale per output channel
  Path path;
  std::vector<uint8_t> repacked;   // dotprod-only CPUs: the weights in the dotprod kernels' layout
  std::vector<int8_t> values;      // other CPUs: the weights decoded once (per channel: rows of `stride`)
  std::vector<float> scales, bias;
  std::vector<int32_t> sums;       // other CPUs, per channel: each channel's sum of weights
  std::vector<float> blockSums;    // other CPUs, blocks: the zero-point sums the model carries
  size_t stride = 0;
  size_t cachedK = 0;              // the K values/repacked were built for: WeightBytes cannot tell 48 from 64

  void Unpack(const uint8_t* w, size_t k) {
    if (block == 0) UnpackChannels(w, n, k, values, scales, bias);
    else UnpackBlocks(w, n, k, values, scales, bias, blockSums);
    cachedK = k;
  }
  void Decode(const uint8_t* w, size_t k);
  void Multiply(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, const uint8_t* w, float ys, int32_t yz,
                uint8_t* y);
  void Channels(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz, uint8_t* y);
  void Blocks(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz, uint8_t* y);
};

// Per channel for DotTile: rows of int8 weights padded to kStep inputs and to whole tiles of channels.
void Kernel::Decode(const uint8_t* w, size_t k) {
  std::vector<int8_t> q;
  UnpackChannels(w, n, k, q, scales, bias);
  stride = RoundUp(k, kStep);
  values.assign(RoundUp(n, kTileChannels) * stride, 0);
  sums.assign(n, 0);
  for (size_t o = 0; o < n; ++o) {
    for (size_t c = 0; c < k; ++c) {
      values[o * stride + c] = q[o * k + c];
      sums[o] += q[o * k + c];
    }
  }
  cachedK = k;
}

void Kernel::Multiply(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, const uint8_t* w, float ys,
                      int32_t yz, uint8_t* y) {
  if (path == Path::kPortable && block == 0) {
    if (cachedK != k) Decode(w, k);
    return Channels(x, m, k, xs, xz, 1.0f / ys, yz, y);
  }
  if (path == Path::kPortable) {
    if (cachedK != k) Unpack(w, k);
    return Blocks(x, m, k, xs, xz, 1.0f / ys, yz, y);
  }
#if defined(__aarch64__)
  const bool i8mm = path == Path::kI8mm;
  if (!i8mm && cachedK != k) {
    Unpack(w, k);
    repacked.resize(WeightBytes(n, k, block));
    PackWeights(n, k, block, kDotKr, values.data(), scales.data(), bias.data(), repacked.data());
    std::vector<int8_t>().swap(values);
    std::vector<float>().swap(scales);
    std::vector<float>().swap(bias);
    std::vector<float>().swap(blockSums);
  }
  const uint8_t* weights = i8mm ? w : repacked.data();
  const size_t rowBlock = (i8mm ? kModelKr : kDotKr) / kSr, padded = RoundUp(k, kGroup), stride = n * sizeof(float);
  if (scratch.rows.size() < kChunk * (padded + 8)) scratch.rows.resize(kChunk * (padded + 8));
  if (scratch.results.size() < kChunk * n) scratch.results.resize(kChunk * n);
  float* results = scratch.results.data();
  for (size_t m0 = 0; m0 < m; m0 += kChunk) {
    const size_t count = std::min(kChunk, m - m0);
    const uint8_t* rows = scratch.rows.data();
    PackRows(x + m0 * k, count, k, rowBlock, xs, xz, scratch.rows.data());
    if (block == 0 && i8mm) {
      kai_run_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm(count, n, k, rows, weights, results, stride,
                                                                      sizeof(float), -INFINITY, INFINITY);
    } else if (block == 0) {
      kai_run_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod(count, n, k, rows, weights, results, stride,
                                                                         sizeof(float), -INFINITY, INFINITY);
    } else if (i8mm) {
      kai_run_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm(count, n, padded, kGroup, rows, weights, results,
                                                                       stride, sizeof(float), -INFINITY, INFINITY);
    } else {
      kai_run_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod(count, n, padded, kGroup, rows, weights, results,
                                                                       stride, sizeof(float), -INFINITY, INFINITY);
    }
    Requantize(results, count * n, 1.0f / ys, yz, y + m0 * n);
  }
#endif
}

// Per channel without dotprod: chunks of rows prepared for DotTile, each tile of channels over all of them
// while its weights are in cache, then KleidiAI's float steps and the same requantization as its paths, so a
// value that sits half a step between two outputs rounds the same way on every path: the weights' scale over
// 16 (KleidiAI shifts each nibble up 4 bits) times the activations' scale, that times 16 x the sum, and the
// bias added in a rounding of its own.
void Kernel::Channels(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz,
                      uint8_t* y) {
  const int32_t offset = static_cast<int32_t>(kCenter) - static_cast<int32_t>(xz);
  // Each row holds `stride` inputs and the cache is keyed on K, so a stale decode is impossible; the clamp
  // is the guard that would keep one from writing past the scratch vector if it ever came back.
  const size_t covered = std::min(k, stride);
  if (scratch.rows.size() < kChunk * stride) scratch.rows.resize(kChunk * stride);
  if (scratch.results.size() < kChunk * n) scratch.results.resize(kChunk * n);
  uint8_t* rows = scratch.rows.data();
  float* results = scratch.results.data();
  int32_t dots[kTileRows * kTileChannels];
  for (size_t m0 = 0; m0 < m; m0 += kChunk) {
    const size_t count = std::min(kChunk, m - m0), tiles = RoundUp(count, kTileRows);
    memset(rows, 0, tiles * stride);  // padding: zero weights there, and rows past the chunk are dropped
    for (size_t r = 0; r < count; ++r) {
      const uint8_t* src = x + (m0 + r) * k;
      uint8_t* dst = rows + r * stride;
      for (size_t c = 0; c < covered; ++c) dst[c] = static_cast<uint8_t>(src[c] ^ kCenter);
    }
    for (size_t o0 = 0; o0 < n; o0 += kTileChannels) {
      const int8_t* weights = values.data() + o0 * stride;
      for (size_t r0 = 0; r0 < tiles; r0 += kTileRows) {
        DotTile<kTileRows, kTileChannels>(rows + r0 * stride, weights, stride, dots);
        for (size_t r = 0; r < kTileRows && r0 + r < count; ++r) {
          for (size_t j = 0; j < kTileChannels && o0 + j < n; ++j) {
            const int32_t total = dots[r * kTileChannels + j] + offset * sums[o0 + j];
            const float product = static_cast<float>(16 * total) * (scales[o0 + j] * 0.0625f * xs);
            results[(r0 + r) * n + o0 + j] = product + bias[o0 + j];
          }
        }
      }
    }
    Requantize(results, count * n, inverse, yz, y + m0 * n);
  }
}

// Blocks of 32 without dotprod by plain loops the compiler vectorizes, four rows at a time: the encoder keeps
// blocks for its head, a row per window. The float steps are KleidiAI's, as in Channels: each block's sum over
// x - 128 fused into the running total with its scale, then the zero-point sum fused in times 128 - x_zp, the
// activations' scale, and the bias in a rounding of its own.
void Kernel::Blocks(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz,
                    uint8_t* y) {
  constexpr size_t kTile = 4;
  const size_t groups = RoundUp(k, kGroup) / kGroup, span = kGroup;
  const float offset = static_cast<float>(128 - static_cast<int32_t>(xz));
  if (scratch.centered.size() < kTile * k) scratch.centered.resize(kTile * k);
  int16_t* rows = scratch.centered.data();
  for (size_t r0 = 0; r0 < m; r0 += kTile) {
    const size_t count = std::min(kTile, m - r0);
    for (size_t i = 0; i < count; ++i) {
      for (size_t c = 0; c < k; ++c) rows[i * k + c] = static_cast<int16_t>(x[(r0 + i) * k + c] - 128);
    }
    for (size_t o = 0; o < n; ++o) {
      const int8_t* wo = values.data() + o * k;
      for (size_t i = 0; i < count; ++i) {
        const int16_t* row = rows + i * k;
        float sum = 0.f;
        for (size_t g = 0; g < groups; ++g) {
          int32_t acc = 0;
          const size_t end = std::min(k, (g + 1) * span);
          for (size_t c = g * span; c < end; ++c) acc += row[c] * wo[c];
          sum = std::fma(static_cast<float>(acc), scales[o * groups + g], sum);
        }
        const float scaled = std::fma(blockSums[o], offset, sum) * xs;
        const float value = scaled + bias[o];
        const int32_t q = static_cast<int32_t>(std::nearbyint(value * inverse)) + yz;
        y[(r0 + i) * n + o] = static_cast<uint8_t>(std::min(255, std::max(0, q)));
      }
    }
  }
}

OrtStatus* Fail(const char* message) { return api->CreateStatus(ORT_INVALID_ARGUMENT, message); }

OrtStatus* Count(const OrtValue* value, size_t* count, std::vector<int64_t>* shape) {
  OrtTensorTypeAndShapeInfo* info = nullptr;
  if (OrtStatus* status = api->GetTensorTypeAndShape(value, &info)) return status;
  OrtStatus* status = api->GetTensorShapeElementCount(info, count);
  if (status == nullptr && shape != nullptr) {
    size_t rank = 0;
    status = api->GetDimensionsCount(info, &rank);
    if (status == nullptr) {
      shape->resize(rank);
      status = api->GetDimensions(info, shape->data(), rank);
    }
  }
  api->ReleaseTensorTypeAndShapeInfo(info);
  return status;
}

// Reads a scalar input of the given type. The element count is checked first: a graph that hands over an
// empty tensor would otherwise make this read the first element of a buffer that holds none.
template <typename T>
OrtStatus* Scalar(OrtKernelContext* context, size_t index, T* out) {
  const OrtValue* value = nullptr;
  if (OrtStatus* status = api->KernelContext_GetInput(context, index, &value)) return status;
  size_t count = 0;
  if (OrtStatus* status = Count(value, &count, nullptr)) return status;
  if (count != 1) return Fail("Q4Conv1x1: a quantization parameter must hold exactly one element");
  void* data = nullptr;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(value), &data)) return status;
  *out = *static_cast<const T*>(data);
  return nullptr;
}

OrtStatus* ORT_API_CALL Compute(void* raw, OrtKernelContext* context) {
  Kernel* kernel = static_cast<Kernel*>(raw);
  const OrtValue* x = nullptr;
  const OrtValue* w = nullptr;
  if (OrtStatus* status = api->KernelContext_GetInput(context, 0, &x)) return status;
  if (OrtStatus* status = api->KernelContext_GetInput(context, 3, &w)) return status;
  size_t count = 0, weightBytes = 0;
  std::vector<int64_t> shape;
  if (OrtStatus* status = Count(x, &count, &shape)) return status;
  if (OrtStatus* status = Count(w, &weightBytes, nullptr)) return status;
  if (shape.empty() || shape.back() <= 0) return Fail("Q4Conv1x1: X needs a channel axis");
  const size_t k = static_cast<size_t>(shape.back()), m = count / k;
  if (weightBytes != WeightBytes(kernel->n, k, kernel->block)) {
    return Fail("Q4Conv1x1: W does not match n, block and the channels of X");
  }
  float xs = 0.f, ys = 0.f;
  uint8_t xz = 0, yz = 0;
  if (OrtStatus* status = Scalar(context, 1, &xs)) return status;
  if (OrtStatus* status = Scalar(context, 2, &xz)) return status;
  if (OrtStatus* status = Scalar(context, 4, &ys)) return status;
  if (OrtStatus* status = Scalar(context, 5, &yz)) return status;
  if (!(ys > 0.f) || !std::isfinite(xs)) return Fail("Q4Conv1x1: bad quantization scales");
  shape.back() = static_cast<int64_t>(kernel->n);
  OrtValue* y = nullptr;
  if (OrtStatus* status = api->KernelContext_GetOutput(context, 0, shape.data(), shape.size(), &y)) return status;
  void *xd = nullptr, *wd = nullptr, *yd = nullptr;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(x), &xd)) return status;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(w), &wd)) return status;
  if (OrtStatus* status = api->GetTensorMutableData(y, &yd)) return status;
  if (m == 0) return nullptr;
  kernel->Multiply(static_cast<const uint8_t*>(xd), m, k, xs, xz, static_cast<const uint8_t*>(wd), ys, yz,
                   static_cast<uint8_t*>(yd));
  return nullptr;
}

OrtStatus* ORT_API_CALL CreateKernel(const OrtCustomOp*, const OrtApi*, const OrtKernelInfo* info, void** out) {
  int64_t n = 0, block = 32;
  if (OrtStatus* status = api->KernelInfoGetAttribute_int64(info, "n", &n)) return status;
  if (OrtStatus* status = api->KernelInfoGetAttribute_int64(info, "block", &block)) {
    api->ReleaseStatus(status);  // absent: blocks of 32
    block = 32;
  }
  if (n <= 0) return Fail("Q4Conv1x1: n must be positive");
  if (block != 0 && block != 32) return Fail("Q4Conv1x1: block must be 32 or 0");
  Kernel* kernel = new (std::nothrow) Kernel{static_cast<size_t>(n), static_cast<size_t>(block), DetectPath(), {}, {}, {}, {}};
  if (kernel == nullptr) return api->CreateStatus(ORT_FAIL, "Q4Conv1x1: out of memory");
  *out = kernel;
  return nullptr;
}

OrtCustomOp MakeOp() {
  OrtCustomOp op{};
  op.version = kApiVersion;
  op.GetName = [](const OrtCustomOp*) { return "Q4Conv1x1"; };
  op.GetExecutionProviderType = [](const OrtCustomOp*) -> const char* { return nullptr; };
  op.GetInputTypeCount = [](const OrtCustomOp*) -> size_t { return 6; };
  op.GetInputType = [](const OrtCustomOp*, size_t index) {
    return index == 1 || index == 4 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
  };
  op.GetOutputTypeCount = [](const OrtCustomOp*) -> size_t { return 1; };
  op.GetOutputType = [](const OrtCustomOp*, size_t) { return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8; };
  op.KernelDestroy = [](void* kernel) { delete static_cast<Kernel*>(kernel); };
  op.GetInputCharacteristic = [](const OrtCustomOp*, size_t) { return INPUT_OUTPUT_REQUIRED; };
  op.GetOutputCharacteristic = [](const OrtCustomOp*, size_t) { return INPUT_OUTPUT_REQUIRED; };
  op.GetInputMemoryType = [](const OrtCustomOp*, size_t) { return OrtMemTypeDefault; };
  op.GetVariadicInputMinArity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicInputHomogeneity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicOutputMinArity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicOutputHomogeneity = [](const OrtCustomOp*) { return 1; };
  op.CreateKernelV2 = CreateKernel;
  op.KernelComputeV2 = Compute;
  return op;
}

}  // namespace

extern "C" {

#define LJ_EXPORT __attribute__((visibility("default")))

// ONNX Runtime's entry point for operator libraries (SessionOptions.registerCustomOpLibrary, Android).
LJ_EXPORT OrtStatus* ORT_API_CALL RegisterCustomOps(OrtSessionOptions* options, const OrtApiBase* base) {
  static const OrtApi* const runtime = base->GetApi(kApiVersion);
  if (runtime == nullptr) return nullptr;  // older than 1.16: there is no API to fail through
  api = runtime;
  static OrtCustomOp op = MakeOp();
  static OrtCustomOpDomain* domain = nullptr;
  if (domain == nullptr) {
    if (OrtStatus* status = api->CreateCustomOpDomain("latentjam", &domain)) return status;
    if (OrtStatus* status = api->CustomOpDomain_Add(domain, &op)) return status;
  }
  return api->AddCustomOpDomain(options, domain);
}

// The same for runtimes that link this library statically (iOS); see ljq4.h.
LJ_EXPORT OrtStatus* LjRegisterOrtOps(OrtSessionOptions* options, const OrtApiBase* base) {
  return RegisterCustomOps(options, base);
}

// Bytes lj_q4_pack writes for an [n, k] matrix (block 32 or 0).
LJ_EXPORT size_t lj_q4_packed_size(size_t n, size_t k, size_t block) { return WeightBytes(n, k, block); }

// Packs values q [n, k] in -8..7, scales ([n, ceil(k / 32)] for blocks of 32, [n] per channel) and biases [n]
// the way the model carries them.
LJ_EXPORT void lj_q4_pack(size_t n, size_t k, size_t block, const int8_t* q, const float* scale, const float* bias,
                          uint8_t* out) {
  PackWeights(n, k, block, kModelKr, q, scale, bias, out);
}

}  // extern "C"
