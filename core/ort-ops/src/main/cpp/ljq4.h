/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

struct OrtApiBase;
struct OrtSessionOptions;
struct OrtStatus;

// Adds LatentJam's operators (domain "latentjam") to session options, for runtimes that link this library
// statically (iOS). Returns null on success, else an OrtStatus the caller releases.
struct OrtStatus* LjRegisterOrtOps(struct OrtSessionOptions* options, const struct OrtApiBase* base);

#ifdef __cplusplus
}
#endif
