/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    init() {
        let provider = IosOnnxInferenceProvider()
        IosInferenceBridgeKt.installIosInferenceProvider(
            provider: provider
        )
        provider.runSmokeIfRequested()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
