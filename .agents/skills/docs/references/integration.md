# Host integration

Install `react-native-document-scanner` and `react-native-nitro-modules` and rebuild the app. The package declares React Native 0.78+ and defaults to Android API 24+. Expo uses a development build. On iOS follow the [host setup](ios/setup.md).

- Create a session with `DocumentScanner.createSession()` in a host lifetime boundary such as a React effect. Close it on replacement or unmount. Pass the same session to `Camera`, `ImagePager`, `PageThumbnail` and `CropEditor`. Use `useCameraPermission()` for permission UI.
- `Camera` resolves `mode="document"` or `mode="photo"` into explicit props for the same native view. Photo mode disables live document guidance and automatic correction. `active` controls camera use independently of session lifetime. The example starts a new session on mode changes as host policy.
- Capture via the camera ref. `takePhoto()` returns an admitted page ID. Observe `session.subscribe` and `session.getSession` with `useSyncExternalStore` for ordered pages and background readiness. Keep navigation and selected page in React.
- `imageCorrection` and `outputOptions` configure future pages. Output dimensions are in centimeters and JPEG quality is 0–1. Change an admitted page with session commands or submit its native crop draft. Export with `session.createPdf()` or `session.exportImages()` to host-owned destinations.
- See `src/index.ts` for public exports, the `*.nitro.ts` files for native signatures, and `example/` for a working host. [Architecture](architecture.md) owns the lifecycle and publication rules.

The example uses `cornerDetectionModel="asset:///doccornernet/model.tflite"` for the bundled model. Wire the shutter to the camera ref and enable it when `onCaptureStateChange` reports `ready`.
