# Android validation

- Run `npm run test:native -- -Test '*CaptureGateTest'` for filtered JVM tests. The runner compiles Kotlin and Gradle inputs, so a no-build request excludes it and compile-only Gradle targets. For a stale Expo Gradle plugin, retry with `-RecompilePlugins`.
- Use Android Studio's JDK and the installed SDK. Gradle runs from prebuilt `example/android`. From `example/`, use `npm run start` for Metro and `npm run android` for an arm64 target or `npm run android:emulator` for x86_64.
- Verify the target with `adb devices`. Run `npm run test:device -- -Device <serial> -Class <Class#method>` in PowerShell. The runner builds, installs and runs the test APK. Use `-Measure` for opt-in timing, private model parity and recording tests.
- Exclude live shutter tests from unattended runs. With a human operator, check permission, suspend/resume, rotation, focus and page arrival, dim-light fallback, tap focus and shutter behavior. Use a fixed scene when comparing settings.
- For session changes, exercise deletion during a decode, close during export, and awaited mutations followed by a snapshot read. Verify selection survives unrelated edits and published exports survive close.

## Live guide traces

For issues on testers' devices, use the example's release APK: it turns diagnostics on at launch, and **Share log** at the bottom of the camera and text screens sends the export through the share sheet right after the issue. The export holds the recent log lines and each mounted document camera's last 15 s trace, pixel-free. Match repeating Camera2 requests and results to detector decisions by sensor timestamp. A one-shot CameraX request can fall outside that callback. App commands and guide expiry use the device's elapsed clock, and expiry includes the guide acceptance ID.

`LiveGuideTraceRecordingTest` records pixel-free TSV through instrumentation with `-Measure`. Enable `liveGuideCapture` only for a human-tapped shutter. Pull the test app's `files/live-guide-traces` into ignored `.local/`. Replay through `LiveGuideTraceReplayTest` with `-Argument liveGuideTrace=<device-path>`. The trace must match the current schema; exact guide equality requires matching config. Replay reuses recorded flow and cannot validate different feature tracking or the actual paper boundary.


## Focus and OCR checks

- Document focus: check a bright press on an existing page lock and a dim press that reaches torch recovery. Match torch request, confirmation and lit frames to the press outcome. Include guide dropout during a lens search and app suspension during a wait.
- Text focus: check tap takeover, a region change during a lock's scan, initial zoom, rapid return to a previous zoom, and refusal. Compare the first admitted OCR frame with capture-result zoom and sensor time. Use different text in the two fields of view so stale readings are visible.
- Debug tag `RNDocumentScannerText` records each focus lock with its reason (`text focus lock for …`), its release (`text focus lock released: …`), lost and resent triggers, turns, zoom confirmation and frame admission, and delivered analyzer rate against camera frames with the longest analyzer gap and recognition cost. Use a fixed scene at different recognition rates to isolate analyzer contention.
