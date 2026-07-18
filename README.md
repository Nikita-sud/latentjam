# LatentJam Monorepo

Cross-platform (Android API 24+ / iOS 15+) local music player built with
Kotlin Multiplatform, with on-device next-track similarity matching
("SMART" shuffle) as its core feature.

**License: [Apache-2.0](LICENSE).** This tree is a clean-room implementation
written from scratch. It shares **no code and no git history** with the
GPL-3.0 Auxio-fork branches that live elsewhere in this repository — this
branch descends from its own parentless root commit. Nothing may be copied,
ported-by-diff, or merged across that license boundary, in either direction.

## Modules

| Module | Contents |
|---|---|
| `:core:smart` | The similarity-engine architectural layer: `SimilarityEngine` interface, Koin DI, `expect`/`actual` embedding-backend stubs for Android (ONNX Runtime, later) and iOS (Core ML / ONNX Runtime, later), and an in-memory vector index. No ML runtime dependencies yet. |
| `:core:library` | `MusicLibrary` port onto the device's music collection: MediaStore-backed on Android (permission-gated), stub on iOS pending a MusicKit/files source. |
| `:composeApp` | Shared Compose Multiplatform UI + the `AppGraph` Koin composition root. A KMP **library** (AGP 9 has no KMP application plugin), consumed by both thin shells; also produces the `ComposeApp.framework` for iOS. |
| `:androidApp` | Thin Android shell: `MainActivity` hosting the shared `App` composable. Application id `io.github.nikitasud.latentjam.kmp` so it coexists with the legacy app during development. |
| `iosApp/` | Thin SwiftUI shell (Xcode project) hosting the shared UI via `ComposeUIViewController`; builds the Kotlin framework through the `embedAndSignAppleFrameworkForXcode` script phase. |

## Building

Requires JDK 21 and (for iOS) Xcode. The Gradle daemon is disabled on
purpose (`org.gradle.daemon=false`).

```bash
./gradlew --no-daemon :core:smart:testAndroidHostTest      # run all common tests on the JVM
./gradlew --no-daemon :androidApp:assembleDebug            # Android APK
./gradlew --no-daemon :composeApp:linkDebugFrameworkIosSimulatorArm64   # iOS framework
xcodebuild -project iosApp/iosApp.xcodeproj -target iosApp \
    -sdk iphonesimulator -configuration Debug build CODE_SIGNING_ALLOWED=NO  # iOS app
```

## Status

Bootstrap stage: `:core:smart` defines the engine contract, DI wiring, and
platform seams; the app shells show a minimal engine-status screen on both
platforms. Model loading, tensor ops, audio decoding, index persistence, and
the real player UI land in later changes.
