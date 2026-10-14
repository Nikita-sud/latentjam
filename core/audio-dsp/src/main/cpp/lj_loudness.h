/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#ifndef LJ_LOUDNESS_H
#define LJ_LOUDNESS_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Background analysis only. One owner per meter. Interleaved, original-rate PCM; no downmix.
 * Mono/stereo, 8–192 kHz. Ambiguous multichannel layouts are deliberately refused.
 * Histogram mode bounds memory independently of track duration (0.1 LU bins).
 * Never call finish on a partial/cancelled decode. Destroy on every exit path. */
typedef struct lj_loudness lj_loudness;
lj_loudness *lj_loudness_create(int sample_rate, int channels);
int lj_loudness_add(lj_loudness *meter, const float *samples, int frames);
/* Returns 1 only for finite integrated LUFS and maximum true peak in dBTP.
 * Silence/less than 400 ms/invalid input/second finish returns 0. */
int lj_loudness_finish(lj_loudness *meter, double *lufs, double *true_peak_dbtp);
void lj_loudness_destroy(lj_loudness *meter);
#ifdef __cplusplus
}
#endif
#endif
