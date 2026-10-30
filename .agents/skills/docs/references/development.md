# Development

## Environment

Parse generated JSON reports with Python `json.load()` and print only the fields needed for the current question.
Write edited text files with LF line endings; in Python use `open(..., newline="\n")` or `Path.write_text(..., newline="\n")` to keep Windows writes consistent with Git.

## Routine checks

- `npm run check`: library and example typechecks, then the JS contract tests, stopping at the first failure. It runs no Gradle, generation, APK, install or device command. `npm run check -- --list` prints the plan; `npm run typecheck`, `typecheck:example` and `test:contracts` run one step.
- `npm run test:native -- -Test '*DocumentFocusPolicyTest'` runs JVM tests through `tools/test-native.ps1`, which sets the JDK, ABI and Gradle cache. It may compile Kotlin, resources and Gradle plugins, so never run it, or any compile-only Gradle target, under a no-build instruction. It never builds or installs an APK.
  - Pass several filters as one comma-separated `-Test` list.
  - `-RecompilePlugins` works around stale Expo Gradle plugin output ("Could not find implementation class ..."). Concurrent Gradle runs on this checkout cause the same failure.
  - `-Trace <path>` selects live-trace replay input.

## Build and run

Use Android Studio's JDK and the installed SDK; set `JAVA_HOME` for the command only. Gradle runs from `example/android`, which is prebuild output.

- App: from `example/`, `npm run start` (Metro on 8088), then `npm run android` (arm64) or `npm run android:emulator` (x86_64). Both install only to a matching online target. `npm run android:release` builds a standalone APK signed with the development keystore.
- After changing a spec in `src/`, run `npm run nitrogen`; never edit `nitrogen/generated/`.
- Lifecycle check: `npm run android:lifecycle -- -Device <adb-id>`. It clears that device's Logcat.
- Library tasks (with `-PreactNativeArchitectures=<abi>`): `:react-native-document-scanner:assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`.
- Instrumented tests: `npm run test:device -- -Device <adb-id> [-Class <Class or Class#method>,...] [-Measure] [-Argument name=value]` builds the androidTest APK, installs it only if the build succeeded, runs the tests and fails unless the runner reports OK. Run it from PowerShell, not Git Bash, so paths are not rewritten; grant CAMERA once for the camera tests.
- Render timings, private model parity and scene recordings (`RenderTimingTest`, `DocCornerNetRuntimeTest.privatePhotosMeetNativeParityBudgetAndRecordWarmTimings`, `LiveQuadTraceRecordingTest`) are skipped unless the run passes `-e measure true`, so a plain run checks behaviour only.
- Tests cover dynamic behaviour, lifecycle and concurrency, non-trivial algorithms, security boundaries and the example's session rules. Do not add a test that only pins a single wiring fix, a passthrough or a basic guarantee the code states plainly.
- Unattended runs exclude `LiveCameraRuntimeTest`: it needs the phone awake and unlocked, and a human to tap every shutter in `CameraValidationActivity`.
- Confirm targets with `adb devices`. Report build and device results separately; a build is not a device result.

## Hand checks

Compare one switch at a time in the example's Options panel, with the scene, lighting and other settings unchanged. The adaptive status line shows the last reason inference woke (`reset: …`).

- Camera: permission grant and denial, capture, rotation, background and foreground. `LiveCameraRuntimeTest#inactiveCameraStaysSuspendedAcrossForegroundAndSettingsThenResumesWithoutRemounting` checks `mode` `off` without a shutter.
- Session: one pager for capture and saved pages, thumbnail navigation, filter modal, Crop and Shadow toggles, global Shadow default for new captures, separate crop screen, Add page, Done and Save, Delete, New scan, Back confirmation, the 50-page limit, PDF share and gallery save. Verify the camera stays mounted and inactive beneath preview and crop.
- Shutter focus: with the quality gate on, press while focus is scanning; no photo fires until focus is reported, and the 2 s timeout rejects rather than shoots.
- Guide validation: a stationary page near competing table edges, then real changes in tilt, rotation, framing and distance, occlusion and focus loss. Replay alone cannot show which visible boundary was the paper.
- Document focus: an initially defocused page, approach and retreat, pans, target loss, taps during an automatic scan, capture during a pending scan, background and resume.
- Adaptive detection: hold a text page with normal hand tremor, then pan slowly; inference should stay at zero while the guide follows. Move nearer or farther (focus rescans without inference), tilt slightly (the outline reshapes and stays on the edges) and strongly (`shape changed`), cover part of the page (`revalidating`), then remove it. The guide must not survive prolonged target loss. Battery and CPU savings need live measurement.
- Torch: `auto` in a dim room lights within about a second and stays lit through captures; in an ordinary room it stays off. The stats line shows the exposure × ISO product next to the torch state, for tuning the dark threshold.
- The stats line's `checks X ms/frame` sums flow, stabilizer and tracked-retention time over every delivered frame. It excludes inference and is not a process-wide CPU measure.

## Live quad traces

`LiveQuadTraceRecordingTest` records pixel-free TSV from the instrumentation harness: raw and accepted quads, decisions, timings, gyroscope, camera optics, and the flow estimate with its fitted transform. The user follows the on-screen instructions and taps Stop recording; recording also ends at 2,048 rows or 60 s.

```powershell
adb -s <serial> shell "am instrument -w -e class com.margelo.nitro.documentscanner.LiveQuadTraceRecordingTest -e measure true -e liveQuadScene close-range -e liveQuadCapture true -e liveQuadInstructions 'Fill most of the screen with the page, tap the green button once, then Stop recording.' com.margelo.nitro.documentscanner.test/androidx.test.runner.AndroidJUnitRunner"
adb -s <serial> pull /sdcard/Android/data/com.margelo.nitro.documentscanner.test/files/live-quad-traces/. .local/live-quad-traces/
```

Options: `liveQuadCaptures <n>` (user-tapped shutters), `liveQuadKeepPhotos true`, `liveQuadAnalysisWidth <px>`, `liveQuadQualityGate true`, `liveQuadFlow false` (cadence baseline). The trace config stores the acceptance settings.

Replay: `testDebugUnitTest -PliveQuadTrace=<file or dir>`, relative to the repository root, writes `android/build/reports/live-quad-trace/summary.txt`.

Replay limits:

- Traces are unversioned and must match the current columns and config; older layouts are rejected. Strict row equality applies only when the recorded config equals the current one and the trace has no gyro rows.
- Pixel-free replay cannot regenerate feature tracks. Recorded transforms belong to the recorded feature regions. Evaluating a different tracking policy needs live recordings.
- Analysis frames are about 6x smaller than the photo, so only capture rows can test the 0.2% capture blur limit. Record rotation, sideways motion and approach separately.

## Evidence (Samsung SM-G998U, arm64)

- Capture negotiates 4000x3000, with 960x720 preview and 640x480 analysis.
- `takePhoto` resolves in about 0.35 s with `zsl`, 0.9 s with `latency`, and 1.0 to 1.2 s with `quality`.
- Native photo preprocessing takes a median 9.5 ms against 53 ms for the Kotlin oracle, with bitwise-equal tensors.
- Live guidance runs at a median 14.6 Hz, with 27 ms from analysis to overlay.
- Six recorded scenes (3,562 frames): of 491 raw-candidate disagreements, 28 reached the guide. The held guide shows on 4% of accepted frames when steady, 16% in quick pans and 24% with a hand in the way.
- A triggered focus scan costs 450–650 ms.
