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
/* Reset for a stream resumed mid-track: its first 5 ms rise from silence (see lj_eq_fade_in). */
void lj_eq_ios_restart(AVAudioUnit *unit);
/* Stopped graph only. Configure both buses while detached, then reconnect source -> unit -> main
 * mixer. Unsupported system formats return false; an Objective-C audio-graph exception must not
 * cross the Kotlin boundary. */
int lj_eq_ios_connect(AVAudioEngine *engine, AVAudioNode *source, AVAudioUnit *unit, AVAudioFormat *format);
/* Connects a player to one input bus of a mixer in its own format; the mixer converts. Allowed on
 * a running graph, which is how a second track joins for an overlap. False on any graph error. */
int lj_eq_ios_connect_deck(AVAudioEngine *engine, AVAudioPlayerNode *player, AVAudioMixerNode *mixer, int bus, AVAudioFormat *format);
/* Whether [file] really decodes: reads its first frames, then rewinds. AVAudioFile opens some files it cannot decode
 * (32-bit FLAC in MP4) and raises an Objective-C exception from framePosition and every read, which ends the app if it
 * crosses the Kotlin boundary; such a file returns false. */
int lj_eq_ios_file_decodes(AVAudioFile *file);
/* framePosition = start, then readIntoBuffer; false on an error, an exception or no frames. */
int lj_eq_ios_read_at(AVAudioFile *file, AVAudioPCMBuffer *buffer, int64_t start, uint32_t frames);
/* readIntoBuffer from the current position: 1 read (frameLength 0 at the end), 0 an error, -1 an exception (the file
 * cannot decode). */
int lj_eq_ios_read_next(AVAudioFile *file, AVAudioPCMBuffer *buffer);
int lj_eq_ios_sample_rate(AVAudioUnit *unit);
float lj_eq_ios_take_meter_gain(AVAudioUnit *unit);
#ifdef __cplusplus
}
#endif
NS_ASSUME_NONNULL_END
