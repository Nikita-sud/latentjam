/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#import <AVFoundation/AVFoundation.h>
#include "lj_equalizer.h"
NS_ASSUME_NONNULL_BEGIN
#ifdef __cplusplus
extern "C" {
#endif
AVAudioUnit * _Nullable lj_eq_ios_create(void);
void lj_eq_ios_set_settings(AVAudioUnit *unit, int enabled, const int32_t *levels, int bass);
void lj_eq_ios_reset(AVAudioUnit *unit);
int lj_eq_ios_sample_rate(AVAudioUnit *unit);
float lj_eq_ios_take_meter_gain(AVAudioUnit *unit);
#ifdef __cplusplus
}
#endif
NS_ASSUME_NONNULL_END
