# F-Droid for 0.9.3: per-ABI APKs and the reduced ONNX Runtime

Draft for the owner, who submits the fdroiddata merge request himself. Nothing here was pushed or submitted.

## 1. Per-ABI APKs (built and checked on release/0.9.3-full)

`androidApp/build.gradle.kts` now takes `-Platentjam.fdroidAbi=<abi>` together with `-Platentjam.fdroid=true`. Each
F-Droid build entry produces **one unsigned APK for one ABI**:

- ABI splits are on with only that ABI included;
- native libraries stay compressed (F-Droid's download size);
- no signing.

Without `fdroidAbi`, `latentjam.fdroid=true` still builds the universal APK, so the CI check of the four-ABI F-Droid
package keeps working.

**versionCode scheme**, for every ABI split in every channel (GitHub releases included): `base × 10 + digit`.

| ABI | digit | 0.9.3 example (base 43) | F-Droid APK, measured |
|---|---|---|---|
| armeabi-v7a | 1 | 431 | 33.5 MB |
| arm64-v8a | 2 | 432 | 34.4 MB |
| x86 | 3 | 433 | 36.4 MB |
| x86_64 | 4 | 434 | 36.3 MB |

The universal APK keeps the base. 0.9.2 was 16, so every split code is far above any installed release or test build
(the owner's phone runs test builds up to 43). The release's base versionCode is the owner's choice. The table uses
the current 43; if he sets it lower than 44, the phone's test build (43) will not be replaced by the 0.9.3 release
without an uninstall.

Measured: `assembleRelease -Platentjam.fdroid=true -Platentjam.fdroidAbi=<abi>` for each of the four ABIs gives one
`androidApp-<abi>-release-unsigned.apk` with versionCode 431 / 432 / 433 / 434 and only that ABI's `lib/` directory.
The plain release (`assembleRelease`) gives the three signed split APKs with 431 / 432 / 434.

## 2. Metadata draft (fdroiddata `metadata/io.github.nikitasud.latentjam.kmp.yml`)

Replace the single build entry per version with one entry per ABI. Example for 0.9.3, assuming base versionCode 43
and the tag `v0.9.3`; put the real commit hash once tagged:

```yaml
  - versionName: 0.9.3
    versionCode: 431
    commit: v0.9.3
    subdir: androidApp
    gradle:
      - yes
    prebuild: sdkmanager 'cmake;3.22.1'
    scanignore:
      - androidApp/src/main/assets/ml/artist_adapter.bin
      - androidApp/src/main/assets/ml/artist_knowledge.bin
      - androidApp/src/main/assets/ml/music_entities_250k.bin
    scandelete: &scandelete093
      - core/library/src/androidDeviceTest/resources
      - core/playback/src/androidDeviceTest/assets
      - tools/fuzz/corpus
      - core/smart/src/commonTest/resources/embedding_fixture.bin
      - core/smart/src/androidHostTest/resources/artist_known.bin
      # in the tree for the learned ranker's host tests, left out of the APK (androidResources.ignoreAssetsPattern)
      - androidApp/src/main/assets/ml/artist_colisten.bin
      - androidApp/src/main/assets/ml/artist_family.bin
      - androidApp/src/main/assets/ml/artist_family_l2.bin
      - androidApp/src/main/assets/ml/artist_lfm.bin
      - androidApp/src/main/assets/ml/artist_pop.bin
      - androidApp/src/main/assets/ml/audio_tagger.bin
      - iosApp/iosApp/ml
    ndk: r28c
    gradleprops:
      - latentjam.fdroid=true
      - latentjam.fdroidAbi=armeabi-v7a

  # versionCode 432 / 433 / 434: the same entry with latentjam.fdroidAbi=arm64-v8a / x86 / x86_64

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
VercodeOperation:
  - 10 * %c + 1
  - 10 * %c + 2
  - 10 * %c + 3
  - 10 * %c + 4
CurrentVersion: 0.9.3
CurrentVersionCode: 434
```

Notes for the merge request:

- `iosApp/iosApp/ml` is scan-deleted whole: the Android build never reads it.
- `artist_known.bin` is a host-test fixture added after 0.9.1.
- The six ranker tables are new since 0.9.1, and none of them is packaged.
- `fdroid lint`, `fdroid rewritemeta` and a local `fdroid build` were **not** run here: fdroidserver is not installed on
  this machine. Run them before submitting.

## 3. The reduced ONNX Runtime on F-Droid, built from source

Since release/0.9.3-full, every release channel packages the reduced ARM64 runtime (tools/runtime/README.md, "Every
channel"):

- `settings.gradle.kts` substitutes it into release builds from a local Maven repository;
- `checkReleaseOnnxRuntime` fails a release that would package the full ARM64 runtime.

F-Droid builds the runtime the same way the GitHub release does, with `tools/runtime/ort_source_build.sh`. That script
needs:

- no onnxruntime Python wheel: the operator list is the committed `tools/runtime/required-operators.config`;
- no prebuilt protoc: protoc is built from the protobuf sources;
- no downloads beyond source archives checked by SHA-1 (`ort_deps_mirror.py`, the 13 archives ORT's CMake fetches, about
  50 MB) and Maven Central's stock AAR (checked against `tools/runtime/onnxruntime-android-1.26.0.aar.sha256`; its Java
  classes and non-ARM64 libraries are kept).

Locally the whole step took 3 min 48 s (M-series, 6 jobs). Its ARM64 library passes the Android parity probe bitwise
against the stock runtime: 13 / 13 cases on emulator-5586, including three real audio excerpts.

**srclib** (fdroiddata `srclibs/onnxruntime.yml`):

```yaml
RepoType: git
Repo: https://github.com/microsoft/onnxruntime.git
Prepare: git submodule update --init --recursive --depth 1
```

**arm64 build entry** (versionCode base × 10 + 2). The other three ABIs need nothing new: their entry packages no
ARM64 runtime, so the check lets them through.

```yaml
  - versionName: 0.9.3
    versionCode: 432
    commit: v0.9.3
    subdir: androidApp
    srclibs:
      - onnxruntime@v1.26.0
    gradle:
      - yes
    prebuild:
      - sdkmanager 'cmake;3.31.6'
      - bash ../tools/runtime/ort_source_build.sh $$onnxruntime$$ $$NDK$$ $$SDK$$ -
    scanignore: (as the armeabi-v7a entry)
    scandelete: (as the armeabi-v7a entry)
    ndk: r28c
    gradleprops:
      - latentjam.fdroid=true
      - latentjam.fdroidAbi=arm64-v8a
```

The script writes `build/reduced-ort/maven` in the repository, the default `latentjam.reducedOrtRepo`, so no further
property is needed. Toolchain on F-Droid's image: CMake ≥ 3.28, Ninja, Python 3, JDK 17 (ORT's Java packaging step) and
NDK r28c.

**Not verified here:**

- `fdroid build` itself. fdroidserver is not installed and installing it needs a new download approval.
- The scanner's view of the srclib and the mirror.
- Whether the build server allows the mirror's downloads during prebuild. If not, the 13 archives become srclibs, and
  `ort_deps_mirror.py --offline` checks them in the same layout.

The steps the recipe runs were reproduced from a clean export of the release commit (section 4).

## 4. Reproduced locally (2026-10-28)

A clean `git archive` of release/0.9.3-full was used, with the scandelete list above removed, then the recipe's steps:

1. `bash ../tools/runtime/ort_source_build.sh <onnxruntime v1.26.0 srclib> $NDK $SDK -` from `androidApp/`. The stock
   AAR was fetched by the script and matched its pinned SHA-256; the mirror was prefilled and SHA-1-checked.
2. `../gradlew assembleRelease -Platentjam.fdroid=true -Platentjam.fdroidAbi=arm64-v8a`.

Result: one unsigned `androidApp-arm64-v8a-release-unsigned.apk`, versionCode 432, 28,555,240 bytes. Its
`lib/arm64-v8a/libonnxruntime.so` is the reduced runtime this build produced.

That library is not byte-identical to one built in another directory: build paths end up in the binary. It passed the
Android parity probe bitwise on its own, 13 / 13 cases on emulator-5586 (API 30), as did the in-tree build (API 36
and 30).

| channel | arm64-v8a | armeabi-v7a | x86 | x86_64 |
|---|---|---|---|---|
| GitHub release APK (signed, uncompressed libs) | 36,955,416 (full ORT: 53,077,360) | 44,480,220 | — | 58,841,574 |
| F-Droid APK (unsigned, compressed libs) | 28,555,240 | 33,467,080 | 36,408,096 | 36,300,138 |
| Play bundle | base/lib/arm64-v8a/libonnxruntime.so 11,278,568 (full: 27,408,600) | stock | stock | stock |

The non-ARM64 ABIs keep Maven Central's runtime. Only ARM64 is reduced.
