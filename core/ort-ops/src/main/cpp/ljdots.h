/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// out[rows[r]] = ascending-d sum(matrix[rows[r]*dim+d] * query[d]).
// Borrowed pointers, valid lengths and row indices are the caller's responsibility; no allocations.
// Returns 0 without writing output on unsupported CPUs, 1 when computed. Arrays must not overlap output.
int LjBatchDots(const float* matrix, int32_t dim, const float* query, const int32_t* rows, int32_t count, float* out);
#ifdef __cplusplus
}
#endif
