/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        IosInferenceBridgeKt.installIosMapEmbeddingProvider(provider: IosNativeMapEmbeddingProvider())
        let provider = IosOnnxInferenceProvider()
        IosInferenceBridgeKt.installIosInferenceProvider(
            provider: provider
        )
        provider.runSmokeIfRequested()
        // BGTaskScheduler needs its handlers before launch finishes (Map deferred rebuild).
        IosMapRebuildTaskKt.registerMapRebuildTask()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
