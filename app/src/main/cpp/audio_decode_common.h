/*
 * Copyright (c) 2021 Auxio Project
 * Copyright (c) 2026 LatentJam Project (modifications)
 * audio_decode_common.h is part of LatentJam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
#pragma once

// Shared between the main native decoder TU (`native_audio_decoder.cpp`) and
// the M4A/AAC decoder TU (`m4a_aac_decoder.cpp`). The split exists because
// `minimp3.h` and `minimp4.h` both declare an internal `struct bs_t` with
// incompatible layouts — including both in the same compilation unit triggers
// a type redefinition error.

#include <cstddef>
#include <cstdint>

namespace latentjam_audio {

struct Decoded {
    float *mono = nullptr;
    std::size_t samples = 0;
    int sample_rate = 0;
};

// Implemented in m4a_aac_decoder.cpp. Decodes `duration_us` of audio from a
// memory-mapped ISO BMFF/M4A file starting at `start_us`; on success writes a
// freshly-malloc'd mono float32 buffer + length + sample rate into `out` and
// returns true. On failure returns false with `out.mono == nullptr`.
//
// Use `int64_t` not `long long` for the duration args: on the arm64-linux ABI
// the two are distinct types in name mangling (`int64_t = long`), so any
// signature mismatch between header and impl causes an undefined-symbol error
// at link time.
bool decode_m4a_aac(const std::uint8_t *data, std::size_t size,
        std::int64_t start_us, std::int64_t duration_us, Decoded &out);

// Implemented in opus_fast_decoder.cpp. Custom Ogg page parser + direct
// libopus call. Skips libopusfile entirely — measured ~3× faster than the
// `op_open_memory` + `op_pcm_seek` path because that path's end-of-file
// bisection and CRC32 validation dominate the budget on mmap'd files. We
// keep the libopusfile path as a fallback for streams this code rejects
// (malformed Ogg, multi-stream chains, etc.).
bool decode_opus_fast(const std::uint8_t *data, std::size_t size,
        std::int64_t start_us, std::int64_t duration_us, Decoded &out);

}  // namespace latentjam_audio
