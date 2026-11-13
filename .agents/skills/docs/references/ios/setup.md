# iOS setup

## Host

Set the deployment target to iOS 18 or later and supply a nonempty `NSCameraUsageDescription`. React Native autolinks the scanner.

For Expo prebuild, add `'react-native-document-scanner'` to the app's `plugins` array before `expo-build-properties` if you use it. The scanner plugin adds the bundled OpenCV podspec to the iOS extra pods. Remove any manual OpenCV entry from your Expo config.

For apps with a manually maintained Podfile, declare the local OpenCV dependency inside the consuming target and adjust the path for your package layout.

```ruby
pod 'DocumentScannerOpenCV', :podspec => '../node_modules/react-native-document-scanner/ios/DocumentScannerOpenCV.podspec'
```

The package podspec owns the TensorFlow Lite dependency and canonical model bundle. The pinned OpenCV framework supports arm64 device and x86_64 simulator. Use an Intel simulator or a device build.

From `example/` on a Mac, run `npm run ios:prebuild`, then `npm run ios -- --device` for the Expo development build. The example supports iPhone and iPad. Its media-library plugin supplies `NSPhotoLibraryAddUsageDescription` for write-only Photos access. Expo Sharing presents the PDF share sheet and provides a default iPad popover anchor.

## Permission and validation

- `CameraPermission` works independently of the camera view. Requests share one foreground prompt. Denied or restricted is `blocked`; subscribers refresh on app activation after a Settings change.
- The podspec's `ScaffoldTests` test spec supplies the XCTest sources and synthetic resources. `.github/workflows/ios-validation.yml` can build and test a snapshot on an Intel runner, or run prebuilt test products using matching Xcode. The vmac profile is `docscanner`.
- Use `CameraSessionTests`, `CameraControlsTests`, `LiveGuidanceTests`, `CaptureGateTests` and `StillCaptureTests` for focused camera changes. Simulated frames and a fake camera exercise ordering and cancellation. Check real permission transitions, focus, torch, frame/still orientation and photo quality on hardware. Report simulator and device results separately.
- On iPhone and iPad, verify a human-triggered capture through review, crop and filter, return to the camera, and export to Photos and the share sheet. Include Photos denial and share cancellation. The [iOS camera reference](camera.md) describes the frame evidence used after an autofocus scan settles.
