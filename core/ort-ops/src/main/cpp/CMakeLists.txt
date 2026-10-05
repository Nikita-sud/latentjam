# Copyright (c) 2026 LatentJam Project
# SPDX-License-Identifier: Apache-2.0
#
# libljq4: LatentJam's ONNX Runtime operators (Q4Conv1x1, see ljq4.cc). It links nothing but the C library:
# ONNX Runtime hands over its API at registration, and libc++ is linked statically on Android. The Android
# build runs it through Gradle (../../../build.gradle.kts); a host build (macOS, for the model tools and the
# reduced runtime's model conversion) is plain CMake:
#   cmake -S core/ort-ops/src/main/cpp -B /tmp/ljq4-host -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/ljq4-host
cmake_minimum_required(VERSION 3.22.1)
project(ljq4 C CXX ASM)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(KAI ${CMAKE_CURRENT_SOURCE_DIR}/vendor/kleidiai)
set(PACK ${KAI}/kai/ukernels/matmul/pack)
set(BLOCKS ${KAI}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p)
set(CHANNELS ${KAI}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4cxp)

add_library(ljq4 SHARED ljq4.cc ${PACK}/kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.c ${PACK}/kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0.c)
target_include_directories(ljq4 PRIVATE vendor/onnxruntime ${KAI} ${PACK} ${BLOCKS} ${CHANNELS})
target_compile_options(ljq4 PRIVATE -O3 -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>)
if(ANDROID)
  target_link_options(ljq4 PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
endif()

# 64-bit Arm: KleidiAI's int8 kernels. Each file is compiled with the extension it uses and only runs
# after the CPU reported that extension (getauxval in ljq4.cc); every other CPU takes the portable loop.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
  foreach(KERNEL ${BLOCKS}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm
                 ${CHANNELS}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm)
    target_sources(ljq4 PRIVATE ${KERNEL}.c)
    set_source_files_properties(${KERNEL}.c PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+i8mm+dotprod")
    if(EXISTS ${KERNEL}_asm.S)  # the per-channel kernels keep their assembly inline
      target_sources(ljq4 PRIVATE ${KERNEL}_asm.S)
      set_source_files_properties(${KERNEL}_asm.S PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+i8mm+dotprod")
    endif()
  endforeach()
  foreach(KERNEL ${BLOCKS}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod
                 ${CHANNELS}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod)
    target_sources(ljq4 PRIVATE ${KERNEL}.c)
    set_source_files_properties(${KERNEL}.c PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
    if(EXISTS ${KERNEL}_asm.S)  # the per-channel kernels keep their assembly inline
      target_sources(ljq4 PRIVATE ${KERNEL}_asm.S)
      set_source_files_properties(${KERNEL}_asm.S PROPERTIES COMPILE_OPTIONS "-march=armv8.2-a+dotprod")
    endif()
  endforeach()
endif()
