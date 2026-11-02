# iOS package scaffold

Requires iOS 18+, Nitro/Nitrogen 0.37.1, Swift 6 and C++20. All eight current
registrations are generated from TypeScript. Five views mount as empty UIKit
views; operation result unions return `feature/unsupported`. Synchronous methods
without a failure union throw. No camera, permission request, storage, inference
or export behavior is implemented by this scaffold.

Add the pinned official OpenCV podspec inside the app target in the Podfile,
using the installed library path (adjust for the app's node_modules layout):

```ruby
pod 'DocumentScannerOpenCV', :podspec => '../node_modules/react-native-document-scanner/ios/DocumentScannerOpenCV.podspec'
```

React Native autolinks ReactNativeDocumentScanner. It depends on TensorFlowLiteC
2.17.0 and includes all portable core sources, generated registrations and the
canonical model in DocumentScannerModels.bundle. The model remains unmodified.

The official OpenCV 4.12.0 framework has arm64 iOS device and x86_64 simulator
slices. Build an Intel simulator or generic arm64 device; Apple Silicon simulator
support remains unverified and needs a separate platform-correct slice. Do not
substitute a device arm64 binary into a simulator build.

The package is not release-ready. Task 17 verified package installation, Intel simulator and generic arm64 device
builds, actual registry construction, UIKit mounting and bundled model integrity.
Functional iOS behavior remains for the following tasks.
Machine-specific hosts, build scripts and test runners stay outside this repo.

## Binding generation

Run `npm run nitrogen` in the repository. The version-guarded wrapper copies the
installed Nitrogen 0.37.1 generator to ignored `.local/` and adjusts its struct
equality emission before generation. Installed packages and generated output are
never edited in place by the wrapper.

`DOCUMENT_SCANNER_SWIFT_IMPORT`, set only through the pod's Swift Clang-importer
flags, hides generated defaulted equality operators from Swift. Normal C++ and
Android retain those operators. This addresses the importer failure tracked in
[Nitro #1186](https://github.com/margelo/nitro/issues/1186) and
[Nitro #1376](https://github.com/margelo/nitro/issues/1376). Revalidate and remove
the workaround when changing the pinned generator/toolchain. Xcode 26.6 / Swift 6.3.3 simulator and arm64 device builds, plus seven native
smoke tests, validated this workaround for the package.
