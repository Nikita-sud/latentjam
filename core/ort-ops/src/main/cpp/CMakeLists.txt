# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
#
# libljq4: LatentJam's ONNX Runtime operators (Q4Conv1x1, see ljq4.cc). It links nothing but the C library:
# ONNX Runtime hands over its API at registration, and libc++ is linked statically on Android. The Android
# build runs it through Gradle (../../../build.gradle.kts); a host build (macOS, for the model tools and the
# reduced runtime's model conversion) is plain CMake:
#   cmake -S core/ort-ops/src/main/cpp -B /tmp/ljq4-host -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/ljq4-host
# -DLJQ4_TESTS=ON adds the two host tests (../../test/cpp): ljq4_test, the operator's arithmetic on every
# path the host CPU has, and ljdots_test, LjBatchDots against a portable reference — the latter exits 77
# ("skipped") on a CPU where LjBatchDots has no kernel, rather than reporting a pass that verified nothing.
# On an Apple silicon Mac, -DCMAKE_OSX_ARCHITECTURES=x86_64 builds the SSSE3 path to run under Rosetta.
cmake_minimum_required(VERSION 3.22.1)
project(ljq4 C CXX ASM)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(LJQ4_TESTS "Build the host test of the operator's arithmetic" OFF)

set(KAI ${CMAKE_CURRENT_SOURCE_DIR}/vendor/kleidiai)
set(PACK ${KAI}/kai/ukernels/matmul/pack)
set(BLOCKS ${KAI}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p)
set(CHANNELS ${KAI}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4cxp)
set(KAI_SOURCES ${PACK}/kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.c ${PACK}/kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0.c)

# 64-bit Arm: KleidiAI's int8 kernels. Each file is compiled with the extension it uses and only runs
# after the CPU reported that extension (getauxval in ljq4.cc); every other CPU takes ljq4.cc's own code.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64" AND NOT CMAKE_OSX_ARCHITECTURES MATCHES "x86_64")
  foreach(KERNEL ${BLOCKS}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm
                 ${CHANNELS}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm)
    list(APPEND KAI_SOURCES ${KERNEL}.c)
    set_source_files_properties(${KERNEL}.c PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+i8mm+dotprod")
    if(EXISTS ${KERNEL}_asm.S)  # the per-channel kernels keep their assembly inline
      list(APPEND KAI_SOURCES ${KERNEL}_asm.S)
      set_source_files_properties(${KERNEL}_asm.S PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+i8mm+dotprod")
    endif()
  endforeach()
  foreach(KERNEL ${BLOCKS}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod
                 ${CHANNELS}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod)
    list(APPEND KAI_SOURCES ${KERNEL}.c)
    set_source_files_properties(${KERNEL}.c PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
    if(EXISTS ${KERNEL}_asm.S)  # the per-channel kernels keep their assembly inline
      list(APPEND KAI_SOURCES ${KERNEL}_asm.S)
      set_source_files_properties(${KERNEL}_asm.S PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
    endif()
  endforeach()
endif()

add_library(ljq4 SHARED ljq4.cc ljdots.cc ${KAI_SOURCES})
# Score ties depend on separate rounding in dimension order, including on non-Clang hosts.
set_source_files_properties(ljdots.cc PROPERTIES COMPILE_OPTIONS "-ffp-contract=off;-fno-fast-math")
if(ANDROID)
  target_sources(ljq4 PRIVATE ljdots_jni.cc)
endif()
target_include_directories(ljq4 PRIVATE vendor/onnxruntime ${KAI} ${PACK} ${BLOCKS} ${CHANNELS})
target_compile_options(ljq4 PRIVATE -O3 -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)
if(ANDROID)
  target_link_options(ljq4 PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

if(LJQ4_TESTS)
  add_executable(ljdots_test ../../test/cpp/ljdots_test.cc ljdots.cc)
  target_include_directories(ljdots_test PRIVATE .)
  target_compile_options(ljdots_test PRIVATE -O2 -ffp-contract=off)
  add_executable(ljq4_test ../../test/cpp/ljq4_test.cc ${KAI_SOURCES})
  target_include_directories(ljq4_test PRIVATE . vendor/onnxruntime ${KAI} ${PACK} ${BLOCKS} ${CHANNELS})
  target_compile_options(ljq4_test PRIVATE -O2 $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)
endif()
