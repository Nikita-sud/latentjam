/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
#include "ljdots.h"

// Each multiply and addition must round separately, in increasing dimension order: changing this
// changes score ties and therefore queues. SIMD lanes represent independent rows, never partial sums.
#pragma clang fp contract(off)

#if defined(__aarch64__)
#include <arm_neon.h>

namespace {

// Four contiguous loads, transposed only in registers. There is no matrix copy or retained scratch.
inline void Transpose(float32x4_t a, float32x4_t b, float32x4_t c, float32x4_t d, float32x4_t* columns) {
  const auto ab = vtrnq_f32(a, b);
  const auto cd = vtrnq_f32(c, d);
  columns[0] = vcombine_f32(vget_low_f32(ab.val[0]), vget_low_f32(cd.val[0]));
  columns[1] = vcombine_f32(vget_low_f32(ab.val[1]), vget_low_f32(cd.val[1]));
  columns[2] = vcombine_f32(vget_high_f32(ab.val[0]), vget_high_f32(cd.val[0]));
  columns[3] = vcombine_f32(vget_high_f32(ab.val[1]), vget_high_f32(cd.val[1]));
}

}  // namespace

extern "C" __attribute__((visibility("default"))) int LjBatchDots(
    const float* matrix, int32_t dim, const float* query, const int32_t* rows, int32_t count, float* out) {
  int r = 0;
  for (; r + 8 <= count; r += 8) {
    const float* row[8];
    for (int j = 0; j < 8; ++j) row[j] = matrix + rows[r + j] * dim;
    float32x4_t first = vdupq_n_f32(0);
    float32x4_t second = first;
    int d = 0;
    for (; d + 4 <= dim; d += 4) {
      float32x4_t a[4], b[4];
      Transpose(vld1q_f32(row[0] + d), vld1q_f32(row[1] + d),
                vld1q_f32(row[2] + d), vld1q_f32(row[3] + d), a);
      Transpose(vld1q_f32(row[4] + d), vld1q_f32(row[5] + d),
                vld1q_f32(row[6] + d), vld1q_f32(row[7] + d), b);
      // Interleave the independent accumulators without regrouping either sum.
      first = vaddq_f32(first, vmulq_n_f32(a[0], query[d]));
      second = vaddq_f32(second, vmulq_n_f32(b[0], query[d]));
      first = vaddq_f32(first, vmulq_n_f32(a[1], query[d + 1]));
      second = vaddq_f32(second, vmulq_n_f32(b[1], query[d + 1]));
      first = vaddq_f32(first, vmulq_n_f32(a[2], query[d + 2]));
      second = vaddq_f32(second, vmulq_n_f32(b[2], query[d + 2]));
      first = vaddq_f32(first, vmulq_n_f32(a[3], query[d + 3]));
      second = vaddq_f32(second, vmulq_n_f32(b[3], query[d + 3]));
    }
    float results[8];
    vst1q_f32(results, first);
    vst1q_f32(results + 4, second);
    for (int j = 0; j < 8; ++j) {
      float sum = results[j];
      for (int k = d; k < dim; ++k) sum += row[j][k] * query[k];
      out[rows[r + j]] = sum;
    }
  }
  // Short batches and ragged dimensions use the same scalar operation order.
  for (; r < count; ++r) {
    float sum = 0;
    for (int d = 0; d < dim; ++d) sum += matrix[rows[r] * dim + d] * query[d];
    out[rows[r]] = sum;
  }
  return 1;
}

#else
extern "C" __attribute__((visibility("default"))) int LjBatchDots(
    const float*, int32_t, const float*, const int32_t*, int32_t, float*) {
  return 0;
}
#endif
