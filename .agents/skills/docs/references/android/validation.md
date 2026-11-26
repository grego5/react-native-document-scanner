# Android validation

- `npm run test:native -- -Test '*CaptureGateTest'` filters JVM suites. It compiles native project inputs and requires a prebuilt `example/android`, the Android SDK and a JDK. `-RecompilePlugins` bypasses stale plugin build outputs.
- Check the target with `adb devices`. In PowerShell, `npm run test:device -- -Device <serial> -Class <Class#method>` builds, installs and runs instrumentation. `-Measure` opts into timing, private model parity and recording tests.
- Live shutter checks require a human operator. Include permission, suspend/resume and rotation. Keep the scene fixed when comparing settings.
- For session changes, exercise deletion during decode, close during export and a snapshot read after an awaited mutation. Published exports must survive close.

## Diagnostics and trace replay

- Call `DocumentScanner.setDiagnosticsEnabled(true)` before mounting a camera. `exportDiagnostics()` returns a cache `file://` URI for host sharing. The example enables diagnostics at launch and exposes **Share log** on both camera screens. Exports contain recent log lines and mounted document cameras' last 15 s of trace. They contain no pixels, but text logs can contain recognized text. iOS reports `feature/unsupported`.
- Match Camera2 results and detector decisions by sensor timestamp. A one-shot CameraX request can fall outside the repeating callback. App commands and guide expiry use elapsed device time.
- Use `LiveGuideTraceRecordingTest` with `-Measure` for pixel-free TSV. Enable `liveGuideCapture` only with a human-tapped shutter. Pull `files/live-guide-traces` into ignored `.local/`. Replay with `LiveGuideTraceReplayTest` and `-Argument liveGuideTrace=<device-path>`. Use the current schema and matching config for exact guide comparisons. Replay consumes recorded flow, so tracking and actual page boundaries require device validation.
- Document frames record the print's edge width (median, rows, columns, edge count) over the tracked page, or the guide area without one, and its focus verdict with the sharp floor, in the trace's `edges`, `edgeArea` and `focus` columns, the diagnostics export and the `RNDocumentScannerLive` debug log. Lock lines name the verdict that started them. The trace's `drift` and `acceleration` columns carry each frame's net turn over 300 ms, as frame widths, and peak acceleration, which the capture's framing check judges.
- For calibrating capture limits, each approved shot logs a `capture gate` line: who held focus, the print verdict, the frame's AF state, gyro blur, drift, acceleration and exposure, the wait since the press, and why steadiness restarted meanwhile. A `capture photo` line then gives the saved photo's own edge width over a 1024-pixel square at the page's print spot, whether it flagged the page soft, how long the measure took, the gyro blur across the still's own exposure in photo pixels, the time and turn from the gate's newest frame to that exposure, and the same gate values. The lines are written only while debug logging or diagnostics is on. The photo measure runs on every document capture, since it sets the page's `capture.soft`, but after `takePhoto()` resolves, on the session's work queue behind the page's analysis. `PhotoSharpnessTest` covers the soft limit.

## Focus and OCR checks

| Area | Focused JVM tests | Device checks |
| --- | --- | --- |
| Document focus and capture | `FocusControllerTest`, `FocusCycleTest`, `GuideSpotTest`, `DepthOfFieldTest`, `CaptureGateTest` | Press on a held page lock. In dim light, correlate torch request, confirmation and lit frames with the press. Include guide dropout during relock and suspension during a wait. |
| Text focus, frames and zoom | `TextFocusCycleTest`, `TextFramesTest`, `TextZoomTest` | Check tap takeover, region change during a scan and rapid zoom requests. Use distinct text at each ratio and compare first admitted frames with capture-result zoom and sensor time. |

Use `RNDocumentScannerText` for focus transitions, zoom admission, frame delivery, OCR cost and edge width. For text-session or selection changes, also run `npm run test:contracts` and verify capture, retake and remount on Android.
