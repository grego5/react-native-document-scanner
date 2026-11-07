# iOS setup

## Host

Set the deployment target to iOS 18 or later and supply a nonempty `NSCameraUsageDescription`. React Native autolinks the scanner. Declare its local OpenCV dependency inside the consuming Podfile target, adjusting for the host's package layout:

```ruby
pod 'DocumentScannerOpenCV', :podspec => '../node_modules/react-native-document-scanner/ios/DocumentScannerOpenCV.podspec'
```

For Expo prebuild, see the deployment target and extra pod in `example/app.config.ts`. The package podspec owns the TensorFlow Lite dependency and canonical model bundle. The pinned OpenCV framework supports arm64 device and x86_64 simulator. Use an Intel simulator or a device build.

## Permission and validation

- `CameraPermission` works independently of the camera view. Requests share one foreground prompt. Denied or restricted is `blocked`; subscribers refresh on app activation after a Settings change.
- The podspec's `ScaffoldTests` test spec supplies the XCTest sources and synthetic resources. `.github/workflows/ios-validation.yml` can build and test a snapshot on an Intel runner, or run prebuilt test products using matching Xcode. The vmac profile is `docscanner`.
- Use `CameraSessionTests`, `CameraControlsTests`, `LiveGuidanceTests`, `CaptureGateTests` and `StillCaptureTests` for focused camera changes. Simulated frames and a fake camera exercise ordering and cancellation. Check real permission transitions, focus, torch, frame/still orientation and photo quality on hardware. Report simulator and device results separately.
