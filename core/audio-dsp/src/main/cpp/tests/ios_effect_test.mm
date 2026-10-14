/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#import "lj_equalizer_ios.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

static int check(int rate, int channels, bool boosted) { @autoreleasepool {
    NSError *error = nil;
    AVAudioEngine *engine = [[AVAudioEngine alloc] init];
    AVAudioPlayerNode *player = [[AVAudioPlayerNode alloc] init];
    AVAudioUnit *unit = lj_eq_ios_create();
    AVAudioFormat *format = channels <= 2 ? [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:channels] :
        [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channelLayout:[[AVAudioChannelLayout alloc] initWithLayoutTag:kAudioChannelLayoutTag_MPEG_5_1_A]];
    if (![engine enableManualRenderingMode:AVAudioEngineManualRenderingModeOffline format:format maximumFrameCount:512 error:&error]) { NSLog(@"%@",error); return 1; }
    [engine attachNode:player]; [engine attachNode:unit];
    [engine connect:player to:unit format:format]; [engine connect:unit to:engine.outputNode format:format];
    int32_t levels[10] = {700, 600, 450, 250, 0, 0, 0, 0, 0, 0};
    lj_eq_ios_set_settings(unit, boosted, levels, boosted ? 1000 : 0);
    const int frames = rate / 5, latency = std::max(1, rate / 200), total = frames + latency;
    std::vector<float> expected(total * channels), actual(total * channels);
    AVAudioPCMBuffer *input = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:frames];
    input.frameLength = frames;
    for (int i = 0; i < frames; ++i) for (int c = 0; c < channels; ++c) {
        const float sample = float((0.6 * std::sin(2*M_PI*40*i/rate) + 0.15 * std::sin(2*M_PI*1000*i/rate)) * (c % 2 ? -0.25 : 1));
        input.floatChannelData[c][i] = sample; expected[i * channels + c] = sample;
    }
    lj_equalizer *reference = lj_eq_create(rate, channels);
    lj_eq_set_settings(reference, boosted, levels, boosted ? 1000 : 0); lj_eq_reset(reference);
    for (int offset = 0; offset < total; offset += 512) lj_eq_process_f32(reference, expected.data() + offset * channels, std::min(512, total - offset));
    [player scheduleBuffer:input completionHandler:nil];
    if (![engine startAndReturnError:&error]) { NSLog(@"%@",error); return 2; }
    [player play];
    AVAudioPCMBuffer *output = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:512];
    for (int offset = 0; offset < total;) {
        const int count = std::min(512, total - offset);
        const auto status = [engine renderOffline:count toBuffer:output error:&error];
        if (status != AVAudioEngineManualRenderingStatusSuccess) { NSLog(@"render status %ld %@",long(status),error); return 3; }
        for (int i = 0; i < count; ++i) for (int c = 0; c < channels; ++c) actual[(offset+i)*channels+c] = output.floatChannelData[c][i];
        offset += count;
    }
    double maximum = 0;
    for (size_t i = 0; i < actual.size(); ++i) maximum = std::max(maximum, std::abs(double(actual[i] - expected[i])));
    printf("AU vs core: %d Hz, %d channels, boost=%d, max error %.9g, last %.6f/%.6f\n",rate,channels,boosted,maximum,actual.back(),expected.back());
    [player stop]; [engine stop]; lj_eq_destroy(reference);
    return maximum < 0.00002 ? 0 : 4;
} }
int main() {
    for (int rate : {44100,48000}) for (int channels : {1,2,6}) for (bool boost : {false,true}) {
        const int result = check(rate,channels,boost); if (result) return result;
    }
    return 0;
}
