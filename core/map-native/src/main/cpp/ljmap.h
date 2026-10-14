// SPDX-License-Identifier: Apache-2.0
#ifndef LATENTJAM_MAP_H
#define LATENTJAM_MAP_H
#ifdef __cplusplus
extern "C" {
#endif
// 0 complete, 1 cancelled, 2 invalid input, 3 unavailable/failed. Output changes only on success.
// rows: n*dim Float32, initial: optional n*2, output: n*2. n=61..3000, dim=1..50.
// is_active is called synchronously on the calling thread. No retained pointers or worker threads.
typedef int (*LjMapIsActive)(void* context);
__attribute__((visibility("default")))
int LjMapEmbed(const float* rows, int n, int dim, const float* initial, float* output,
               LjMapIsActive is_active, void* context);
#ifdef __cplusplus
}
#endif
#endif

