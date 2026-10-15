# Android APK size regression against 0.9.0

The exact locally verified 0.9.0 ARM64 release was found at
`/Users/nichitabulgaru/Documents/LJ/releases/0.9.0/final/LatentJam-v0.9.0-arm64.apk`.
Its size and SHA256 match that directory's `build-manifest.json` and `SHA256SUMS`.
The manifest's source commit matches the annotated `v0.9.0` tag:
`68496c56ab471053fa519171861571ad1448e0ff`. This is stronger evidence than older
0.8.1 build-directory APKs that happen to round to the same 34.2 MiB.

| Artifact inspected | Bytes | MiB | SHA256 |
|---|---:|---:|---|
| Exact 0.9.0 ARM64 release | 35,892,644 | 34.2299 | `3201216384ac4708a97824cf1bc7cae35d172f3455a85ebf29091bb5a1bf42ca` |
| 0.9.1 full-runtime UI candidate inspected on October 15 | 52,956,542 | 50.5033 | `74c732eb2064184c188a746aa8b90fd75f782bb8bd686f38b1ab376308b8cf12` |

These identify the inspected files, not every APK carrying those version names.
The second file was the reliability worktree's `androidApp/build/outputs/apk/release/androidApp-arm64-v8a-release.apk`.
A later build must receive a new recorded hash.

## What accounts for the 17,063,898-byte difference

The libraries are stored uncompressed in these ARM64 APKs. Other rows below use
ZIP entry compressed sizes, so the rows reconcile to the actual download size.

| Change | Additional bytes |
|---|---:|
| `libonnxruntime.so`: reduced 11,875,984 → full 27,408,600 | 15,532,616 |
| ORT JNI: 83,120 → 83,208 | 88 |
| New shared EQ/loudness library | 416,760 |
| New native map backend library | 1,027,560 |
| DEX | 34,902 |
| `assets/ml/` packaging | 1,472 |
| Other ZIP entries | 27,341 |
| ZIP metadata, alignment and signing overhead | 23,159 |
| **Total** | **17,063,898** |

The runtime substitution accounts for **91.0% of the increase**. All three ONNX
model files are byte-identical between these APKs; the small `assets/ml/` delta
is not larger neural networks. `libljq4.so` and the AndroidX native path library
have unchanged sizes.

The 0.9.0 release verification explicitly records a reduced-ORT build, and the
historical release command included `tools/runtime/use-reduced-ort.init.gradle`.
The reliability branch's ordinary `assembleRelease` uses the official full ORT
AAR. Omitting the opt-in substitution therefore explains the large increase;
it is not caused primarily by UI work, EQ, or the loudness meter.

## Preventing a silent return to the full runtime

`tools/runtime/build_compact_release.py` validates the exact model manifest and
candidate/stock AARs, uses the existing strict substitution init script, runs the
release/DEX/native gates, and compares the actual packaged ORT/JNI bytes with the
candidate. Its provenance report includes APK sizes and hashes. Only ARM64 is
reduced; other shipping ABIs retain the stock runtime.

A smaller APK is not itself runtime validation. Device parity and error recovery
must pass before this candidate is installed or distributed. Restoring the reduced
runtime will not restore exactly 34.2 MiB: the EQ/loudness and map libraries are
real additions. Report the final measured size after the compact build succeeds.

## Verified compact build and phone installation

The ARM64 compact APK is **37,145,894 bytes / 35.43 MiB**, SHA256
`6fb53a7cf651d6fa32b54c6f15422fb7beb91b4e8570d487b33bccf54983d86b`.
This removes **15,810,648 bytes / 15.08 MiB** from the full-runtime UI APK.
It is **1,253,250 bytes / 1.20 MiB** larger than the exact 0.9.0 release.
Only the two ARM64 ORT libraries differ inside the full and compact APK ZIPs;
DEX, models, other native libraries and resources are byte-identical.

ORT 1.26.0 was rebuilt from release revision
`8c546c37b43caaca1fa25db430dab94b901cf277` for the exact three current models.
The generated operator set actually dropped GRU and other unused kernels;
this was a native rebuild, not a manifest-only workaround. The runtime is
11,584,056 bytes and JNI is 83,120 bytes after stripping. The candidate AAR
retains all non-ARM64 entries and Java classes byte-for-byte from stock.

On the physical S24 Ultra, stock and reduced CPU runtimes produced exactly
matching shapes and all **42,807 Float32 values across 13 cases**, including
three public FMA excerpts. Both rejected a corrupt model and invalid tensor
shape, and recovered on the next valid inference. The runner now fails on
shape/count/nonfinite/bitwise mismatches and uses fresh directories to avoid
stale outputs. These fixtures do not establish correctness for every input.

The compact wrapper passed release/DEX/native gates, packaged-library SHA256
checks and ELF/ZIP 16 KiB alignment checks. It accounts for AGP's NDK stripping
before comparing the packaged bytes. Other shipping ABIs retain stock ORT.
The checked APK was installed with `adb install -r`; installed SHA256 matches,
the library displays, and no crash/OOM was found for the new process.

[Aggregate evidence](compact-runtime-validation-2026-10-15.json) is committed.
Full local logs are in `build/reports/compact-release`,
`build/ort-parity-reduced-strict-20261015-r2` and
`build/reports/ui-compact-phone-install.json`. The local candidate, manifest,
operator list, source-build log and unstripped libraries were preserved at
`/Users/nichitabulgaru/Documents/LJ/ort-reduced/1.26.0-20261015-ui`.
No new FPS/startup claim or two-clean-build reproducibility claim is made
for this compact artifact.
