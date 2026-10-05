# Vendored third-party sources

Copied unmodified; each keeps its own licence. Update them together with `../ljq4.cc` and re-run its
device test (`OrtOperatorsDeviceTest`): the model carries weights packed in KleidiAI's layout.

| Directory | Project | Version | Licence |
|---|---|---|---|
| `kleidiai/` | [Arm KleidiAI](https://github.com/ARM-software/kleidiai) | v1.20.0 (`archive/refs/tags/v1.20.0.tar.gz`, SHA-1 6895e72b3d5cf1173358164cb3d64c9d7d33cc84, as ONNX Runtime 1.26.0 pins it) | Apache-2.0 (`kleidiai/LICENSES/`) |
| `onnxruntime/` | [ONNX Runtime](https://github.com/microsoft/onnxruntime) C API headers | v1.26.0 (8c546c37b43caaca1fa25db430dab94b901cf277) | MIT (`onnxruntime/LICENSE`) |

From KleidiAI only these files: `kai/kai_common.h`; the RHS packers `kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0`
(blocks of 32) and `kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0` (a scale per channel); and four matmul kernels for
int8 activations x 4-bit weights, with their `.c` and `.h` (and `_asm.S` for the two block kernels; the
per-channel ones keep their assembly inline):
`kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm`,
`kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod`,
`kai_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm` and
`kai_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod`.
