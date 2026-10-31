# 11 — Share optical flow and motion evidence

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [05](05-share-image-geometry.md)

## Objective

Produce portable tracking transforms and motion estimates from native frames.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/FlowMotionEstimator.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/FlowQuadMotion.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/MotionBlur.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/GyroHistory.kt
- android/src/androidTest/java/com/margelo/nitro/documentscanner/processing/FlowMotionEstimatorTest.kt

## Current behavior and invariants

Tracking replaces its reference every frame; homography fits page motion; worst page region controls speed; unknown flow is never classified as steady.

## Implementation steps

1. Move feature selection, regional coverage, tracking, fitted transforms, and gyro/flow blur math into per-camera C++ state.
2. Keep sensor acquisition and clock conversion in platform adapters; carry optional clock correlation explicitly.
3. Replace Android estimator math through JNI without retaining borrowed frame pixels past their owner.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Exercise translation, rotation, approach, tilt, occlusion, sparse texture, broken chains, and timestamp gaps.
- Compare recorded-input outputs and retain the documented limitation that pixel-free traces cannot regenerate features.

## Validation commands

- Run shared build and ctest.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.FlowMotionEstimatorTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android flow/motion uses shared native state with matching evidence and safe buffer teardown.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share live flow tracking and motion estimation



## Implementation report

- Added `cpp/include/document_scanner/Motion.hpp`, `cpp/src/FlowEstimator.cpp` and `cpp/src/Motion.cpp`. One analysis-serial estimator owns gray reference/current pixels and masks; feature selection, forward/backward Lucas–Kanade tracking, regional coverage/loss, percentile/worst-region speed and deterministic similarity-seeded homography fitting now execute in the shared core. Existing luma weights, 224 px reduction, feature/coverage thresholds, 200 ms gap and fitting limits are preserved. Android Java OpenCV truncates rectangle/quad coordinates; that conversion is preserved explicitly. Unknown speed stays optional, and every valid frame replaces the reference even when tracking is unknown. Exceptions/reset/geometry or timestamp discontinuities break tracking.
- Shared `FlowHistory` owns exact contiguous transform intervals, bounded to 32 steps / 500 ms; no interpolation or partial intervals. Shared math also owns quad warps, active-array/crop/rotation pinhole geometry, gyro Rodrigues integration and peak rates, rotation homographies, gyro/flow exposure blur and evidence-source selection. Sensor/history acquisition remains Android-owned and synchronized. Chronological sensor snapshots cross by value, with an explicit optional `ClockCorrelation`; absent correlation returns unknown, not zero. Android camera gyro consumers still require Camera2 REALTIME timestamps. The native math does not infer a relationship with host uptime; only optional fit-duration diagnostics read `steady_clock`.
- Added `NativeMotion.hpp/.cpp` and `processing/NativeMotion.kt`, JNI registration, Android CMake and keep rules. HybridData owns each estimator/history, lazily created on analysis; detector disposal drains queued work before closing history and estimator on that executor. JNI retains a direct-buffer owner only during the synchronous pixel lease. Crop, position/limit and padded row/pixel strides are validated; native state keeps an independent gray copy, with no borrowed source address or owner after return. Sensor times cross as Long arrays, preserving precision beyond 2^53. Returned evidence is independent Kotlin value data; public/Nitro contracts and generated bindings are unchanged.
- `FlowMotionEstimator.kt`, `FlowQuadMotion.kt`, `GyroHistory.kt`, `LiveQuadMotion.kt` and `MotionBlur.kt` now adapt the shared implementation. Existing sensor threads, CameraX ImageProxy lifetime, binding generations and publication guards remain with their owners. No later guide/focus/torch/capture policy was implemented, and no product model or threshold was changed.
- Added `cpp/tests/MotionTest.cpp` / `motion_contracts` for translation/rotation/approach/retreat, homography tilt and outliers, sparse/narrow/collinear/inconsistent tracks, worst-region motion, partial occlusion, unusable page regions, broken/bounded interval chains, timestamp gaps/precision, gyro coverage/reversal/clock correlation, blur-source selection and borrowed-source release/overwrite. Host OpenCV discovery now also requests `video`; Android retains its existing single OpenCV runtime. Core floating-point contraction remains disabled.
- Moved all original `FlowQuadMotionTest`, `LiveQuadMotionTest` and `GateMotionTest` assertions from JVM tests to instrumented JNI tests, with their pure algorithm assertions also exercised in C++. They are not reported as JVM passes. Original flow tests remain; added pre-migration oracle comparison over 32 synthetic sequence frames across all four rotations, padded positioned-plane overwrite/close checks and truncated-buffer recovery. Test-only former Kotlin estimator/fitter oracles retain the original arithmetic; reference tracking metadata also distinguishes fitting from tracking mismatches. No oracle code enters release sources.
- The old gyro math remains a named test-only `ReferenceGyroHistory` oracle, shared by historical JVM trace reports and recorded-motion device comparisons. Four existing pixel-free device traces (steady, sideways, approach, rotation; 2026-10-24) compare raw gyro integration/blur and recorded flow blur with the shared engine. Their legacy columns/config are not passed off as a current full-guide replay. Pixel-free recordings cannot regenerate feature tracks; synthetic pixels plus the original estimator oracle verify that portion.
- Broader detector validation exposed a decoder-failure test whose immediately following bad frame could be suppressed by cadence and the flat fixture's low-score pause. That test now disables those two guards only for deterministic failure injection; production settings are unchanged. An initial expiry timing overrun passed on its isolated rerun; its timing tolerance was not relaxed. Updated architecture/core ownership guidance. C++ and Kotlin implementation stages only; no optimization/cleanup or performance claim.

### Validation

- Started clean in `C:/dev/react-native-document-scanner-parity`, branch `codex/ios-scanner-parity`. The configured C++/Kotlin language servers returned SERVER_UNAVAILABLE; focused source inspection and real compilation were used instead.
- Reused the verified Task 10 setup: MSVC 19.51.36256 / VS toolset 14.51.36231, C++20, Windows x64, OpenCV 4.12.0 and zlib 1.3.1. `cmd /c .local\task10\build-core.cmd` selects the installed VS developer environment, configures `cmake -S cpp -B .local/build/core-tests -G "Ninja Multi-Config" -DDOCUMENT_SCANNER_BUILD_TESTS=ON -DOpenCV_DIR=C:/dev/react-native-document-scanner/.local/opencv-4.12.0/opencv/build/x64/vc16/lib -DZLIB_INCLUDE_DIR=C:/dev/react-native-document-scanner/.local/task09/zlib-build/include -DZLIB_LIBRARY=C:/dev/react-native-document-scanner/.local/task09/zlib-build/Release/zlibstatic.lib`, then builds Release. Existing ignored dependency caches are reused read-only; no machine-specific path entered production CMake. The new `video` dependency was found and linked. An initial test compile caught assignment to immutable quad storage; corrected to construction before successful builds.
- Final shared build passed. With the verified OpenCV DLL folder on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: **8/8 suites passed**, 2.45 seconds, including new motion/history contracts and all earlier suites.
- `npm run check`: both TypeScript checks and **16/16 JS contracts passed**.
- `adb devices` verified authorized `192.168.1.55:5555` online (Samsung SM-G998U, Android 15, arm64). The existing actual build uses RN 0.88.1, Nitro 0.37.1, AGP 9.2.1, Kotlin 2.2.10, compile/target/min SDK 37/36/24, NDK 27.1.12297006 and CMake 4.1.2.
- Initial device execution compiled/installed but found the old Java-only flow test lacked SoLoader/Nitro initialization for HybridData; added the existing test-runtime setup. A comparison then found the Java rectangle truncation difference described above; corrected the port rather than widening its tolerances. Existing evidence and transform coefficients now match the pre-migration oracle within 1e-8, with status/count/loss and timestamps exact.
- Recorded-motion device comparison passed for **4 traces, 1,375 flow-blur values, 1,375 gyro-blur values and 1,371 rotation intervals** (1e-12 arithmetic tolerance). Files/reports remain under ignored `.local/task11/` or the existing test application's external files; no private photo was copied or packaged.
- Final expanded device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.FlowMotionEstimatorTest,com.margelo.nitro.documentscanner.processing.FlowQuadMotionTest,com.margelo.nitro.documentscanner.processing.LiveQuadMotionTest,com.margelo.nitro.documentscanner.processing.GateMotionTest,com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest'`: final APK built and installed; **70/70 tests passed**, 53.782 seconds (36 motion/JNI tests plus 34 detector integration tests). This includes the corrected failure-injection fixture and the original expiry timing tolerance. Earlier failing runs were not counted as passes. Log: ignored `.local/task11/device-verified.log`.
- `npm run test:native -- -Test '*LiveQuadTrace*Test' -RecompilePlugins`: build passed, **10 JVM tests passed, 1 optional external full-replay test skipped**, zero failures/errors (XML verified). The initial comma-joined test filter selected no tests and was not counted; a stale Expo plugin output required the documented `-RecompilePlugins` workaround. JVM reports use the historical gyro oracle and are not shared-engine validation; the instrumented recorded-input comparison supplies that evidence.
- Final C++ format and diff whitespace checks passed. No automated shutter, new live camera recording, Mac/iOS run or performance measurement was performed. iOS integration/release validation remains deferred per user direction.
