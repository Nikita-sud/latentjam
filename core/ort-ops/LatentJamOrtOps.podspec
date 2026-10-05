# LatentJam's own ONNX Runtime operators for iOS, linked statically into the app; the app registers them
# with LjRegisterOrtOps (src/main/cpp/ljq4.h). Android builds the same sources with CMake (build.gradle.kts).
Pod::Spec.new do |s|
  s.name = 'LatentJamOrtOps'
  s.version = '1.0.0'
  s.summary = "LatentJam's ONNX Runtime operators: the music encoder's 4-bit pointwise convolutions."
  s.homepage = 'https://github.com/Nikita-sud/latentjam'
  s.license = { :type => 'Apache-2.0' }
  s.author = 'LatentJam Project'
  s.source = { :git => 'https://github.com/Nikita-sud/latentjam.git' }
  s.platform = :ios, '15.1'

  cpp = 'src/main/cpp'
  kai = "#{cpp}/vendor/kleidiai"
  kernels = "#{kai}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p"
  s.source_files = "#{cpp}/ljq4.{h,cc}", "#{kai}/kai/kai_common.h",
                   "#{kai}/kai/ukernels/matmul/pack/kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.{h,c}",
                   "#{kernels}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm.{h,c}",
                   "#{kernels}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm_asm.S",
                   "#{kernels}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod.{h,c}",
                   "#{kernels}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod_asm.S",
                   "#{cpp}/vendor/onnxruntime/*.h"
  s.public_header_files = "#{cpp}/ljq4.h"
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => [kai, "#{kai}/kai/ukernels/matmul/pack", kernels, "#{cpp}/vendor/onnxruntime"]
      .map { |path| "\"$(PODS_TARGET_SRCROOT)/#{path}\"" }.join(' '),
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'GCC_ENABLE_CPP_EXCEPTIONS' => 'NO',
    'GCC_ENABLE_CPP_RTTI' => 'NO',
    # KleidiAI's kernels are 64-bit Arm only; an Intel simulator build takes the portable path.
    'EXCLUDED_SOURCE_FILE_NAMES[arch=x86_64]' => 'kai_matmul_clamp_f32_qai8dxp*',
  }
end
