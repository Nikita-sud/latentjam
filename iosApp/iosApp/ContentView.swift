/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
import SwiftUI
import ComposeApp

/// Hosts the shared Compose UI. The entire app lives on the Kotlin side
/// (`MainViewController()` in composeApp/iosMain); this Swift shell only
/// bridges it into the SwiftUI lifecycle.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(edges: .all) // Compose draws edge-to-edge and manages insets itself.
    }
}
