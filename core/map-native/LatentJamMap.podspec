Pod::Spec.new do |s|
  s.name = 'LatentJamMap'
  s.version = '0.1.0'
  s.summary = 'Experimental offline Barnes-Hut map backend.'
  s.homepage = 'https://github.com/Nikita-sud/latentjam'
  s.license = { :type => 'Apache-2.0 AND MIT AND BSD-4-Clause', :file => 'THIRD-PARTY-NOTICES.txt' }
  s.author = 'LatentJam Project'
  s.source = { :git => 'https://github.com/Nikita-sud/latentjam.git' }
  s.platform = :ios, '15.1'
  s.source_files = 'src/main/cpp/ljmap.{h,cc}'
  s.public_header_files = 'src/main/cpp/ljmap.h'
  s.preserve_paths = 'src/main/cpp/vendor/**/*', 'THIRD-PARTY-NOTICES.txt'
  s.pod_target_xcconfig = {
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'GCC_ENABLE_CPP_EXCEPTIONS' => 'YES',
    'OTHER_CPLUSPLUSFLAGS' => '$(inherited) -ffp-contract=off -fno-fast-math',
    'HEADER_SEARCH_PATHS' => %w[qdtsne knncolle aarand subpar sanisizer].map {
      |dep| "\"$(PODS_TARGET_SRCROOT)/src/main/cpp/vendor/#{dep}/include\""
    }.join(' ')
  }
  s.libraries = 'c++'
end

