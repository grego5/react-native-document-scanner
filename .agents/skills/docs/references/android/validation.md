# Android validation

- Run `npm run test:native -- -Test '*CaptureGateTest'` for filtered JVM tests. The runner compiles Kotlin and Gradle inputs, so a no-build request excludes it and compile-only Gradle targets. For a stale Expo Gradle plugin, retry with `-RecompilePlugins`.
- Use Android Studio's JDK and the installed SDK. Gradle runs from prebuilt `example/android`. From `example/`, use `npm run start` for Metro and `npm run android` for an arm64 target or `npm run android:emulator` for x86_64.
- Verify the target with `adb devices`. Run `npm run test:device -- -Device <serial> -Class <Class#method>` in PowerShell. The runner builds, installs and runs the test APK. Use `-Measure` for opt-in timing, private model parity and recording tests.
- Exclude live shutter tests from unattended runs. With a human operator, check permission, suspend/resume, rotation, focus and page arrival, dim-light fallback, tap focus and shutter behavior. Use a fixed scene when comparing settings.
- For session changes, exercise deletion during a decode, close during export, and awaited mutations followed by a snapshot read. Verify selection survives unrelated edits and published exports survive close.

## Live guide traces

For intermittent preview issues on Android 10 and later, build the example's `assembleRelease` with `-PdocumentScannerStandaloneTrace=true`. The APK runs without Metro or ADB. In Scanner mode with Camera stats visible, tap **Save last 15s trace** immediately after the issue. The trace goes to Downloads as a pixel-free TSV. Match repeating Camera2 requests and results to detector decisions by sensor timestamp. A one-shot CameraX request can fall outside that callback. App commands and guide expiry use the device's elapsed clock, and expiry includes the guide acceptance ID.

`LiveGuideTraceRecordingTest` records pixel-free TSV through instrumentation with `-Measure`. Enable `liveGuideCapture` only for a human-tapped shutter. Pull the test app's `files/live-guide-traces` into ignored `.local/`. Replay through `LiveGuideTraceReplayTest` with `-Argument liveGuideTrace=<device-path>`. The trace must match the current schema; exact guide equality requires matching config. Replay reuses recorded flow and cannot validate different feature tracking or the actual paper boundary.
