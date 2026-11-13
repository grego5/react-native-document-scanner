# Official OpenCV binary: arm64 device and x86_64 simulator.
# Host Podfile setup: .agents/skills/docs/references/ios/setup.md.
Pod::Spec.new do |s|
  s.name = 'DocumentScannerOpenCV'
  s.version = '4.12.0'
  s.summary = 'Official OpenCV iOS framework for the document scanner'
  s.homepage = 'https://opencv.org'
  s.license = 'Apache-2.0'
  s.author = 'OpenCV contributors'
  s.platform = :ios, '18.0'
  s.source = { :http => 'https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-ios-framework.zip' }
  s.vendored_frameworks = 'opencv2.framework'
  s.frameworks = ['Accelerate', 'AVFoundation', 'CoreMedia', 'CoreVideo', 'CoreGraphics', 'Foundation', 'UIKit']
  s.libraries = ['c++', 'z']
end
