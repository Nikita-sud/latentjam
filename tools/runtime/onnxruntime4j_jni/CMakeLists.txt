# SPDX-License-Identifier: Apache-2.0
#
# ONNX Runtime's Java JNI library (libonnxruntime4j_jni.so), built from ORT_SOURCE/java/src/main/native against the
# libonnxruntime.so that ort_source_build.sh just built. Same sources, include paths and flags as ORT's own
# onnxruntime4j_jni target (cmake/onnxruntime_java.cmake, with --enable_lto), without that target's POST_BUILD steps:
# those run ORT's Gradle builds, and the Android AAR one needs AGP 7.4.2, which fails on JDK 21.
#
# Configured by ort_source_build.sh with the NDK's toolchain file and
#   -DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF (as ORT's build.py) -DORT_SOURCE=<onnxruntime source>
#   -DORT_BUILD_DIR=<ORT's Release build dir: libonnxruntime.so, onnxruntime_config.h> -DJNI_HEADERS=<javac -h output>
cmake_minimum_required(VERSION 3.22)
project(onnxruntime4j_jni C)

foreach(var ORT_SOURCE ORT_BUILD_DIR JNI_HEADERS)
  if(NOT ${var})
    message(FATAL_ERROR "${var} is required")
  endif()
endforeach()

# What ORT's build adds to the NDK's flags for this target: adjust_global_compile_flags.cmake (sections, 16 KB pages),
# the C11 standard of onnxruntime_java.cmake and the warnings of onnxruntime_configure_target; IPO is ORT's
# --enable_lto (-flto=thin).
string(APPEND CMAKE_C_FLAGS " -ffunction-sections -fdata-sections")
string(APPEND CMAKE_MODULE_LINKER_FLAGS " -Wl,-z,max-page-size=16384")
set(CMAKE_INTERPROCEDURAL_OPTIMIZATION ON)

file(GLOB jni_sources "${ORT_SOURCE}/java/src/main/native/*.c")
add_library(onnxruntime4j_jni MODULE ${jni_sources})
set_property(TARGET onnxruntime4j_jni PROPERTY C_STANDARD 11)
target_compile_options(onnxruntime4j_jni PRIVATE -Wall -Wextra -Wno-unused-parameter -Werror)
target_include_directories(onnxruntime4j_jni PRIVATE
  "${ORT_SOURCE}/include/onnxruntime"
  "${ORT_SOURCE}/include/onnxruntime/core/session"
  "${ORT_SOURCE}/include"
  "${ORT_BUILD_DIR}"
  "${ORT_SOURCE}/orttraining/orttraining/training_api/include"
  "${JNI_HEADERS}")

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION "${ORT_BUILD_DIR}/libonnxruntime.so")
target_link_libraries(onnxruntime4j_jni PRIVATE onnxruntime atomic m)
