/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import AVFoundation
import Foundation

/// One decoded window: 320,000 samples, of which the first `valid` came from the file and the rest are zeros.
struct DecodedWindow {
    var waveform: [Float]
    let valid: Int
}

enum DecodeFailureClass {
    case invalidAudio
    case unavailable
}

/// What the reader path can say about a song before any sample is read.
enum AudioWindowError: LocalizedError {
    case notLocal
    case protectedContent
    case noAudioTrack
    case readerRefused

    var errorDescription: String? {
        switch self {
        case .notLocal: return "Audio file is not locally readable"
        case .protectedContent: return "Audio is protected and cannot be read"
        case .noAudioTrack: return "Asset holds no audio track"
        case .readerRefused: return "Asset reader refused the audio track"
        }
    }
}

/// The 10 s windows the audio model hears, from an imported file or from a song of the Music library.
///
/// A file is read with `AVAudioFile`. A Music library song has no file: its `ipod-library://` asset URL is read with
/// `AVAssetReader`, which hands back the decoded samples at the song's own rate and channel count. Both then go
/// through the same `convert`, so the resampler, the mono downmix, the padding and the clamp are one piece of code.
///
/// This file has no dependency on the Kotlin framework or ONNX Runtime, so that the two paths can be compared on a Mac.
enum IosAudioWindowDecoder {
    static let musicLibraryScheme = "ipod-library"

    /// AVError codes by number: several of these names do not exist in the macOS SDK the comparison is built with.
    private static let passingAssetErrors: Set<Int> = [
        -11801, // AVErrorOutOfMemory
        -11818, // AVErrorSessionWasInterrupted
        -11819, // AVErrorMediaServicesWereReset
        -11836, // AVErrorApplicationIsNotAuthorized
        -11839, // AVErrorDecoderTemporarilyUnavailable
        -11847, // AVErrorOperationInterrupted
    ]

    static func localURL(_ value: String) throws -> URL {
        if let url = URL(string: value), url.isFileURL || url.scheme == musicLibraryScheme { return url }
        let url = URL(fileURLWithPath: value)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw AudioWindowError.notLocal
        }
        return url
    }

    static func decodeWindow(url: URL, startMs: Int64) throws -> DecodedWindow? {
        if url.scheme == musicLibraryScheme { return try decodeAssetWindow(url: url, startMs: startMs) }
        return try decodeFileWindow(url: url, startMs: startMs)
    }

    static func decodeFileWindow(url: URL, startMs: Int64) throws -> DecodedWindow? {
        let file = try AVAudioFile(forReading: url)
        let inputFormat = file.processingFormat
        let requestedFrame = AVAudioFramePosition(
            Double(max(0, startMs)) * inputFormat.sampleRate / 1_000
        )
        // AVAudioFile throws an Objective-C NSException (not a Swift Error) when
        // framePosition is outside the real stream. Imported metadata can overstate
        // duration, especially for damaged VBR files, so reject that window before
        // touching the exception-raising setter. Other valid windows can still be used.
        guard requestedFrame < file.length else { return nil }
        // A FLAC-in-MP4 file opens but raises from both calls below, even at frame 0. Caught, that
        // is a durable decode error like any other (0.9.3 crashed here on every pass).
        try LJSafeAudioFile.setFramePosition(requestedFrame, of: file)
        let inputFrames = AVAudioFrameCount((10 * inputFormat.sampleRate).rounded(.up))
        guard let input = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: inputFrames) else {
            return nil
        }
        try LJSafeAudioFile.read(file, into: input, frameCount: inputFrames)
        return try convert(input)
    }

    /// The same window as `decodeFileWindow`, read through `AVAssetReader`. The reader is asked for the song's own
    /// rate and channels as float32; the window starts at the same frame and holds the same number of frames at most.
    static func decodeAssetWindow(url: URL, startMs: Int64) throws -> DecodedWindow? {
        try autoreleasepool {
            // Without precise timing an MP3's time range is placed by estimate, not by frame.
            let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            if asset.hasProtectedContent { throw AudioWindowError.protectedContent }
            guard let track = try audioTrack(of: asset) else { throw AudioWindowError.noAudioTrack }
            let startMs = max(0, startMs)
            let trackStart = track.timeRange.start
            // A window that begins after the song's end is skipped, as for a file.
            guard CMTimeCompare(
                CMTimeAdd(trackStart, CMTime(value: startMs, timescale: 1_000)), CMTimeRangeGetEnd(track.timeRange)
            ) < 0 else { return nil }
            // The reader starts decoding at the packet that holds its first sample. An MP3 frame needs the frames
            // before it, so up to a second is decoded ahead of the window and dropped: measured without it, the
            // first 16 ms of a window differed from what AVAudioFile reads at the same place. The range also ends
            // a little late; which frames are the window is decided below, from the rate the samples arrive at.
            let leadMs = min(startMs, 1_000)

            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 32,
                AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            guard reader.canAdd(output) else { throw AudioWindowError.readerRefused }
            reader.add(output)
            reader.timeRange = CMTimeRange(
                start: CMTimeAdd(trackStart, CMTime(value: startMs - leadMs, timescale: 1_000)),
                duration: CMTime(value: leadMs + 10_000 + 100, timescale: 1_000)
            )
            guard reader.startReading() else { throw reader.error ?? AudioWindowError.readerRefused }

            var input: AVAudioPCMBuffer?
            var bytesPerFrame = 0
            var firstFrame: Int64 = 0
            var windowFrames: Int64 = 0
            while let sample = output.copyNextSampleBuffer() {
                if input == nil {
                    guard let description = CMSampleBufferGetFormatDescription(sample) else { continue }
                    guard let format = pcmFormat(of: description),
                          format.commonFormat == .pcmFormatFloat32,
                          format.isInterleaved || format.channelCount == 1
                    else {
                        reader.cancelReading()
                        throw AudioWindowError.readerRefused
                    }
                    // The rate of the decoded samples, which for HE-AAC is twice the rate the track names: the
                    // same rate, and so the same first frame and frame count, as AVAudioFile's processing format.
                    firstFrame = Int64(Double(startMs) * format.sampleRate / 1_000)
                    let inputFrames = AVAudioFrameCount((10 * format.sampleRate).rounded(.up))
                    // Not past the song's end either: after an MP3's last frame the reader may hand over the
                    // encoder's padding, which AVAudioFile leaves out of the file's length.
                    let songFrames = CMTimeConvertScale(
                        track.timeRange.duration,
                        timescale: CMTimeScale(format.sampleRate.rounded()),
                        method: .roundHalfAwayFromZero
                    ).value
                    windowFrames = min(Int64(inputFrames), songFrames - firstFrame)
                    input = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: inputFrames)
                    bytesPerFrame = Int(format.streamDescription.pointee.mBytesPerFrame)
                }
                guard let input, bytesPerFrame > 0 else {
                    reader.cancelReading()
                    return nil
                }
                // Where this buffer begins, in frames from the window's first: below zero while the lead runs.
                let begins = CMTimeConvertScale(
                    CMTimeSubtract(CMSampleBufferGetPresentationTimeStamp(sample), trackStart),
                    timescale: CMTimeScale(input.format.sampleRate.rounded()),
                    method: .roundHalfAwayFromZero
                ).value - firstFrame
                let available = CMSampleBufferGetNumSamples(sample)
                let skipped = Int(min(Int64(available), max(0, -begins)))
                if skipped == available { continue }
                let frames = min(available - skipped, Int(windowFrames - Int64(input.frameLength)))
                guard frames > 0 else {
                    reader.cancelReading()
                    break
                }
                guard let block = CMSampleBufferGetDataBuffer(sample),
                      let destination = input.mutableAudioBufferList.pointee.mBuffers.mData else { continue }
                let status = CMBlockBufferCopyDataBytes(
                    block,
                    atOffset: skipped * bytesPerFrame,
                    dataLength: frames * bytesPerFrame,
                    destination: destination + Int(input.frameLength) * bytesPerFrame
                )
                guard status == kCMBlockBufferNoErr else {
                    reader.cancelReading()
                    throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
                }
                input.frameLength += AVAudioFrameCount(frames)
            }
            if reader.status == .failed { throw reader.error ?? AudioWindowError.readerRefused }
            guard let input else { return nil }
            return try convert(input)
        }
    }

    /// The first audio track, or the error that kept the asset's tracks from loading.
    private static func audioTrack(of asset: AVURLAsset) throws -> AVAssetTrack? {
        let loaded = DispatchSemaphore(value: 0)
        asset.loadValuesAsynchronously(forKeys: ["tracks"]) { loaded.signal() }
        loaded.wait()
        var error: NSError?
        if asset.statusOfValue(forKey: "tracks", error: &error) == .failed, let error { throw error }
        return asset.tracks(withMediaType: .audio).first
    }

    /// The reader's samples as AVAudioConverter takes them. A file of more than two channels that does not say
    /// which they are gets the layout AVAudioFile gives such a file, so that both paths mix it down alike.
    private static func pcmFormat(of description: CMAudioFormatDescription) -> AVAudioFormat? {
        guard let basic = CMAudioFormatDescriptionGetStreamBasicDescription(description) else { return nil }
        var size = 0
        if let layout = CMAudioFormatDescriptionGetChannelLayout(description, sizeOut: &size) {
            return AVAudioFormat(streamDescription: basic, channelLayout: AVAudioChannelLayout(layout: layout))
        }
        let channels = basic.pointee.mChannelsPerFrame
        if channels <= 2 { return AVAudioFormat(streamDescription: basic) }
        guard let layout = AVAudioChannelLayout(layoutTag: kAudioChannelLayoutTag_DiscreteInOrder | channels) else {
            return nil
        }
        return AVAudioFormat(streamDescription: basic, channelLayout: layout)
    }

    /// Up to 10 s of decoded audio in its own format, to the model's 32 kHz mono window.
    private static func convert(_ input: AVAudioPCMBuffer) throws -> DecodedWindow? {
        guard input.frameLength > 0,
              let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: 32_000,
                channels: 1,
                interleaved: false
              ),
              let converter = AVAudioConverter(from: input.format, to: outputFormat),
              let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: 320_000)
        else { return nil }

        var supplied = false
        var conversionError: NSError?
        converter.convert(to: output, error: &conversionError) { _, status in
            if supplied {
                status.pointee = .endOfStream
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return input
        }
        if let conversionError { throw conversionError }
        guard let channel = output.floatChannelData?[0] else { return nil }
        var waveform = [Float](repeating: 0, count: 320_000)
        let count = min(Int(output.frameLength), waveform.count)
        waveform.withUnsafeMutableBufferPointer { destination in
            destination.baseAddress?.update(from: channel, count: count)
        }
        // AVAudioConverter can also produce finite overshoot. Keep the shared waveform contract
        // identical to Android before running the FP16-weight audio graph.
        for index in 0..<count {
            let sample = waveform[index]
            waveform[index] = sample.isFinite ? min(1, max(-1, sample)) : 0
        }
        return DecodedWindow(waveform: waveform, valid: count)
    }

    static func classify(_ error: Error, url: URL) -> DecodeFailureClass {
        let nsError = error as NSError
        // A Music library song has no path to ask the file system about: the error itself decides.
        if !url.isFileURL { return classifyAssetError(nsError) }
        let fileManager = FileManager.default
        // Missing/unreadable/protected storage and explicit permission failures may recover while
        // the library descriptor remains unchanged.
        if !fileManager.fileExists(atPath: url.path)
            || !fileManager.isReadableFile(atPath: url.path)
            || nsError.domain == NSCocoaErrorDomain && [
                NSFileNoSuchFileError,
                NSFileReadNoPermissionError,
                NSFileReadNoSuchFileError,
                NSFileReadUnknownError,
            ].contains(nsError.code)
        {
            return .unavailable
        }
        // Allocation/resource pressure is also temporary. AVFoundation's remaining failures for
        // a present, readable local file are media-format/read/conversion facts and are durable.
        if nsError.domain == NSCocoaErrorDomain && nsError.code == NSFileReadTooLargeError {
            return .unavailable
        }
        return .invalidAudio
    }

    /// Media services that were reset or taken away, an interrupted or unauthorized read, a busy decoder and memory
    /// pressure pass; protected content, a song with no audio track and every format or decoder error stay with the song.
    private static func classifyAssetError(_ error: NSError) -> DecodeFailureClass {
        if error.domain == AVFoundationErrorDomain && passingAssetErrors.contains(error.code) { return .unavailable }
        if error.domain == NSCocoaErrorDomain && [
            NSFileReadNoPermissionError,
            NSFileReadTooLargeError,
        ].contains(error.code) {
            return .unavailable
        }
        if error.domain == NSPOSIXErrorDomain && error.code == Int(ENOMEM) { return .unavailable }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError,
           classifyAssetError(underlying) == .unavailable
        {
            return .unavailable
        }
        return .invalidAudio
    }
}

#if DEBUG
extension IosAudioWindowDecoder {
    /// The largest difference between the two paths that counts as the same window: a third of one 16-bit step.
    /// Measured on a Mac over WAV, Apple Lossless and MP3 the windows were equal bit for bit, and over AAC they
    /// differed by at most 7.2e-7 (a few float steps in a few samples). HE-AAC is left out: after a seek its
    /// decoder does not repeat itself even on one path (AVAudioFile against its own unbroken read: up to 0.06).
    static let readerTolerance: Float = 1e-5

    /// Reads the same files through `decodeFileWindow` and `decodeAssetWindow` and compares every window. The
    /// Simulator has no Music library, so this is the only place the reader path runs without a phone.
    static func compareReaders() -> String {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("latentjam-readers-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let files: [(String, [String: Any])] = [
                ("pcm.wav", [AVFormatIDKey: kAudioFormatLinearPCM, AVLinearPCMBitDepthKey: 16,
                             AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]),
                ("aac.m4a", [AVFormatIDKey: kAudioFormatMPEG4AAC]),
                ("alac.m4a", [AVFormatIDKey: kAudioFormatAppleLossless]),
            ]
            var windows = 0
            var largest: Float = 0
            for (name, format) in files {
                let url = folder.appendingPathComponent(name)
                try writeTestTone(to: url, format: format)
                // Inside, at the three places of a 25 s song, a 6 s tail, and past the end.
                for start: Int64 in [0, 3_000, 7_500, 12_000, 19_000, 25_500] {
                    let file = try decodeFileWindow(url: url, startMs: start)
                    let asset = try decodeAssetWindow(url: url, startMs: start)
                    guard let file, let asset else {
                        if file == nil && asset == nil { continue }
                        return "readers failed: \(name) at \(start)ms file=\(file?.valid ?? -1) reader=\(asset?.valid ?? -1)"
                    }
                    guard file.valid == asset.valid else {
                        return "readers failed: \(name) at \(start)ms valid file=\(file.valid) reader=\(asset.valid)"
                    }
                    var difference: Float = 0
                    for index in file.waveform.indices {
                        difference = max(difference, abs(file.waveform[index] - asset.waveform[index]))
                    }
                    guard difference <= readerTolerance else {
                        return "readers failed: \(name) at \(start)ms max_difference=\(difference)"
                    }
                    largest = max(largest, difference)
                    windows += 1
                }
            }
            return "readers ok files=\(files.count) windows=\(windows) max_difference=\(largest)"
        } catch {
            return "readers failed: \(error.localizedDescription)"
        }
    }

    /// 25 s of two different tones, left and right, at 44.1 kHz. Never played.
    private static func writeTestTone(to url: URL, format: [String: Any]) throws {
        let sampleRate = 44_100.0
        var settings = format
        settings[AVSampleRateKey] = sampleRate
        settings[AVNumberOfChannelsKey] = 2
        let file = try AVAudioFile(
            forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false
        )
        let frames = AVAudioFrameCount(25 * sampleRate)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: frames),
              let channels = buffer.floatChannelData else { throw AudioWindowError.readerRefused }
        for frame in 0..<Int(frames) {
            let time = Double(frame) / sampleRate
            channels[0][frame] = Float(0.5 * sin(2 * .pi * (220 + 9 * time) * time) + 0.2 * sin(2 * .pi * 1_870 * time))
            channels[1][frame] = Float(0.4 * sin(2 * .pi * (330 + 5 * time) * time) + 0.3 * sin(2 * .pi * 5_200 * time))
        }
        buffer.frameLength = frames
        try file.write(from: buffer)
    }
}
#endif
