// SPDX-License-Identifier: Apache-2.0
import ComposeApp
import Foundation
import LatentJamMap
import os

private final class MapCancellationContext {
    let callback: SmartMapCancellation
    init(_ callback: SmartMapCancellation) { self.callback = callback }
}

final class IosNativeMapEmbeddingProvider: NSObject, SmartIosMapEmbeddingProvider {
    func availableMemoryBytes() -> Int64 {
        // iOS's current process headroom accounts for native allocations and the jetsam limit.
        let headroom = Int64(clamping: os_proc_available_memory())
        return min(128 * 1024 * 1024, max(0, headroom - 32 * 1024 * 1024))
    }

    func embed(rows: KotlinFloatArray, n: Int32, dim: Int32, initial: KotlinFloatArray?,
               output: KotlinFloatArray, cancellation: SmartMapCancellation) -> Int32 {
        guard n >= 61, n <= 3000, dim >= 1, dim <= 50,
              rows.size == n * dim, output.size == n * 2,
              initial == nil || initial!.size == n * 2 else { return 2 }
        let input = (0..<rows.size).map { rows.get(index: $0) }
        let warm = initial.map { values in (0..<values.size).map { values.get(index: $0) } }
        var result = [Float](repeating: 0, count: Int(n * 2))
        let context = MapCancellationContext(cancellation)
        let pointer = Unmanaged.passUnretained(context).toOpaque()
        let active: @convention(c) (UnsafeMutableRawPointer?) -> Int32 = { opaque in
            guard let opaque else { return 0 }
            return Unmanaged<MapCancellationContext>.fromOpaque(opaque).takeUnretainedValue().callback.isActive() ? 1 : 0
        }
        let status: Int32 = withExtendedLifetime(context) {
            input.withUnsafeBufferPointer { source in
                result.withUnsafeMutableBufferPointer { destination in
                    if let warm {
                        return warm.withUnsafeBufferPointer {
                            LjMapEmbed(source.baseAddress, n, dim, $0.baseAddress, destination.baseAddress, active, pointer)
                        }
                    }
                    return LjMapEmbed(source.baseAddress, n, dim, nil, destination.baseAddress, active, pointer)
                }
            }
        }
        if status == 0 {
            for i in 0..<output.size { output.set(index: i, value: result[Int(i)]) }
        }
        return status
    }
}

