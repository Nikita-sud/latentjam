/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
#import "LJSafeAudioFile.h"

NSErrorDomain const LJObjCExceptionErrorDomain = @"io.github.nikitasud.latentjam.ObjCException";

static NSError *LJErrorFromException(NSException *exception) {
    NSString *description = exception.reason ? [NSString stringWithFormat:@"%@: %@", exception.name, exception.reason]
                                             : exception.name;
    return [NSError errorWithDomain:LJObjCExceptionErrorDomain
                               code:0
                           userInfo:@{NSLocalizedDescriptionKey : description}];
}

@implementation LJSafeAudioFile

+ (BOOL)setFramePosition:(AVAudioFramePosition)position
                  ofFile:(AVAudioFile *)file
                   error:(NSError *_Nullable *_Nullable)error {
    @try {
        file.framePosition = position;
        return YES;
    } @catch (NSException *exception) {
        if (error) *error = LJErrorFromException(exception);
        return NO;
    }
}

+ (BOOL)readFile:(AVAudioFile *)file
      intoBuffer:(AVAudioPCMBuffer *)buffer
      frameCount:(AVAudioFrameCount)frames
           error:(NSError *_Nullable *_Nullable)error {
    @try {
        return [file readIntoBuffer:buffer frameCount:frames error:error];
    } @catch (NSException *exception) {
        if (error) *error = LJErrorFromException(exception);
        return NO;
    }
}

@end
