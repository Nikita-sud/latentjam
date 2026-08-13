/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import AVFoundation
import ComposeApp
import Foundation

/// Runs every SMART graph in-process. No audio, tags, or embeddings leave the device.
final class IosOnnxInferenceProvider: NSObject, SmartIosInferenceProvider {
    private let runtime = OrtRuntime()
    private var audio: OrtModel?
    private var semantic: OrtModel?
    private var text: OrtModel?
    private var state: OrtModel?
    private var scorer: OrtModel?

    func loadAudio() -> String? {
        do {
            if audio == nil { audio = try runtime.loadModel(named: "mnv4_audio") }
            return nil
        } catch { return error.localizedDescription }
    }

    func embedAudio(
        uri: String,
        durationMs: Int64,
        outputDim: Int32
    ) -> SmartIosAudioEmbeddingResult {
        guard loadAudio() == nil, let audio else {
            return audioFailure(.backendFailure, "Audio model is unavailable")
        }
        do {
            let url: URL
            do {
                url = try localURL(uri)
            } catch {
                // A file can become reachable again without its library identity changing (for
                // example after permission/storage restoration), so access failures are retryable.
                return audioFailure(.unavailable, error.localizedDescription)
            }
            let starts = windowStarts(durationMs: durationMs)
            var pooled = [Float](repeating: 0, count: Int(outputDim))
            var used = 0
            var decoded = 0
            var invalidEmbedding = false
            var invalidDecode: String?
            var unavailableDecode: String?
            for start in starts {
                let waveform: [Float]?
                do {
                    waveform = try decodeWindow(url: url, startMs: start)
                } catch {
                    switch classifyAudioDecodeError(error, url: url) {
                    case .invalidAudio:
                        invalidDecode = invalidDecode ?? error.localizedDescription
                    case .unavailable:
                        unavailableDecode = unavailableDecode ?? error.localizedDescription
                    }
                    continue
                }
                guard let waveform else { continue }
                decoded += 1
                guard let embedding = try stableAudioEmbedding(
                    audio, waveform: waveform, outputDim: Int(outputDim)
                ) else {
                    invalidEmbedding = true
                    continue
                }
                for index in pooled.indices { pooled[index] += embedding[index] }
                used += 1
            }
            if used == 0, durationMs > 10_000 {
                let waveform: [Float]?
                do {
                    waveform = try decodeWindow(url: url, startMs: 0)
                } catch {
                    switch classifyAudioDecodeError(error, url: url) {
                    case .invalidAudio:
                        invalidDecode = invalidDecode ?? error.localizedDescription
                    case .unavailable:
                        unavailableDecode = unavailableDecode ?? error.localizedDescription
                    }
                    waveform = nil
                }
                if let waveform {
                    decoded += 1
                    if let embedding = try stableAudioEmbedding(
                        audio, waveform: waveform, outputDim: Int(outputDim)
                    ) {
                        for index in pooled.indices { pooled[index] += embedding[index] }
                        used = 1
                    } else {
                        invalidEmbedding = true
                    }
                }
            }
            guard used > 0 else {
                // A single temporary crop failure prevents deterministic outcomes from other
                // crops becoming a durable marker.
                if let unavailableDecode {
                    return audioFailure(.unavailable, unavailableDecode)
                }
                let detail = invalidEmbedding
                    ? "Audio model produced no usable embedding"
                    : invalidDecode != nil
                        ? invalidDecode!
                    : decoded == 0
                        ? "Audio contained no decodable PCM window"
                        : "Audio produced no usable window"
                return audioFailure(.invalidAudio, detail)
            }
            normalize(&pooled)
            guard isUsable(pooled) else {
                return audioFailure(.invalidAudio, "Pooled audio embedding was unusable")
            }
            return SmartIosAudioEmbeddingResult(
                status: .success,
                embedding: KotlinFloatArray(pooled),
                technicalDetail: nil
            )
        } catch {
            // Only ORT inference throws from the remaining do scope. A session/runtime/model
            // failure must stay retryable and must never create a per-track durable marker.
            return audioFailure(.backendFailure, error.localizedDescription)
        }
    }

    private func audioFailure(
        _ status: SmartIosAudioEmbeddingStatus,
        _ detail: String
    ) -> SmartIosAudioEmbeddingResult {
        SmartIosAudioEmbeddingResult(
            status: status,
            embedding: nil,
            technicalDetail: detail
        )
    }

    private enum DecodeFailureClass {
        case invalidAudio
        case unavailable
    }

    private func classifyAudioDecodeError(_ error: Error, url: URL) -> DecodeFailureClass {
        let nsError = error as NSError
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

    func loadSemantic() -> String? {
        do {
            if semantic == nil {
                semantic = try runtime.loadModel(named: "universal_semantic_head")
            }
            return nil
        } catch { return error.localizedDescription }
    }

    func classifySemantics(
        embeddings: KotlinFloatArray,
        batchSize: Int32,
        inputDim: Int32,
        outputDim: Int32
    ) -> KotlinFloatArray? {
        guard batchSize > 0, inputDim > 0, outputDim > 0,
              loadSemantic() == nil, let semantic else { return nil }
        let values = embeddings.swiftArray
        guard values.count == Int(batchSize * inputDim) else { return nil }
        do {
            return KotlinFloatArray(try semantic.run(
                floats: [
                    (
                        "embedding",
                        values,
                        [Int64(batchSize), Int64(inputDim)]
                    ),
                ],
                int64s: [],
                output: "semantic_scores",
                outputCount: Int(batchSize * outputDim)
            ))
        } catch { return nil }
    }

    func loadText() -> String? {
        do {
            if text == nil { text = try runtime.loadModel(named: "text_encoder_minilm") }
            return nil
        } catch { return error.localizedDescription }
    }

    func encodeText(inputIds: KotlinLongArray) -> KotlinFloatArray? {
        guard loadText() == nil, let text else { return nil }
        do {
            let ids = inputIds.swiftArray
            guard !ids.isEmpty else { return nil }
            let ones = [Int64](repeating: 1, count: ids.count)
            let zeros = [Int64](repeating: 0, count: ids.count)
            let tokens = try text.run(
                floats: [],
                int64s: [
                    ("input_ids", ids, [1, Int64(ids.count)]),
                    ("attention_mask", ones, [1, Int64(ids.count)]),
                    ("token_type_ids", zeros, [1, Int64(ids.count)]),
                ],
                output: "last_hidden_state", outputCount: ids.count * 384
            )
            var pooled = [Float](repeating: 0, count: 384)
            for token in 0..<ids.count {
                let base = token * 384
                for dimension in pooled.indices { pooled[dimension] += tokens[base + dimension] }
            }
            let inverse = 1 / Float(ids.count)
            for index in pooled.indices { pooled[index] *= inverse }
            normalize(&pooled)
            return KotlinFloatArray(pooled)
        } catch { return nil }
    }

    func loadPredictor() -> String? {
        do {
            if state == nil { state = try runtime.loadModel(named: "predictor_state") }
            if scorer == nil { scorer = try runtime.loadModel(named: "predictor_scorer_n100") }
            return nil
        } catch { return error.localizedDescription }
    }

    func encodeState(
        historySmall: KotlinFloatArray,
        historyMedium: KotlinFloatArray,
        historyLarge: KotlinFloatArray,
        timeFeatures: KotlinFloatArray,
        sessionFeatures: KotlinFloatArray
    ) -> KotlinFloatArray? {
        guard loadPredictor() == nil, let state else { return nil }
        do {
            return KotlinFloatArray(try state.run(
                floats: [
                    ("history_small", historySmall.swiftArray, [1, 4, 961]),
                    ("history_medium", historyMedium.swiftArray, [1, 960]),
                    ("history_large", historyLarge.swiftArray, [1, 960]),
                    ("time_features", timeFeatures.swiftArray, [1, 5]),
                    ("session_features", sessionFeatures.swiftArray, [1, 5]),
                ], int64s: [], output: "state", outputCount: 960
            ))
        } catch { return nil }
    }

    func score(
        state: KotlinFloatArray,
        candidates: KotlinFloatArray
    ) -> KotlinFloatArray? {
        guard loadPredictor() == nil, let scorer else { return nil }
        do {
            // Fused scorer: state = [960 audio ⊕ 384 text-centroid] = 1344,
            // candidates = [100 × 1344]. See ScorerPacking on the Kotlin side.
            return KotlinFloatArray(try scorer.run(
                floats: [
                    ("state", state.swiftArray, [1, 1344]),
                    ("candidates", candidates.swiftArray, [1, 100, 1344]),
                ], int64s: [], output: "scores", outputCount: 100
            ))
        } catch { return nil }
    }

    func close() {
        audio = nil
        semantic = nil
        text = nil
        state = nil
        scorer = nil
    }

    /// Opt-in end-to-end graph check used by simulator validation. Normal app launches do no work.
    func runSmokeIfRequested() {
        guard ProcessInfo.processInfo.environment["LATENTJAM_SMART_SMOKE"] == "1" else { return }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self else { return }
            let started = CFAbsoluteTimeGetCurrent()
            do {
                if let error = self.loadAudio() { throw OrtError.message(error) }
                if let error = self.loadText() { throw OrtError.message(error) }
                if let error = self.loadPredictor() { throw OrtError.message(error) }
                guard let audio = self.audio, let text = self.text,
                      let state = self.state, let scorer = self.scorer else {
                    throw OrtError.message("One or more SMART sessions did not load")
                }
                let audioEmbedding = try audio.run(
                    floats: [("waveform", [Float](repeating: 0, count: 320_000), [1, 320_000])],
                    int64s: [], output: "embedding", outputCount: 960
                )
                let textTokens = try text.run(
                    floats: [], int64s: [
                        ("input_ids", [101, 102], [1, 2]),
                        ("attention_mask", [1, 1], [1, 2]),
                        ("token_type_ids", [0, 0], [1, 2]),
                    ], output: "last_hidden_state", outputCount: 2 * 384
                )
                let sessionState = try state.run(
                    floats: [
                        ("history_small", [Float](repeating: 0, count: 4 * 961), [1, 4, 961]),
                        ("history_medium", [Float](repeating: 0, count: 960), [1, 960]),
                        ("history_large", [Float](repeating: 0, count: 960), [1, 960]),
                        ("time_features", [Float](repeating: 0, count: 5), [1, 5]),
                        ("session_features", [Float](repeating: 0, count: 5), [1, 5]),
                    ], int64s: [], output: "state", outputCount: 960
                )
                // Fused scorer: state = [960 ⊕ 384] = 1344, candidates = [100 × 1344]. An all-zero
                // text half exercises the trained text-dropout path (graceful degradation).
                let fusedState = [Float](repeating: 0, count: 1344)
                let candidates = [Float](repeating: 0, count: 100 * 1344)
                let scores = try scorer.run(
                    floats: [
                        ("state", fusedState, [1, 1344]),
                        ("candidates", candidates, [1, 100, 1344]),
                    ], int64s: [], output: "scores", outputCount: 100
                )
                guard audioEmbedding.allSatisfy(\.isFinite),
                      textTokens.allSatisfy(\.isFinite),
                      sessionState.allSatisfy(\.isFinite), scores.allSatisfy(\.isFinite) else {
                    throw OrtError.message("A SMART graph failed finiteness checks")
                }
                let elapsed = Int((CFAbsoluteTimeGetCurrent() - started) * 1_000)
                print("[SMART_SMOKE] ok audio=960 text=384 state=1344 scores=100 elapsed_ms=\(elapsed)")
            } catch {
                print("[SMART_SMOKE] failed: \(error.localizedDescription)")
            }
        }
    }

    private func localURL(_ value: String) throws -> URL {
        if let url = URL(string: value), url.isFileURL { return url }
        let url = URL(fileURLWithPath: value)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw OrtError.message("Audio file is not locally readable")
        }
        return url
    }

    private func windowStarts(durationMs: Int64) -> [Int64] {
        guard durationMs > 10_000 else { return [0] }
        let span = durationMs - 10_000
        return [0.2, 0.5, 0.8].map { Int64(Double(span) * $0) }
    }

    private func decodeWindow(url: URL, startMs: Int64) throws -> [Float]? {
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
        file.framePosition = requestedFrame
        let inputFrames = AVAudioFrameCount((10 * inputFormat.sampleRate).rounded(.up))
        guard let input = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: inputFrames) else {
            return nil
        }
        try file.read(into: input, frameCount: inputFrames)
        guard input.frameLength > 0,
              let outputFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: 32_000,
                channels: 1,
                interleaved: false
              ),
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat),
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
        return waveform
    }

    private func stableAudioEmbedding(
        _ model: OrtModel,
        waveform original: [Float],
        outputDim: Int
    ) throws -> [Float]? {
        var waveform = original
        var appliedGain: Float = 1
        for targetGain: Float in [1, 0.5, 0.25] {
            let scale = targetGain / appliedGain
            if scale != 1 {
                for index in waveform.indices { waveform[index] *= scale }
            }
            appliedGain = targetGain
            let embedding = try model.run(
                floats: [("waveform", waveform, [1, 320_000])],
                int64s: [], output: "embedding", outputCount: outputDim
            )
            if isUsable(embedding) { return embedding }
        }
        return nil
    }

    private func isUsable(_ values: [Float]) -> Bool {
        var normSquared: Double = 0
        for value in values {
            guard value.isFinite else { return false }
            normSquared += Double(value * value)
        }
        return normSquared.isFinite && normSquared > 1e-12
    }

    private func normalize(_ values: inout [Float]) {
        let norm = sqrt(values.reduce(0) { $0 + $1 * $1 })
        if norm > 1e-12 { for index in values.indices { values[index] /= norm } }
    }
}

private typealias FloatInput = (name: String, values: [Float], shape: [Int64])
private typealias Int64Input = (name: String, values: [Int64], shape: [Int64])

private final class OrtRuntime {
    let api: UnsafePointer<OrtApi>
    let environment: OpaquePointer
    let memoryInfo: OpaquePointer

    init() {
        api = OrtGetApiBase()!.pointee.GetApi(UInt32(ORT_API_VERSION))!
        var environment: OpaquePointer?
        var memoryInfo: OpaquePointer?
        do {
            try OrtRuntime.check(api, api.pointee.CreateEnv(
                ORT_LOGGING_LEVEL_WARNING, "LatentJam", &environment
            ))
            try OrtRuntime.check(api, api.pointee.CreateCpuMemoryInfo(
                OrtArenaAllocator, OrtMemTypeDefault, &memoryInfo
            ))
        } catch {
            fatalError("ONNX Runtime initialization failed: \(error)")
        }
        self.environment = environment!
        self.memoryInfo = memoryInfo!
    }

    deinit {
        api.pointee.ReleaseMemoryInfo(memoryInfo)
        api.pointee.ReleaseEnv(environment)
    }

    func loadModel(named name: String) throws -> OrtModel {
        guard let path = Bundle.main.path(forResource: name, ofType: "onnx", inDirectory: "ml")
            ?? Bundle.main.path(forResource: name, ofType: "onnx") else {
            throw OrtError.message("Bundled model \(name).onnx is missing")
        }
        var options: OpaquePointer?
        try Self.check(api, api.pointee.CreateSessionOptions(&options))
        defer { api.pointee.ReleaseSessionOptions(options) }
        // Indexing is intentionally background work. One sequential worker keeps ONNX from
        // occupying the cores Core Animation needs while the listener browses the library.
        try Self.check(api, api.pointee.SetIntraOpNumThreads(options, 1))
        try Self.check(api, api.pointee.SetInterOpNumThreads(options, 1))
        var session: OpaquePointer?
        try path.withCString {
            try Self.check(api, api.pointee.CreateSession(environment, $0, options, &session))
        }
        return OrtModel(runtime: self, session: session!)
    }

    static func check(_ api: UnsafePointer<OrtApi>, _ status: OpaquePointer?) throws {
        guard let status else { return }
        defer { api.pointee.ReleaseStatus(status) }
        let message = api.pointee.GetErrorMessage(status).map(String.init(cString:))
            ?? "Unknown ONNX Runtime error"
        throw OrtError.message(message)
    }
}

private final class OrtModel {
    private unowned let runtime: OrtRuntime
    private let session: OpaquePointer

    init(runtime: OrtRuntime, session: OpaquePointer) {
        self.runtime = runtime
        self.session = session
    }

    deinit { runtime.api.pointee.ReleaseSession(session) }

    func run(
        floats: [FloatInput],
        int64s: [Int64Input],
        output: String,
        outputCount: Int
    ) throws -> [Float] {
        let api = runtime.api
        var values: [OpaquePointer?] = []
        var allocations: [UnsafeMutableRawPointer] = []
        defer {
            values.forEach { if let value = $0 { api.pointee.ReleaseValue(value) } }
            allocations.forEach { $0.deallocate() }
        }
        for input in floats {
            let pointer = UnsafeMutablePointer<Float>.allocate(capacity: input.values.count)
            pointer.initialize(from: input.values, count: input.values.count)
            allocations.append(UnsafeMutableRawPointer(pointer))
            var value: OpaquePointer?
            try input.shape.withUnsafeBufferPointer { shape in
                try OrtRuntime.check(api, api.pointee.CreateTensorWithDataAsOrtValue(
                    runtime.memoryInfo,
                    UnsafeMutableRawPointer(pointer),
                    input.values.count * MemoryLayout<Float>.stride,
                    shape.baseAddress, shape.count,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value
                ))
            }
            values.append(value)
        }
        for input in int64s {
            let pointer = UnsafeMutablePointer<Int64>.allocate(capacity: input.values.count)
            pointer.initialize(from: input.values, count: input.values.count)
            allocations.append(UnsafeMutableRawPointer(pointer))
            var value: OpaquePointer?
            try input.shape.withUnsafeBufferPointer { shape in
                try OrtRuntime.check(api, api.pointee.CreateTensorWithDataAsOrtValue(
                    runtime.memoryInfo,
                    UnsafeMutableRawPointer(pointer),
                    input.values.count * MemoryLayout<Int64>.stride,
                    shape.baseAddress, shape.count,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &value
                ))
            }
            values.append(value)
        }

        let allInputNames: [String] = floats.map { $0.name } + int64s.map { $0.name }
        let inputNameStorage: [UnsafeMutablePointer<CChar>?] = allInputNames.map { name in
            strdup(name)
        }
        let outputNameStorage: [UnsafeMutablePointer<CChar>?] = [strdup(output)]
        let inputNames: [UnsafePointer<CChar>?] = inputNameStorage.map { pointer in
            guard let pointer else { return nil }
            return UnsafePointer<CChar>(pointer)
        }
        let outputNames: [UnsafePointer<CChar>?] = outputNameStorage.map { pointer in
            guard let pointer else { return nil }
            return UnsafePointer<CChar>(pointer)
        }
        defer {
            inputNameStorage.forEach { free($0) }
            outputNameStorage.forEach { free($0) }
        }
        var outputValue: OpaquePointer?
        try inputNames.withUnsafeBufferPointer { names in
            try values.withUnsafeBufferPointer { tensors in
                try outputNames.withUnsafeBufferPointer { outputs in
                    try OrtRuntime.check(api, api.pointee.Run(
                        session, nil,
                        names.baseAddress,
                        tensors.baseAddress, values.count,
                        outputs.baseAddress, 1, &outputValue
                    ))
                }
            }
        }
        guard let outputValue else { throw OrtError.message("Model returned no output") }
        defer { api.pointee.ReleaseValue(outputValue) }
        var raw: UnsafeMutableRawPointer?
        try OrtRuntime.check(api, api.pointee.GetTensorMutableData(outputValue, &raw))
        guard let floats = raw?.assumingMemoryBound(to: Float.self) else {
            throw OrtError.message("Model output was not float32")
        }
        return Array(UnsafeBufferPointer(start: floats, count: outputCount))
    }
}

private enum OrtError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        if case let .message(value) = self { return value }
        return "Local inference failed"
    }
}

private extension KotlinFloatArray {
    convenience init(_ values: [Float]) {
        self.init(size: Int32(values.count))
        for (index, value) in values.enumerated() { set(index: Int32(index), value: value) }
    }

    var swiftArray: [Float] {
        (0..<Int(size)).map { get(index: Int32($0)) }
    }
}

private extension KotlinLongArray {
    var swiftArray: [Int64] {
        (0..<Int(size)).map { get(index: Int32($0)) }
    }
}
