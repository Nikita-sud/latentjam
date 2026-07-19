/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.nikitasud.latentjam.smart

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.get
// Calling a C function pointer goes through this extension. Without it every
// `!!.invoke(...)` below silently resolves to DeepRecursiveFunction.invoke and
// the whole file collapses into type-inference errors.
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.set
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
// The opaque ORT handles are forward-declared structs in C, which cinterop puts
// under cnames.structs rather than the binding's own package.
import cnames.structs.OrtEnv
import cnames.structs.OrtMemoryInfo
import cnames.structs.OrtSession
import cnames.structs.OrtSessionOptions
import cnames.structs.OrtStatus
import cnames.structs.OrtValue
import onnxruntime.ONNXTensorElementDataType
import onnxruntime.ORT_API_VERSION
import onnxruntime.OrtLoggingLevel
import onnxruntime.OrtApi
import onnxruntime.OrtArenaAllocator
import onnxruntime.OrtGetApiBase
import onnxruntime.OrtMemTypeDefault

/**
 * Thin Kotlin wrapper over the ONNX Runtime **C** API.
 *
 * The C API rather than C++/Objective-C because it is the ABI ONNX Runtime
 * commits to and the only one cinterop can bind without a shim.
 *
 * Every C entry point is a function pointer hanging off [OrtApi] and returns an
 * `OrtStatus*` that is null on success and an owned error otherwise, so every
 * call goes through [ortCheck] — forgetting one leaks the status and, worse, lets
 * a failed call look like a successful one.
 */
@OptIn(ExperimentalForeignApi::class)
internal object Ort {

    /**
     * Process-wide, deliberately never released.
     *
     * Mirrors the Android backend, which leaves `OrtEnvironment` open for the
     * life of the process: tearing an environment down while any session still
     * references it is undefined behaviour, and the app has no point at which
     * every session is provably closed.
     */
    val api: CPointer<OrtApi> = run {
        val base = OrtGetApiBase() ?: error("ONNX Runtime C API base unavailable")
        base.pointed.GetApi!!.invoke(ORT_API_VERSION.toUInt())
            ?: error("ONNX Runtime C API v$ORT_API_VERSION unavailable")
    }

    private val env: CPointer<OrtEnv> = memScoped {
        val out = alloc<CPointerVar<OrtEnv>>()
        ortCheck(api.pointed.CreateEnv!!.invoke(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "latentjam".cstr.ptr, out.ptr))
        requireNotNull(out.value) { "ONNX Runtime environment could not be created" }
    }

    /** Turns a non-null `OrtStatus*` into an exception, releasing it either way. */
    fun ortCheck(status: CPointer<OrtStatus>?) {
        if (status == null) return
        val message = api.pointed.GetErrorMessage!!.invoke(status)?.toKString() ?: "unknown ORT error"
        api.pointed.ReleaseStatus!!.invoke(status)
        error("onnxruntime: $message")
    }

    /**
     * Opens a session over a model FILE.
     *
     * By path, not by bytes: the audio encoder is 43 MB and the byte-array form
     * would hold a second copy in Kotlin memory for the lifetime of the load.
     */
    fun createSession(modelPath: String): CPointer<OrtSession> = memScoped {
        val options = alloc<CPointerVar<OrtSessionOptions>>()
        ortCheck(api.pointed.CreateSessionOptions!!.invoke(options.ptr))
        val session = alloc<CPointerVar<OrtSession>>()
        try {
            ortCheck(
                api.pointed.CreateSession!!.invoke(env, modelPath.cstr.ptr, options.value, session.ptr),
            )
        } finally {
            api.pointed.ReleaseSessionOptions!!.invoke(options.value)
        }
        requireNotNull(session.value) { "session was not created for $modelPath" }
    }

    fun releaseSession(session: CPointer<OrtSession>?) {
        if (session != null) api.pointed.ReleaseSession!!.invoke(session)
    }

    /** CPU allocator info, needed to wrap Kotlin memory as a tensor. */
    fun MemScope.cpuMemoryInfo(): CPointer<OrtMemoryInfo> {
        val out = alloc<CPointerVar<OrtMemoryInfo>>()
        ortCheck(api.pointed.CreateCpuMemoryInfo!!.invoke(OrtArenaAllocator, OrtMemTypeDefault, out.ptr))
        return requireNotNull(out.value)
    }

    /**
     * Runs a single-input, single-output float model and returns the output as a
     * Kotlin [FloatArray].
     *
     * Scoped to that shape because all four LatentJam graphs that iOS needs are
     * either single-input or handled by the multi-input sibling; keeping the
     * common case in one place keeps the pointer bookkeeping in one place too.
     */
    fun runFloat(
        session: CPointer<OrtSession>,
        inputName: String,
        input: FloatArray,
        inputShape: LongArray,
        outputName: String,
        outputSize: Int,
    ): FloatArray = memScoped {
        val memoryInfo = cpuMemoryInfo()
        val shape = allocArray<LongVar>(inputShape.size)
        inputShape.forEachIndexed { index, dim -> shape[index] = dim }
        val tensor = alloc<CPointerVar<OrtValue>>()

        // Pinned rather than copied. One audio window is 320k floats; copying it
        // per window would move gigabytes over a full library index for nothing.
        // The tensor borrows this memory, so everything that touches it has to
        // stay inside the pin.
        input.usePinned { pinned ->
            ortCheck(
                api.pointed.CreateTensorWithDataAsOrtValue!!.invoke(
                    memoryInfo,
                    pinned.addressOf(0),
                    (input.size * Float.SIZE_BYTES).toULong(),
                    shape,
                    inputShape.size.toULong(),
                    ONNXTensorElementDataType.ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                    tensor.ptr,
                ),
            )
            try {
                val inputNames = allocArrayOf(inputName.cstr.ptr)
                val outputNames = allocArrayOf(outputName.cstr.ptr)
                val inputs = allocArrayOf(tensor.value)
                val outputs = allocArray<CPointerVar<OrtValue>>(1)
                ortCheck(
                    api.pointed.Run!!.invoke(
                        session, null,
                        inputNames.reinterpret(), inputs.reinterpret(), 1uL,
                        outputNames.reinterpret(), 1uL, outputs,
                    ),
                )
                val out = requireNotNull(outputs[0]) { "$outputName was not produced" }
                try {
                    readFloats(out, outputSize)
                } finally {
                    api.pointed.ReleaseValue!!.invoke(out)
                }
            } finally {
                api.pointed.ReleaseValue!!.invoke(tensor.value)
                api.pointed.ReleaseMemoryInfo!!.invoke(memoryInfo)
            }
        }
    }

    /** Copies a float tensor's contents out of ORT-owned memory. */
    fun readFloats(value: CPointer<OrtValue>, count: Int): FloatArray = memScoped {
        val raw = alloc<CPointerVar<ByteVar>>()
        ortCheck(api.pointed.GetTensorMutableData!!.invoke(value, raw.ptr.reinterpret()))
        val floats = requireNotNull(raw.value).reinterpret<kotlinx.cinterop.FloatVar>()
        FloatArray(count) { floats[it] }
    }

}
