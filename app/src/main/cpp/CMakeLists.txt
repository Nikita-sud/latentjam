cmake_minimum_required(VERSION 3.22.1)
project("latentjam_native")

# Aggressive optimization: every file here is on the audio embedding hot path called
# for every track in the library at startup. -O3 enables auto-vectorization (NEON on
# arm64-v8a, SSE on x86_64). -ffast-math: trade strict IEEE FP for speed — denormals,
# subnormals, etc. don't matter for music audio.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ----- Third-party static libs -----------------------------------------------------
# Bundled native decoders so all popular audio formats bypass
# `android.media.MediaExtractor`, whose random-offset seek can cost 5-15 s/track on
# Snapdragon 8 Gen 3 — far above the ~350 ms total embedding budget per track.
# Decoding directly from a memory-mapped file eliminates the cross-process SAF I/O
# and the linear page walk that dominate the extractor's seek cost.
#
# Coverage:
#   mp3    → minimp3 (header-only, sample-accurate index seek)
#   opus   → libopus + libopusfile + libogg (binary-search granule seek)
#   vorbis → stb_vorbis (header-only, pushdata-mode on mmap)
#   flac   → dr_flac (header-only, SEEKTABLE-aware)
#   wav    → dr_wav  (header-only, trivial format)
#   m4a    → minimp4 demux + fdk-aac decode (sample-table seek)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

# libogg — needed as a transitive dep for libopusfile.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY_DIR}/libogg" EXCLUDE_FROM_ALL)

# libopus — turn off everything we don't need.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
# Performance: presume NEON on arm64-v8a (mandatory in the ARMv8 ABI, so the
# runtime cpuinfo check is pure overhead per-call).
# We tried FIXED_POINT but the libopus build is missing celt_pitch_xcorr_neon
# on aarch64 + fixed-point.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(OPUS_PRESUME_NEON ON CACHE BOOL "" FORCE)
endif()
add_subdirectory("${THIRD_PARTY_DIR}/libopus" EXCLUDE_FROM_ALL)
# AGP's Debug variant uses no optimization (`-O0` default), which left
# libopus's inner CELT/SILK loops running scalar-style at ~3× the release-mode
# cost. Profiling showed opus_decode_float consuming ~100 ms / 15 s of audio
# without optimization vs ~35 ms with `-O3 -ffast-math`. Override on the opus
# targets so the embedding pipeline performs the same on debug + release
# builds (the codec is read-only — `-ffast-math` is safe here).
target_compile_options(opus PRIVATE -O3 -ffast-math)
# Upstream refuses `-ffast-math` unless `FLOAT_APPROX` is defined (`celt/arch.h`
# guards against NaN-handling regressions). FLOAT_APPROX is the approved combo:
# enables approximate exp/log/cos for ~5 % extra decode speed with no
# perceptual audio impact.
target_compile_definitions(opus PRIVATE FLOAT_APPROX)
if(TARGET celt)
    target_compile_options(celt PRIVATE -O3 -ffast-math)
    target_compile_definitions(celt PRIVATE FLOAT_APPROX)
endif()
if(TARGET silk)
    target_compile_options(silk PRIVATE -O3 -ffast-math)
    target_compile_definitions(silk PRIVATE FLOAT_APPROX)
endif()

# libopusfile (our minimal CMakeLists). Depends on libogg + libopus targets above.
add_subdirectory("${THIRD_PARTY_DIR}/libopusfile" EXCLUDE_FROM_ALL)

# fdk-aac — Fraunhofer FDK AAC decoder. Builds as a static lib; dead-code-elim
# strips the encoder side at link time (we never reference aacEncoder_*).
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(FDK_AAC_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(FDK_AAC_INSTALL_PKGCONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY_DIR}/fdk-aac" EXCLUDE_FROM_ALL)
# fdk-aac CMakeLists doesn't expose its public include dir on its target, so
# advertise it manually for downstream consumers.
target_include_directories(fdk-aac INTERFACE
    "${THIRD_PARTY_DIR}/fdk-aac/libAACdec/include"
    "${THIRD_PARTY_DIR}/fdk-aac/libSYS/include"
)
# `fdkaac_shim/` provides a no-op <log/log.h> stub for `android_errorWriteLog`
# (AOSP Safetynet logging calls inside libSBRdec). Without the shim that TU
# fails to compile against the NDK sysroot.
target_include_directories(fdk-aac PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/fdkaac_shim"
)
# Silence fdk-aac warnings — it has many in older GCC/Clang, irrelevant to us.
target_compile_options(fdk-aac PRIVATE -w)

# stb_vorbis — single .c file, compiled directly into our shared lib (below) so
# we don't need a separate target. Its symbols live in our final .so.
# dr_flac / dr_wav / minimp4 are header-only with implementation guards.

# ----- latentjam_native: the shared lib we actually load from Kotlin --------------
# Combines the existing linear resampler with the new mmap+minimp3+opusfile decoder.
# Kept under the historical output name "linear_resampler" so existing Kotlin code
# (NativeLinearResampler.kt's System.loadLibrary("linear_resampler")) keeps working.

add_library(latentjam_native SHARED
    linear_resampler.cpp
    native_audio_decoder.cpp
    # M4A/AAC decoder isolated in its own TU: minimp4.h and minimp3.h both
    # declare a `struct bs_t` with incompatible layouts and can't coexist in
    # one compilation unit.
    m4a_aac_decoder.cpp
    # Fast-path Opus decoder. Custom Ogg page walker + direct libopus calls;
    # bypasses libopusfile's expensive end-of-file bisection + CRC32 sweep.
    opus_fast_decoder.cpp
    # stb_vorbis is a single C file — compile it straight into our .so. The header
    # has its implementation guarded by `STB_VORBIS_HEADER_ONLY`, which we don't
    # define here, so this TU emits all the decoder symbols.
    third_party/stb_vorbis/stb_vorbis.c
)

target_include_directories(latentjam_native PRIVATE
    "${THIRD_PARTY_DIR}/minimp3"
    "${THIRD_PARTY_DIR}/libopusfile/include"
    "${THIRD_PARTY_DIR}/libopus/include"        # opus.h for the fast path
    "${THIRD_PARTY_DIR}/dr_libs"
    "${THIRD_PARTY_DIR}/stb_vorbis"
    "${THIRD_PARTY_DIR}/minimp4"
)

target_compile_options(latentjam_native PRIVATE
    -O3
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden
    -Wall
    -Wno-unused-function
)

# 16 KB page alignment for Android 15+ (NDK r27+ default; required for Google Play
# submissions after 2025-11-01).
target_link_options(latentjam_native PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(latentjam_native
    PRIVATE opusfile
    PRIVATE opus
    PRIVATE ogg
    PRIVATE fdk-aac
    PRIVATE ${log-lib}
)

# stb_vorbis is C99 with a few signed/unsigned mismatches that surface under -Wall
# at -O3. Suppress them on that one file rather than polluting the whole project.
set_source_files_properties(third_party/stb_vorbis/stb_vorbis.c PROPERTIES
    COMPILE_OPTIONS "-w"
)

# The .so name has to remain "liblinear_resampler.so" for backwards compatibility:
# NativeLinearResampler.kt does System.loadLibrary("linear_resampler"). Renaming
# would require touching every JNI signature, so the easier path is to make the
# output of this target match.
set_target_properties(latentjam_native PROPERTIES OUTPUT_NAME "linear_resampler")
