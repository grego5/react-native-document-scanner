# Android validation

- `npm run test:native -- -Test '*CaptureGateTest'` filters JVM suites. It compiles native project inputs and requires a prebuilt `example/android`, the Android SDK and a JDK. `-RecompilePlugins` bypasses stale plugin build outputs.
- Check the target with `adb devices`. In PowerShell, `npm run test:device -- -Device <serial> -Class <Class#method>` builds, installs and runs instrumentation. `-Measure` opts into timing, private model parity and recording tests.
- Live shutter checks require a human operator. Include permission, suspend/resume and rotation. Keep the scene fixed when comparing settings.
- For session changes, exercise deletion during decode, close during export and a snapshot read after an awaited mutation. Published exports must survive close.

## Diagnostics and trace replay

- Call `DocumentScanner.setDiagnosticsEnabled(true)` before mounting a camera. `exportDiagnostics()` returns a cache `file://` URI for host sharing. The example enables diagnostics at launch and exposes **Share log** on both camera screens. Exports contain recent log lines and mounted document cameras' last 15 s of trace. They contain no pixels, but text logs can contain recognized text. iOS reports `feature/unsupported`.
- Match Camera2 results and detector decisions by sensor timestamp. A one-shot CameraX request can fall outside the repeating callback. App commands and guide expiry use elapsed device time.
- Use `LiveGuideTraceRecordingTest` with `-Measure` for pixel-free TSV. Enable `liveGuideCapture` only with a human-tapped shutter. Pull `files/live-guide-traces` into ignored `.local/`. Replay with `LiveGuideTraceReplayTest` and `-Argument liveGuideTrace=<device-path>`. Use the current schema and matching config for exact guide comparisons. Replay consumes recorded flow, so tracking and actual page boundaries require device validation.

## Focus and OCR checks

| Area | Focused JVM tests | Device checks |
| --- | --- | --- |
| Document focus and capture | `FocusControllerTest`, `FocusCycleTest`, `GuideSpotTest`, `DepthOfFieldTest`, `ScannerPressTest`, `CaptureGateTest` | Press on a held page lock. In dim light, correlate torch request, confirmation and lit frames with the press. Include guide dropout during relock and suspension during a wait. |
| Text focus, frames and zoom | `TextFocusCycleTest`, `TextFramesTest`, `TextZoomTest` | Check tap takeover, region change during a scan and rapid zoom requests. Use distinct text at each ratio and compare first admitted frames with capture-result zoom and sensor time. |

Use `RNDocumentScannerText` for focus transitions, zoom admission, frame delivery, OCR cost and edge width. For text-session or selection changes, also run `npm run test:contracts` and verify capture, retake and remount on Android.
