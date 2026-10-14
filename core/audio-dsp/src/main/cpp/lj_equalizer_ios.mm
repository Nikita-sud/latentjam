/* Copyright (c) 2026 LatentJam Project. SPDX-License-Identifier: Apache-2.0 */
#import "lj_equalizer_ios.h"
#import <AudioToolbox/AudioToolbox.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <mutex>

struct LJRenderState {
    lj_equalizer *core = nullptr;
    AudioBufferList *input = nullptr;
    uint32_t channels = 0, capacity = 0;
    void *storage[32] = {};
};

@interface LJEqualizerAudioUnit : AUAudioUnit {
    AUAudioUnitBus *_inputBus, *_outputBus;
    AUAudioUnitBusArray *_inputs, *_outputs;
    AVAudioPCMBuffer *_inputBuffer;
    LJRenderState _render;
    std::mutex _control;
    std::array<int32_t, 10> _levels;
    int _enabled, _bass;
}
- (void)setEnabled:(int)enabled levels:(const int32_t *)levels bass:(int)bass;
- (void)resetDSP;
- (int)sampleRate;
- (float)takeMeterGain;
@end

@implementation LJEqualizerAudioUnit
- (instancetype)initWithComponentDescription:(AudioComponentDescription)description options:(AudioComponentInstantiationOptions)options error:(NSError **)error {
    self = [super initWithComponentDescription:description options:options error:error];
    if (self) {
        AVAudioFormat *format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:44100 channels:2];
        _inputBus = [[AUAudioUnitBus alloc] initWithFormat:format error:error];
        _outputBus = [[AUAudioUnitBus alloc] initWithFormat:format error:error];
        if (!_inputBus || !_outputBus) return nil;
        _inputs = [[AUAudioUnitBusArray alloc] initWithAudioUnit:self busType:AUAudioUnitBusTypeInput busses:@[_inputBus]];
        _outputs = [[AUAudioUnitBusArray alloc] initWithAudioUnit:self busType:AUAudioUnitBusTypeOutput busses:@[_outputBus]];
        self.maximumFramesToRender = 4096;
    }
    return self;
}
- (AUAudioUnitBusArray *)inputBusses { return _inputs; }
- (AUAudioUnitBusArray *)outputBusses { return _outputs; }
- (NSArray<NSNumber *> *)channelCapabilities { return @[@(-1), @(-1)]; }
- (NSTimeInterval)latency {
    const double rate = _inputBus.format.sampleRate;
    return rate > 0 ? double(std::max(1, int(rate) / 200)) / rate : 0.005;
}
- (BOOL)shouldChangeToFormat:(AVAudioFormat *)format forBus:(AUAudioUnitBus *)bus {
    return format.commonFormat == AVAudioPCMFormatFloat32 && !format.interleaved &&
        format.channelCount > 0 && format.channelCount <= 32 && format.sampleRate > 0 && format.sampleRate <= 768000;
}
- (BOOL)allocateRenderResourcesAndReturnError:(NSError **)error {
    if (self.renderResourcesAllocated) return YES;
    if (![super allocateRenderResourcesAndReturnError:error]) return NO;
    const AVAudioFormat *input = _inputBus.format, *output = _outputBus.format;
    if (input.sampleRate != output.sampleRate || input.channelCount != output.channelCount ||
        input.commonFormat != AVAudioPCMFormatFloat32 || output.commonFormat != AVAudioPCMFormatFloat32 ||
        input.interleaved || output.interleaved) {
        [self setRenderResourcesAllocated:NO];
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:kAudioUnitErr_FormatNotSupported userInfo:nil];
        return NO;
    }
    std::lock_guard<std::mutex> guard(_control);
    _inputBuffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:_inputBus.format frameCapacity:self.maximumFramesToRender];
    _render.core = lj_eq_create(int(input.sampleRate), int(input.channelCount));
    if (!_inputBuffer || !_render.core) {
        lj_eq_destroy(_render.core); _render.core = nullptr; _inputBuffer = nil;
        [self setRenderResourcesAllocated:NO];
        if (error) *error = [NSError errorWithDomain:NSOSStatusErrorDomain code:kAudioUnitErr_FailedInitialization userInfo:nil];
        return NO;
    }
    _render.input = _inputBuffer.mutableAudioBufferList;
    _render.channels = input.channelCount; _render.capacity = self.maximumFramesToRender;
    for (uint32_t c = 0; c < _render.channels; ++c) _render.storage[c] = _render.input->mBuffers[c].mData;
    lj_eq_set_settings(_render.core, _enabled, _levels.data(), _bass);
    lj_eq_reset(_render.core);
    return YES;
}
- (void)deallocateRenderResources {
    std::lock_guard<std::mutex> guard(_control);
    lj_eq_destroy(_render.core); _render.core = nullptr; _render.input = nullptr;
    _inputBuffer = nil;
    [super deallocateRenderResources];
}
- (void)dealloc { lj_eq_destroy(_render.core); }
- (AUInternalRenderBlock)internalRenderBlock {
    LJRenderState *state = &_render;
    return ^AUAudioUnitStatus(AudioUnitRenderActionFlags *flags, const AudioTimeStamp *timestamp,
        AUAudioFrameCount count, NSInteger bus, AudioBufferList *output,
        const AURenderEvent *events, AURenderPullInputBlock pull) {
        if (!state->core || !pull) return kAudioUnitErr_Uninitialized;
        if (count > state->capacity || output->mNumberBuffers != state->channels) return kAudioUnitErr_TooManyFramesToProcess;
        for (uint32_t c = 0; c < state->channels; ++c) {
            state->input->mBuffers[c].mData = state->storage[c];
            state->input->mBuffers[c].mDataByteSize = count * sizeof(float);
        }
        const AUAudioUnitStatus status = pull(flags, timestamp, count, 0, state->input);
        if (status != noErr) return status;
        if (*flags & kAudioUnitRenderAction_OutputIsSilence) {
            for (uint32_t c = 0; c < state->channels; ++c) std::memset(state->input->mBuffers[c].mData, 0, count * sizeof(float));
        }
        float *channels[32];
        for (uint32_t c = 0; c < state->channels; ++c) {
            AudioBuffer &target = output->mBuffers[c];
            if (!target.mData) target.mData = state->input->mBuffers[c].mData;
            else if (target.mData != state->input->mBuffers[c].mData) std::memcpy(target.mData, state->input->mBuffers[c].mData, count * sizeof(float));
            target.mDataByteSize = count * sizeof(float); target.mNumberChannels = 1;
            channels[c] = static_cast<float *>(target.mData);
        }
        lj_eq_process_planar(state->core, channels, int(count));
        *flags &= ~kAudioUnitRenderAction_OutputIsSilence;
        return noErr;
    };
}
- (void)setEnabled:(int)enabled levels:(const int32_t *)levels bass:(int)bass {
    std::lock_guard<std::mutex> guard(_control);
    _enabled = enabled; _bass = bass; std::memcpy(_levels.data(), levels, sizeof(int32_t) * 10);
    if (_render.core) lj_eq_set_settings(_render.core, _enabled, _levels.data(), _bass);
}
- (void)resetDSP { std::lock_guard<std::mutex> guard(_control); if (_render.core) lj_eq_reset(_render.core); }
- (int)sampleRate { std::lock_guard<std::mutex> guard(_control); return _render.core ? int(_inputBus.format.sampleRate) : 0; }
- (float)takeMeterGain { std::lock_guard<std::mutex> guard(_control); return lj_eq_take_meter_gain(_render.core); }
@end

AVAudioUnit *lj_eq_ios_create(void) {
    static AudioComponentDescription description = {kAudioUnitType_Effect, 'LJeq', 'LtJm', 0, 0};
    static dispatch_once_t once;
    dispatch_once(&once, ^{ [AUAudioUnit registerSubclass:LJEqualizerAudioUnit.class asComponentDescription:description name:@"LatentJam: Equalizer" version:1]; });
    return [[AVAudioUnitEffect alloc] initWithAudioComponentDescription:description];
}
void lj_eq_ios_set_settings(AVAudioUnit *unit, int enabled, const int32_t *levels, int bass) {
    [(LJEqualizerAudioUnit *)unit.AUAudioUnit setEnabled:enabled levels:levels bass:bass];
}
void lj_eq_ios_reset(AVAudioUnit *unit) { [(LJEqualizerAudioUnit *)unit.AUAudioUnit resetDSP]; }
int lj_eq_ios_sample_rate(AVAudioUnit *unit) { return [(LJEqualizerAudioUnit *)unit.AUAudioUnit sampleRate]; }
float lj_eq_ios_take_meter_gain(AVAudioUnit *unit) { return [(LJEqualizerAudioUnit *)unit.AUAudioUnit takeMeterGain]; }

int lj_eq_ios_connect(AVAudioEngine *engine, AVAudioPlayerNode *player, AVAudioUnit *unit, AVAudioFormat *format) {
    if (engine.running) return 0;
    @try {
        [engine disconnectNodeOutput:player];
        [engine detachNode:unit];
        AUAudioUnit *au = unit.AUAudioUnit;
        [au deallocateRenderResources];
        NSError *error = nil;
        BOOL configured = [au.inputBusses[0] setFormat:format error:&error] &&
            [au.outputBusses[0] setFormat:format error:&error];
        [engine attachNode:unit];
        if (!configured) return 0;
        [engine connect:player to:unit format:format];
        [engine connect:unit to:engine.mainMixerNode format:format];
        return 1;
    } @catch (NSException *exception) {
        return 0;
    }
}
