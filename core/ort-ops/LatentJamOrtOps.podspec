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
  pack = "#{kai}/kai/ukernels/matmul/pack"
  blocks = "#{kai}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p"
  channels = "#{kai}/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4cxp"
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => [kai, pack, blocks, channels, "#{cpp}/vendor/onnxruntime"]
      .map { |path| "\"$(PODS_TARGET_SRCROOT)/#{path}\"" }.join(' '),
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'GCC_ENABLE_CPP_EXCEPTIONS' => 'NO',
    'GCC_ENABLE_CPP_RTTI' => 'NO',
    # KleidiAI's kernels are 64-bit Arm only; an Intel simulator build takes the portable path.
    'EXCLUDED_SOURCE_FILE_NAMES[arch=x86_64]' => 'kai_matmul_clamp_f32_qai8dxp*',
  }
  s.default_subspecs = 'Core', 'I8mm', 'Dotprod'

  s.subspec 'Core' do |core|
    core.source_files = "#{cpp}/ljq4.{h,cc}", "#{cpp}/ljdots.{h,cc}", "#{kai}/kai/kai_common.h",
                        "#{pack}/kai_rhs_pack_nxk_qsi4c32p_qsu4c32s1s0.{h,c}", "#{pack}/kai_rhs_pack_nxk_qsi4cxp_qs4cxs1s0.{h,c}",
                        "#{blocks}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4c32p8x8_4x8x32_neon_i8mm{.h,.c,_asm.S}",
                        "#{blocks}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4c32p8x4_4x8_neon_dotprod{.h,.c,_asm.S}",
                        "#{cpp}/vendor/onnxruntime/*.h"
    core.public_header_files = "#{cpp}/ljq4.h", "#{cpp}/ljdots.h"
  end
  # The per-channel kernels keep their assembly inline and need the extension enabled to compile; each runs
  # only after the CPU reported it (ljq4.cc).
  s.subspec 'I8mm' do |i8mm|
    i8mm.source_files = "#{channels}/kai_matmul_clamp_f32_qai8dxp4x8_qsi4cxp8x8_8x8x32_neon_i8mm.{h,c}"
    i8mm.compiler_flags = '-march=armv8.2-a+i8mm+dotprod'
  end
  s.subspec 'Dotprod' do |dotprod|
    dotprod.source_files = "#{channels}/kai_matmul_clamp_f32_qai8dxp4x4_qsi4cxp8x4_8x8x32_neon_dotprod.{h,c}"
    dotprod.compiler_flags = '-march=armv8.2-a+dotprod'
  end
end
