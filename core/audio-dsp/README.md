# Shared equalizer DSP

Android Media3 and iOS file playback use this C++17 core. The ten peaking bands, independent
100 Hz bass shelf, boost headroom, transitions, linked sample-peak limiter and response chart
share the same design. Signalsmith DSP 1.7.0 is vendored at commit
`ca390d28c41ea5108a528e2e81faa4088f2f8ac8`; its unmodified headers and MIT notice are under
`src/main/cpp/vendor/signalsmith`. The notice is also included in the shared Compose resources.
No network download is needed to build it.

## Processing contract

- Create, destroy and reset only after rendering stops. One audio consumer and one serialized
  settings producer own a stream; iOS serializes control calls outside the render callback.
- Settings cross a fixed-size triple buffer. Two stable filter banks crossfade for 20 ms;
  intermediate updates coalesce while a fade is running. Coefficients are never interpolated.
- Signalsmith uses double-precision Direct Form I cookbook filters. Q=1.4 peaking bands and
  Q=sqrt(0.5) bass shelf retain the previous Android RBJ response and preset interpretation.
- Processing and settings publication allocate no heap memory. There is no render mutex.
  Buffers and filter histories are allocated at stream creation. Android JNI processes a
  complete direct PCM buffer per call; iOS renders in C++ without a Kotlin audio callback.
- Float entry points retain a fixed `max(1, sampleRate / 200)` frame delay, even in bypass.
  Media3's PCM16 adapter removes the initial delay and drains exactly the pending input frames
  at EOS. iOS advertises this latency to AVAudioPlayerNode's DataPlayedBack completion.
- The limiter uses 5 ms lookahead, 20 ms peak hold, 100 ms release and a linked -0.5 dBFS
  sample-peak ceiling while filters are active. It is not a true-peak/oversampled limiter.
  Fixed boost headroom is at most 2 dB; flat and cut-only curves have no fixed reduction.
- Supported streams have 1–32 channels and a positive sample rate up to 768 kHz. Tests cover
  common rates through 192 kHz. Disabled/flat bands above 45% of sample rate are omitted.
- Response points use the same Signalsmith configuration, including static headroom. Limiter
  attenuation is a separate meter, polled by UI at 10 Hz while observed.

Android currently converts decoded high-resolution/float PCM to PCM16 in DefaultAudioSink.
iOS uses decoded Float32 before its mixer resamples to the output route. Same input and sample
rate have the same DSP response; decoding, quantization, mixer pan law and system output can
still differ. Protected Music.app playback does not enter this graph and reports EQ unavailable.

## Builds and tests

Android uses `:core:audio-dsp` / NDK 28.2.13676358 and CMake 3.22.1, with static libc++ and
16 KiB ELF page alignment. iOS uses `tools/release/build-native-eq.py` through playback's Gradle
cinterop tasks; the static library includes the Objective-C++ Audio Unit adapter and requires
AVFoundation, AudioToolbox, Foundation and libc++. JVM tests build a host JNI library with the
same script, using the current JDK's JNI headers and an installed C++17 compiler.

```sh
./gradlew :core:playback:testAndroidHostTest :core:playback:iosSimulatorArm64Test
ANDROID_SERIAL=DEVICE_SERIAL ./gradlew :core:playback:connectedAndroidDeviceTest
cmake -S core/audio-dsp/src/main/cpp -B /tmp/lj-eq-tests -DLJ_EQ_TESTS=ON
cmake --build /tmp/lj-eq-tests
ctest --test-dir /tmp/lj-eq-tests --output-on-failure
```

CMake tests check PCM16 frame/bypass preservation, float bypass, limiter ceiling/stereo linking,
zero processing allocations and concurrent settings updates. On Apple hosts they also compare
an actual AVAudioEngine Audio Unit render with the C core at 44.1/48 kHz, 1/2/6 channels,
boosted/bypass, including the delayed end. Direct engine output isolates DSP from mixer pan law.
The Kotlin iOS simulator suite independently exercises the Audio Unit and persisted controls.

For native memory/race checks build separate binaries (do not combine ASan and TSan):

```sh
clang++ -std=c++17 -O1 -g -ffp-contract=off -fno-fast-math -pthread \
  -fsanitize=address,undefined -Icore/audio-dsp/src/main/cpp \
  core/audio-dsp/src/main/cpp/lj_equalizer.cpp \
  core/audio-dsp/src/main/cpp/tests/lj_equalizer_test.cpp -o /tmp/lj-eq-asan
/tmp/lj-eq-asan
# Repeat with -fsanitize=thread and another output path for the settings stress test.
```

Device throughput compares three warmed runs of the old Kotlin reference and C++ block
processing. It measures DSP elapsed time, not full decoder CPU usage, battery or route latency.
The old Kotlin processor remains a test reference; production playback and response use C++.
