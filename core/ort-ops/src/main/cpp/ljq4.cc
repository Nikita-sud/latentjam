/*
 * Copyright (c) 2026 LatentJam Project
 * SPDX-License-Identifier: Apache-2.0
 */
// Q4Conv1x1: the music encoder's pointwise convolutions with 4-bit weights, as an ONNX Runtime custom
// operator in the domain "latentjam". The weights stay 4-bit in memory and are multiplied by KleidiAI's
// int8 kernels: on CPUs with i8mm straight from the packed weights the model carries, on dotprod-only
// CPUs from a copy packed once for the dotprod kernels, elsewhere by a portable loop.
//
// Inputs:  0 X            uint8 [..., K]  rows of an NHWC activation, statically quantized
//          1 x_scale      float []
//          2 x_zero_point uint8 []
//          3 W            uint8 [bytes]   symmetric 4-bit weights with a bf16 scale per 32 inputs and a
//                                         float bias per output channel, packed by lj_q4_pack (KleidiAI
//                                         qsi4c32p, nr 8, kr 16, sr 2)
//          4 y_scale      float []
//          5 y_zero_point uint8 []
// Attribute: n, the number of output channels.
// Output:  Y uint8 [..., n] = sat(round((x_scale * sum_k (x - x_zp) * w + bias) / y_scale) + y_zp)
//
// Only ONNX Runtime's C API is used, at version 16, so one build works with every runtime from 1.16 on. Built
// without C++ exceptions: nothing can unwind into the runtime, and running out of memory aborts as it would there.
#include "onnxruntime_c_api.h"
#include "ljq4.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <vector>

#if defined(__aarch64__)
#include <arm_neon.h>
#if defined(__APPLE__)
#include <sys/sysctl.h>
#else
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#include "kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod.h"
#include "kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm.h"
#endif
#include "kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.h"

namespace {

constexpr uint32_t kApiVersion = 16;
constexpr size_t kNr = 8, kSr = 2;
constexpr size_t kModelKr = 16;   // the layout the model carries: KleidiAI's i8mm kernels read it as is
constexpr size_t kDotKr = 8;      // the dotprod kernels' layout
constexpr size_t kGroup = 32;     // inputs per bf16 scale
constexpr size_t kChunk = 64;     // rows per pass: the int8 rows and float results stay in cache

const OrtApi* api = nullptr;

size_t RoundUp(size_t a, size_t b) { return (a + b - 1) / b * b; }

// Per 8 output channels: per 32 inputs 16 bytes of values and 8 bf16 scales, then 8 sums and 8 biases.
size_t WeightBytes(size_t n, size_t k) {
  return RoundUp(n, kNr) / kNr * (RoundUp(k, kGroup) / kGroup * kNr * 18 + kNr * 8);
}

float Bf16ToFloat(uint16_t bits) {
  const uint32_t widened = static_cast<uint32_t>(bits) << 16;
  float value;
  memcpy(&value, &widened, sizeof(value));
  return value;
}

uint16_t FloatToBf16(float value) {  // round to nearest, ties to even, as torch's bfloat16 cast
  uint32_t bits;
  memcpy(&bits, &value, sizeof(bits));
  return static_cast<uint16_t>((bits + 0x7FFF + ((bits >> 16) & 1)) >> 16);
}

enum class Path { kI8mm, kDotprod, kPortable };

Path DetectPath() {
  if (const char* forced = getenv("LJ_Q4_PATH")) {  // testing: force a slower path
    if (!strcmp(forced, "portable")) return Path::kPortable;
#if defined(__aarch64__)
    if (!strcmp(forced, "dotprod")) return Path::kDotprod;
#endif
  }
#if defined(__aarch64__)
#if defined(__APPLE__)
  int value = 0;
  size_t size = sizeof(value);
  if (sysctlbyname("hw.optional.arm.FEAT_I8MM", &value, &size, nullptr, 0) == 0 && value) return Path::kI8mm;
  value = 0;
  size = sizeof(value);
  if (sysctlbyname("hw.optional.arm.FEAT_DotProd", &value, &size, nullptr, 0) == 0 && value) return Path::kDotprod;
#else
  if (getauxval(AT_HWCAP2) & HWCAP2_I8MM) return Path::kI8mm;
  if (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) return Path::kDotprod;
#endif
#endif
  return Path::kPortable;
}

// Signed 4-bit values [n, k] to the nibbles KleidiAI packs: value + 8, two per byte, the even input low.
std::vector<uint8_t> Nibbles(const int8_t* q, size_t n, size_t k, size_t padded) {
  const size_t stride = padded / 2;
  std::vector<uint8_t> nibbles(n * stride, 0x88);
  for (size_t o = 0; o < n; ++o) {
    for (size_t c = 0; c < k; ++c) {
      const uint8_t u = static_cast<uint8_t>(q[o * k + c] + 8);
      uint8_t& byte = nibbles[o * stride + c / 2];
      byte = (c % 2 == 0) ? static_cast<uint8_t>((byte & 0xF0) | u) : static_cast<uint8_t>((byte & 0x0F) | (u << 4));
    }
  }
  return nibbles;
}

// values [n, k] in -8..7, scales [n, ceil(k / 32)] (rounded to bf16 here), biases [n] -> KleidiAI's layout for kr.
void PackWeights(size_t n, size_t k, size_t kr, const int8_t* q, const float* scale, const float* bias, uint8_t* out) {
  const size_t padded = RoundUp(k, kGroup), groups = padded / kGroup;
  std::vector<uint8_t> nibbles = Nibbles(q, n, k, padded);
  std::vector<uint16_t> scales(n * groups);
  for (size_t i = 0; i < scales.size(); ++i) scales[i] = FloatToBf16(scale[i]);
  kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0_params params{1, 8, kai_dt_bf16};
  kai_run_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0(1, n, padded, kNr, kr, kSr, kGroup, nibbles.data(), padded / 2, bias,
                                            scales.data(), groups * sizeof(uint16_t), out, 0, &params);
}

// The inverse of PackWeights for the model's layout (kr 16): per 32 inputs, two segments of 8 channels x 4
// words, each word the values at k, k + 16, k + 1, k + 17 (xor 0x8888); then 8 bf16 scales; at the end the
// zero-point sums and the biases.
void UnpackWeights(const uint8_t* w, size_t n, size_t k, std::vector<int8_t>& q, std::vector<float>& scales,
                   std::vector<float>& bias) {
  const size_t groups = RoundUp(k, kGroup) / kGroup;
  q.assign(n * k, 0);
  scales.assign(n * groups, 0.f);
  bias.assign(n, 0.f);
  const uint8_t* src = w;
  for (size_t first = 0; first < n; first += kNr) {
    for (size_t g = 0; g < groups; ++g) {
      for (size_t segment = 0; segment < 2; ++segment) {
        for (size_t lane = 0; lane < kNr; ++lane) {
          for (size_t j = 0; j < 4; ++j, src += 2) {
            const uint16_t word = static_cast<uint16_t>((src[0] | (src[1] << 8)) ^ 0x8888);
            const size_t o = first + lane, k0 = g * kGroup + segment * 8 + 2 * j;
            if (o >= n) continue;
            const size_t ks[4] = {k0, k0 + 16, k0 + 1, k0 + 17};
            for (size_t v = 0; v < 4; ++v) {
              if (ks[v] < k) q[o * k + ks[v]] = static_cast<int8_t>((word >> (4 * v)) & 0x0F) - 8;
            }
          }
        }
      }
      for (size_t lane = 0; lane < kNr; ++lane, src += 2) {
        if (first + lane < n) scales[(first + lane) * groups + g] = Bf16ToFloat(static_cast<uint16_t>(src[0] | (src[1] << 8)));
      }
    }
    src += kNr * 4;  // the zero-point sums
    for (size_t lane = 0; lane < kNr; ++lane, src += 4) {
      if (first + lane < n) memcpy(&bias[first + lane], src, 4);
    }
  }
}

// Rows of uint8 activations as KleidiAI's qai8dxp LHS: per group of 4 rows, interleaved blocks of `block`
// int8 values (x - 128), then each row's offset -(x_zp - 128) and multiplier x_scale. Exact: no rounding.
void PackRows(const uint8_t* x, size_t rows, size_t k, size_t block, float scale, uint8_t zero, uint8_t* out) {
  constexpr size_t mr = 4;
  const size_t internal = RoundUp(k, kGroup), stride = mr * (internal + 8);
  memset(out, 0, RoundUp(rows, mr) / mr * stride);
  for (size_t r = 0; r < rows; ++r) {
    uint8_t* group = out + (r / mr) * stride;
    const size_t lane = r % mr;
    const uint8_t* src = x + r * k;
    const size_t full = k / block;
    for (size_t b = 0; b < internal / block; ++b) {
      uint8_t* dst = group + (b * mr + lane) * block;
#if defined(__aarch64__)
      if (b < full && block == 8) {
        vst1_u8(dst, veor_u8(vld1_u8(src + b * 8), vdup_n_u8(0x80)));
        continue;
      }
#endif
      for (size_t i = 0; i < block; ++i) {  // past K: the zero point (the padded weights are zero)
        const size_t c = b * block + i;
        dst[i] = static_cast<uint8_t>((c < k ? src[c] : zero) ^ 0x80);
      }
    }
    reinterpret_cast<int32_t*>(group + mr * internal)[lane] = 128 - static_cast<int32_t>(zero);
    reinterpret_cast<float*>(group + mr * internal + mr * 4)[lane] = scale;
  }
}

void Requantize(const float* values, size_t count, float inverse, int32_t zero, uint8_t* out) {
  size_t i = 0;
#if defined(__aarch64__)
  const float32x4_t vi = vdupq_n_f32(inverse);
  const int32x4_t vz = vdupq_n_s32(zero);
  for (; i + 8 <= count; i += 8) {
    const int32x4_t a = vaddq_s32(vcvtnq_s32_f32(vmulq_f32(vld1q_f32(values + i), vi)), vz);
    const int32x4_t b = vaddq_s32(vcvtnq_s32_f32(vmulq_f32(vld1q_f32(values + i + 4), vi)), vz);
    vst1_u8(out + i, vqmovun_s16(vcombine_s16(vqmovn_s32(a), vqmovn_s32(b))));
  }
#endif
  for (; i < count; ++i) {
    const int32_t v = static_cast<int32_t>(std::nearbyint(values[i] * inverse)) + zero;
    out[i] = static_cast<uint8_t>(std::min(255, std::max(0, v)));
  }
}

// One scratch per thread, shared by every layer: the runtime runs a session's nodes one at a time.
struct Scratch {
  std::vector<uint8_t> rows;
  std::vector<float> results;
  std::vector<int16_t> centered;
};
thread_local Scratch scratch;

struct Kernel {
  size_t n;
  Path path;
  std::vector<uint8_t> repacked;   // dotprod-only CPUs: the weights in the dotprod kernels' layout
  std::vector<int8_t> values;      // portable path: the weights decoded once
  std::vector<float> scales, bias;

  void Multiply(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, const uint8_t* w, float ys, int32_t yz,
                uint8_t* y);
  void Portable(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz, uint8_t* y);
};

void Kernel::Multiply(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, const uint8_t* w, float ys,
                      int32_t yz, uint8_t* y) {
  if (path == Path::kPortable) {
    if (values.empty()) UnpackWeights(w, n, k, values, scales, bias);
    return Portable(x, m, k, xs, xz, 1.0f / ys, yz, y);
  }
#if defined(__aarch64__)
  const bool i8mm = path == Path::kI8mm;
  if (!i8mm && repacked.empty()) {
    UnpackWeights(w, n, k, values, scales, bias);
    repacked.resize(WeightBytes(n, k));
    PackWeights(n, k, kDotKr, values.data(), scales.data(), bias.data(), repacked.data());
    std::vector<int8_t>().swap(values);
    std::vector<float>().swap(scales);
    std::vector<float>().swap(bias);
  }
  const uint8_t* weights = i8mm ? w : repacked.data();
  const size_t block = (i8mm ? kModelKr : kDotKr) / kSr, padded = RoundUp(k, kGroup);
  if (scratch.rows.size() < kChunk * (padded + 8)) scratch.rows.resize(kChunk * (padded + 8));
  if (scratch.results.size() < kChunk * n) scratch.results.resize(kChunk * n);
  for (size_t m0 = 0; m0 < m; m0 += kChunk) {
    const size_t count = std::min(kChunk, m - m0);
    PackRows(x + m0 * k, count, k, block, xs, xz, scratch.rows.data());
    if (i8mm) {
      kai_run_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm(count, n, padded, kGroup, scratch.rows.data(),
          weights, scratch.results.data(), n * sizeof(float), sizeof(float), -INFINITY, INFINITY);
    } else {
      kai_run_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod(count, n, padded, kGroup, scratch.rows.data(),
          weights, scratch.results.data(), n * sizeof(float), sizeof(float), -INFINITY, INFINITY);
    }
    Requantize(scratch.results.data(), count * n, 1.0f / ys, yz, y + m0 * n);
  }
#endif
}

// Integer dot products per block of 32 inputs that the compiler vectorizes, four rows at a time.
void Kernel::Portable(const uint8_t* x, size_t m, size_t k, float xs, uint8_t xz, float inverse, int32_t yz,
                      uint8_t* y) {
  constexpr size_t kTile = 4;
  const size_t groups = RoundUp(k, kGroup) / kGroup;
  if (scratch.centered.size() < kTile * k) scratch.centered.resize(kTile * k);
  int16_t* rows = scratch.centered.data();
  for (size_t r0 = 0; r0 < m; r0 += kTile) {
    const size_t count = std::min(kTile, m - r0);
    for (size_t i = 0; i < count; ++i) {
      for (size_t c = 0; c < k; ++c) rows[i * k + c] = static_cast<int16_t>(x[(r0 + i) * k + c] - xz);
    }
    for (size_t o = 0; o < n; ++o) {
      const int8_t* wo = values.data() + o * k;
      for (size_t i = 0; i < count; ++i) {
        const int16_t* row = rows + i * k;
        float sum = 0.f;
        for (size_t g = 0; g < groups; ++g) {
          int32_t acc = 0;
          const size_t end = std::min(k, (g + 1) * kGroup);
          for (size_t c = g * kGroup; c < end; ++c) acc += row[c] * wo[c];
          sum += scales[o * groups + g] * static_cast<float>(acc);
        }
        const int32_t q = static_cast<int32_t>(std::nearbyint((xs * sum + bias[o]) * inverse)) + yz;
        y[(r0 + i) * n + o] = static_cast<uint8_t>(std::min(255, std::max(0, q)));
      }
    }
  }
}

OrtStatus* Fail(const char* message) { return api->CreateStatus(ORT_INVALID_ARGUMENT, message); }

// Reads a scalar input of the given type.
template <typename T>
OrtStatus* Scalar(OrtKernelContext* context, size_t index, T* out) {
  const OrtValue* value = nullptr;
  if (OrtStatus* status = api->KernelContext_GetInput(context, index, &value)) return status;
  void* data = nullptr;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(value), &data)) return status;
  *out = *static_cast<const T*>(data);
  return nullptr;
}

OrtStatus* Count(const OrtValue* value, size_t* count, std::vector<int64_t>* shape) {
  OrtTensorTypeAndShapeInfo* info = nullptr;
  if (OrtStatus* status = api->GetTensorTypeAndShape(value, &info)) return status;
  OrtStatus* status = api->GetTensorShapeElementCount(info, count);
  if (status == nullptr && shape != nullptr) {
    size_t rank = 0;
    status = api->GetDimensionsCount(info, &rank);
    if (status == nullptr) {
      shape->resize(rank);
      status = api->GetDimensions(info, shape->data(), rank);
    }
  }
  api->ReleaseTensorTypeAndShapeInfo(info);
  return status;
}

OrtStatus* ORT_API_CALL Compute(void* raw, OrtKernelContext* context) {
  Kernel* kernel = static_cast<Kernel*>(raw);
  const OrtValue* x = nullptr;
  const OrtValue* w = nullptr;
  if (OrtStatus* status = api->KernelContext_GetInput(context, 0, &x)) return status;
  if (OrtStatus* status = api->KernelContext_GetInput(context, 3, &w)) return status;
  size_t count = 0, weightBytes = 0;
  std::vector<int64_t> shape;
  if (OrtStatus* status = Count(x, &count, &shape)) return status;
  if (OrtStatus* status = Count(w, &weightBytes, nullptr)) return status;
  if (shape.empty() || shape.back() <= 0) return Fail("Q4Conv1x1: X needs a channel axis");
  const size_t k = static_cast<size_t>(shape.back()), m = count / k;
  if (weightBytes != WeightBytes(kernel->n, k)) return Fail("Q4Conv1x1: W does not match n and the channels of X");
  float xs = 0.f, ys = 0.f;
  uint8_t xz = 0, yz = 0;
  if (OrtStatus* status = Scalar(context, 1, &xs)) return status;
  if (OrtStatus* status = Scalar(context, 2, &xz)) return status;
  if (OrtStatus* status = Scalar(context, 4, &ys)) return status;
  if (OrtStatus* status = Scalar(context, 5, &yz)) return status;
  if (!(ys > 0.f) || !std::isfinite(xs)) return Fail("Q4Conv1x1: bad quantization scales");
  shape.back() = static_cast<int64_t>(kernel->n);
  OrtValue* y = nullptr;
  if (OrtStatus* status = api->KernelContext_GetOutput(context, 0, shape.data(), shape.size(), &y)) return status;
  void *xd = nullptr, *wd = nullptr, *yd = nullptr;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(x), &xd)) return status;
  if (OrtStatus* status = api->GetTensorMutableData(const_cast<OrtValue*>(w), &wd)) return status;
  if (OrtStatus* status = api->GetTensorMutableData(y, &yd)) return status;
  if (m == 0) return nullptr;
  kernel->Multiply(static_cast<const uint8_t*>(xd), m, k, xs, xz, static_cast<const uint8_t*>(wd), ys, yz,
                   static_cast<uint8_t*>(yd));
  return nullptr;
}

OrtStatus* ORT_API_CALL CreateKernel(const OrtCustomOp*, const OrtApi*, const OrtKernelInfo* info, void** out) {
  int64_t n = 0;
  if (OrtStatus* status = api->KernelInfoGetAttribute_int64(info, "n", &n)) return status;
  if (n <= 0) return Fail("Q4Conv1x1: n must be positive");
  Kernel* kernel = new (std::nothrow) Kernel{static_cast<size_t>(n), DetectPath(), {}, {}, {}, {}};
  if (kernel == nullptr) return api->CreateStatus(ORT_FAIL, "Q4Conv1x1: out of memory");
  *out = kernel;
  return nullptr;
}

OrtCustomOp MakeOp() {
  OrtCustomOp op{};
  op.version = kApiVersion;
  op.GetName = [](const OrtCustomOp*) { return "Q4Conv1x1"; };
  op.GetExecutionProviderType = [](const OrtCustomOp*) -> const char* { return nullptr; };
  op.GetInputTypeCount = [](const OrtCustomOp*) -> size_t { return 6; };
  op.GetInputType = [](const OrtCustomOp*, size_t index) {
    return index == 1 || index == 4 ? ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT : ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8;
  };
  op.GetOutputTypeCount = [](const OrtCustomOp*) -> size_t { return 1; };
  op.GetOutputType = [](const OrtCustomOp*, size_t) { return ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT8; };
  op.KernelDestroy = [](void* kernel) { delete static_cast<Kernel*>(kernel); };
  op.GetInputCharacteristic = [](const OrtCustomOp*, size_t) { return INPUT_OUTPUT_REQUIRED; };
  op.GetOutputCharacteristic = [](const OrtCustomOp*, size_t) { return INPUT_OUTPUT_REQUIRED; };
  op.GetInputMemoryType = [](const OrtCustomOp*, size_t) { return OrtMemTypeDefault; };
  op.GetVariadicInputMinArity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicInputHomogeneity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicOutputMinArity = [](const OrtCustomOp*) { return 1; };
  op.GetVariadicOutputHomogeneity = [](const OrtCustomOp*) { return 1; };
  op.CreateKernelV2 = CreateKernel;
  op.KernelComputeV2 = Compute;
  return op;
}

}  // namespace

extern "C" {

#define LJ_EXPORT __attribute__((visibility("default")))

// ONNX Runtime's entry point for operator libraries (SessionOptions.registerCustomOpLibrary, Android).
LJ_EXPORT OrtStatus* ORT_API_CALL RegisterCustomOps(OrtSessionOptions* options, const OrtApiBase* base) {
  static const OrtApi* const runtime = base->GetApi(kApiVersion);
  if (runtime == nullptr) return nullptr;  // older than 1.16: there is no API to fail through
  api = runtime;
  static OrtCustomOp op = MakeOp();
  static OrtCustomOpDomain* domain = nullptr;
  if (domain == nullptr) {
    if (OrtStatus* status = api->CreateCustomOpDomain("latentjam", &domain)) return status;
    if (OrtStatus* status = api->CustomOpDomain_Add(domain, &op)) return status;
  }
  return api->AddCustomOpDomain(options, domain);
}

// The same for runtimes that link this library statically (iOS); see ljq4.h.
LJ_EXPORT OrtStatus* LjRegisterOrtOps(OrtSessionOptions* options, const OrtApiBase* base) {
  return RegisterCustomOps(options, base);
}

// Bytes lj_q4_pack writes for an [n, k] matrix.
LJ_EXPORT size_t lj_q4_packed_size(size_t n, size_t k) { return WeightBytes(n, k); }

// Packs values q [n, k] in -8..7, scales [n, ceil(k / 32)] and biases [n] the way the model carries them.
LJ_EXPORT void lj_q4_pack(size_t n, size_t k, const int8_t* q, const float* scale, const float* bias, uint8_t* out) {
  PackWeights(n, k, kModelKr, q, scale, bias, out);
}

}  // extern "C"
