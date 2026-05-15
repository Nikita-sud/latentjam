/*
 * Shim for AOSP's <log/log.h> when building fdk-aac as a third-party NDK lib.
 *
 * Upstream fdk-aac (v2.0.3) calls `android_errorWriteLog()` in two places in
 * libSBRdec/src/lpp_tran.cpp for AOSP Safetynet incident tagging (CVE 112160868).
 * That symbol lives in libcutils, which is internal to AOSP — not exposed by
 * the public NDK. For our app build we no-op it: the calls are pure logging
 * with no functional effect on the decoder output.
 */
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

static inline int android_errorWriteLog(int /*tag*/, const char* /*subTag*/) {
    return 0;
}

#ifdef __cplusplus
}
#endif
