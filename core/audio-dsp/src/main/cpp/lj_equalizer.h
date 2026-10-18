/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#ifndef LJ_EQUALIZER_H
#define LJ_EQUALIZER_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Opaque stream, one audio consumer and one serialized settings producer.
 * Allocate/destroy/reset only while rendering is stopped. No allocation or lock in processing.
 */
typedef struct lj_equalizer lj_equalizer;
lj_equalizer *lj_eq_create(int sample_rate, int channels);
void lj_eq_destroy(lj_equalizer *eq);
void lj_eq_set_settings(lj_equalizer *eq, int enabled, const int32_t levels[10], int bass);
void lj_eq_reset(lj_equalizer *eq);
/* Rendering stopped or on the render thread. After a seek or restart the first input frames
 * rise from silence over 5 ms, so a waveform resumed mid-cycle does not click. Reset cancels it. */
void lj_eq_fade_in(lj_equalizer *eq);
int lj_eq_latency_frames(const lj_equalizer *eq);
int lj_eq_channel_count(const lj_equalizer *eq);
/* Float entry points preserve the fixed delay, including bypass. Buffers are in-place. */
void lj_eq_process_f32(lj_equalizer *eq, float *interleaved, int frames);
void lj_eq_process_planar(lj_equalizer *eq, float *const *channels, int frames);
/* Media3 streaming adapter: trims priming silence, drains exactly the pending input frames. */
int lj_eq_render_s16(lj_equalizer *eq, const void *input, void *output, int frames);
int lj_eq_pending_frames(const lj_equalizer *eq);
int lj_eq_drain_s16(lj_equalizer *eq, void *output, int capacity_frames);
/* UI takes the minimum gain since the previous read. A paused/stale stream returns unity. */
float lj_eq_take_meter_gain(lj_equalizer *eq);
/* Control/UI thread only. Returns static headroom; out_db receives the linear response. */
float lj_eq_frequency_response(int sample_rate, int enabled, const int32_t levels[10], int bass,
    const float *frequencies, float *out_db, int count);
#ifdef __cplusplus
}
#endif
#endif
