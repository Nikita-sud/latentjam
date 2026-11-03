/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
#import <AVFAudio/AVFAudio.h>

NS_ASSUME_NONNULL_BEGIN

/// The error domain of an Objective-C exception that LJSafeAudioFile turned into an error.
FOUNDATION_EXPORT NSErrorDomain const LJObjCExceptionErrorDomain;

/// AVAudioFile calls that report some media faults by raising NSException, which Swift cannot catch: a FLAC-in-MP4
/// file opens, then raises from both of these, even at frame 0. Here the exception comes back as an error instead
/// of ending the app; without one, each call is exactly the AVAudioFile call it wraps.
@interface LJSafeAudioFile : NSObject

+ (BOOL)setFramePosition:(AVAudioFramePosition)position
                  ofFile:(AVAudioFile *)file
                   error:(NSError *_Nullable *_Nullable)error;

+ (BOOL)readFile:(AVAudioFile *)file
      intoBuffer:(AVAudioPCMBuffer *)buffer
      frameCount:(AVAudioFrameCount)frames
           error:(NSError *_Nullable *_Nullable)error;

@end

NS_ASSUME_NONNULL_END
