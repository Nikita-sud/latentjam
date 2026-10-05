/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Creates an ONNX Runtime session the way every SMART model runs.
 *
 * One sequential thread: ORT's default native pool otherwise occupies several cores even though
 * callers serialize their work, starving UI rendering during first-run indexing.
 *
 * No CPU memory arena: the arena keeps every activation buffer a graph has ever needed for as long
 * as the session lives. Measured on a Galaxy S24 Ultra, an idle music-encoder session held about
 * 80 MiB that way and the scorer 18 MiB; without the arena they hold 15 MiB and 2 MiB, outputs are
 * bit-identical and warm runs as fast (only a session's first run takes 3–20 ms longer).
 */
internal fun createOrtSession(model: ByteArray): OrtSession =
    OrtSession.SessionOptions().use { options ->
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setIntraOpNumThreads(1)
        options.setInterOpNumThreads(1)
        options.setCPUArenaAllocator(false)
        OrtEnvironment.getEnvironment().createSession(model, options)
    }
