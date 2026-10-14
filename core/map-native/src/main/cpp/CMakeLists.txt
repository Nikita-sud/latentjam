# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.22.1)
project(ljmap CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
option(LJMAP_TESTS "Build native correctness and cancellation checks" OFF)
add_library(ljmap SHARED ljmap.cc)
foreach(dep qdtsne knncolle aarand subpar sanisizer)
  target_include_directories(ljmap PRIVATE vendor/${dep}/include)
endforeach()
target_compile_options(ljmap PRIVATE -O3 -ffp-contract=off -fno-fast-math -fvisibility=hidden)
if(ANDROID)
  target_sources(ljmap PRIVATE ljmap_jni.cc)
  # Debug paths participate in the build ID even when AGP later strips DWARF.
  target_compile_options(ljmap PRIVATE
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    "-fdebug-prefix-map=${CMAKE_BINARY_DIR}=.")
  target_link_options(ljmap PRIVATE -Wl,-z,max-page-size=16384)
endif()
if(LJMAP_TESTS)
  enable_testing()
  add_executable(ljmap_test ../../test/cpp/ljmap_test.cc)
  target_include_directories(ljmap_test PRIVATE .)
  target_link_libraries(ljmap_test PRIVATE ljmap)
  add_test(NAME ljmap_test COMMAND ljmap_test)
endif()

