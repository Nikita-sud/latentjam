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

## Building

Requires JDK 21 and (for iOS targets) Xcode. The Gradle daemon is disabled
on purpose (`org.gradle.daemon=false`).

```bash
./gradlew --no-daemon :core:smart:testAndroidHostTest      # run all common tests on the JVM
./gradlew --no-daemon :core:smart:compileKotlinIosArm64 \
                      :core:smart:compileKotlinIosSimulatorArm64
./gradlew --no-daemon :core:smart:assemble                 # AAR + iOS klibs
```

## Status

Bootstrap stage: `:core:smart` defines the engine contract, DI wiring, and
platform seams. Model loading, tensor ops, audio decoding, index persistence,
and the Compose Multiplatform app shells land in later changes.
