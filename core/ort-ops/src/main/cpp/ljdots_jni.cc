/* Copyright (c) 2026 LatentJam Project; SPDX-License-Identifier: Apache-2.0 */
#include <jni.h>
#include "ljdots.h"
extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_nikitasud_latentjam_smart_chain_AndroidBatchDotProducts_nativeCompute(
    JNIEnv* env, jobject, jfloatArray matrix, jint dim, jfloatArray query, jint offset,
    jintArray rows, jint count, jfloatArray output) {
#if defined(__aarch64__)
  // All JNI metadata reads happen before entering the nested critical region. Row bounds have
  // already been checked by the Kotlin adapter; this entry point is private to that adapter.
  const jsize matrixSize = env->GetArrayLength(matrix);
  const jsize querySize = env->GetArrayLength(query);
  const jsize rowSize = env->GetArrayLength(rows);
  const jsize outputSize = env->GetArrayLength(output);
  if (dim <= 0 || offset < 0 || offset > querySize - dim || count < 0 || count > rowSize ||
      matrixSize % dim != 0 || (count > 0 && outputSize == 0)) return JNI_FALSE;
  auto* m = static_cast<float*>(env->GetPrimitiveArrayCritical(matrix, nullptr));
  if (!m) return JNI_FALSE;
  auto* q = static_cast<float*>(env->GetPrimitiveArrayCritical(query, nullptr));
  if (!q) { env->ReleasePrimitiveArrayCritical(matrix, m, JNI_ABORT); return JNI_FALSE; }
  auto* r = static_cast<jint*>(env->GetPrimitiveArrayCritical(rows, nullptr));
  if (!r) {
    env->ReleasePrimitiveArrayCritical(query, q, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(matrix, m, JNI_ABORT); return JNI_FALSE;
  }
  auto* out = static_cast<float*>(env->GetPrimitiveArrayCritical(output, nullptr));
  if (!out) {
    env->ReleasePrimitiveArrayCritical(rows, r, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(query, q, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(matrix, m, JNI_ABORT); return JNI_FALSE;
  }
  const int result = LjBatchDots(m, dim, q + offset, r, count, out);
  env->ReleasePrimitiveArrayCritical(output, out, 0);
  env->ReleasePrimitiveArrayCritical(rows, r, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(query, q, JNI_ABORT);
  env->ReleasePrimitiveArrayCritical(matrix, m, JNI_ABORT);
  return result ? JNI_TRUE : JNI_FALSE;
#else
  return JNI_FALSE;
#endif
}
