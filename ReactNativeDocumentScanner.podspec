require 'json'
package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'ReactNativeDocumentScanner'
  s.version = package['version']
  s.summary = package['description']
  s.homepage = 'https://github.com/grego5/react-native-document-scanner'
  s.license = { :type => 'Proprietary' }
  s.author = 'Document Scanner contributors'
  s.source = { :git => 'https://github.com/grego5/react-native-document-scanner.git', :tag => s.version.to_s }
  s.platform = :ios, '18.0'
  s.swift_version = '6.0'
  s.source_files = ['ios/*.{swift,h,mm}', 'cpp/include/**/*.hpp', 'cpp/src/**/*.{hpp,cpp}']
  s.public_header_files = ['ios/ScannerSourcePixels.h', 'ios/ScannerCornerRuntime.h', 'ios/ScannerDocumentRender.h', 'ios/ScannerLiveTracking.h']
  s.private_header_files = ['cpp/include/**/*.hpp', 'cpp/src/**/*.hpp']
  s.dependency 'DocumentScannerOpenCV', '4.12.0'
  s.dependency 'TensorFlowLiteC', '2.17.0'
  s.frameworks = ['UIKit', 'Accelerate', 'AVFoundation', 'CoreMedia', 'CoreVideo', 'ImageIO', 'UniformTypeIdentifiers']
  s.libraries = ['c++', 'z']
  s.pod_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '$(inherited) "$(PODS_TARGET_SRCROOT)/cpp/include" "$(PODS_TARGET_SRCROOT)/cpp/src"',
    'OTHER_CPLUSPLUSFLAGS' => '$(inherited) -ffp-contract=off',
    'SWIFT_STRICT_CONCURRENCY' => 'minimal',
    'OTHER_SWIFT_FLAGS' => '$(inherited) -Xcc -DDOCUMENT_SCANNER_SWIFT_IMPORT'
  }
  load File.join(__dir__, 'nitrogen/generated/ios/ReactNativeDocumentScanner+autolinking.rb')
  add_nitrogen_files(s)
  s.test_spec 'ScaffoldTests' do |test|
    test.source_files = 'ios/Tests/**/*.{swift,mm}'
    test.frameworks = 'XCTest'
    test.resources = ['ios/Tests/Fixtures/*.tflite', 'example/assets/doccornernet/model.tflite', 'tests/fixtures/parity/adaptive.tsv', 'tests/fixtures/parity/torch.tsv',
                      'tests/fixtures/parity/standard-resolution.tsv', 'tests/fixtures/parity/capture-events.tsv']
  end
  install_modules_dependencies(s)
end
