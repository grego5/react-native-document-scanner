# Resolution check

Standalone Android app (no React Native, no Metro) showing how the scanner picks camera sizes on a device.

- Lists the back camera's hardware level, active array, JPEG sizes and high-resolution JPEG sizes.
- Uses CameraX 1.7.0-rc01 and binds Preview, ImageCapture and ImageAnalysis through a `LifecycleCameraController` and `SessionConfig`, as `HybridDocumentScannerCameraView` does, for `zsl`, `latency` or `quality` with normal sizes, or `latency hi-res` or `quality hi-res` with the camera's high-resolution sizes allowed too (the scanner allows them in `document` mode, and in `photo` mode with `highResolutionPhoto`, ranked as it ranks them).
- Each capture adds a line with its size, shutter time (press to exposure), saved time (press to written file) and file size; `share timings` sends only those lines and the device as text.
- Uses the scanner's default minimum frame rate of 0 (the camera's own range), reports the supported ranges, and uses its exposure policy to select a range when that minimum is changed in the diagnostic app.
- Shows the ranked capture candidates, the negotiated size and crop of each stream, and the first analysis frame.
- Compiles the scanner's own `CaptureResolutionPolicy.kt` and `CameraExposurePolicy.kt`; keep the CameraX version and use-case setup in step with the camera view.
- `capture` saves the JPEG with the full analysis buffer and preview of that moment; `share diagnostics` sends them with the report, which is also logged under the `ResolutionCheck` tag.
- Mode selection and sharing are disabled until a capture finishes, and the capture result retains its requested mode.

Build, and optionally install and open:

```
./tools/resolution-check.ps1 [-Device <serial>]
```

The APK is written to `.local/resolution-check/scanner-resolution-check.apk`.
