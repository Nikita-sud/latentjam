# Minimal CMake build for libopusfile. The upstream tree only ships an autoconf build,
# but for our Android use we just need a small static library with no HTTP/Windows-cert
# transport — opusfile's local-buffer + callback APIs are enough.
#
# Compiled sources: info.c, internal.c, opusfile.c, stream.c.
# Skipped: http.c (HTTPS support), wincerts.c (Windows certificate store).

cmake_minimum_required(VERSION 3.16)
project(opusfile C)

set(OPUSFILE_SOURCES
    src/info.c
    src/internal.c
    src/opusfile.c
    src/stream.c
)

add_library(opusfile STATIC ${OPUSFILE_SOURCES})

target_include_directories(opusfile
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/src
)

# Opusfile uses lrintf for fast float-to-int rounding when OP_HAVE_LRINTF is set.
# Bionic provides this since API 21, well below our min SDK (24).
target_compile_definitions(opusfile PRIVATE
    OP_HAVE_LRINTF=1
    # Explicit lack of HTTP support — internal.h gates http.c references on
    # OP_ENABLE_HTTP, which we leave undefined.
)

target_compile_options(opusfile PRIVATE
    -O3
    -fvisibility=hidden
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-shift-negative-value
)

# Depends on libogg and libopus targets exposed by the root CMakeLists.txt via
# add_subdirectory(). We link them PUBLIC so anyone linking opusfile pulls them
# in transitively.
target_link_libraries(opusfile PUBLIC ogg opus)
