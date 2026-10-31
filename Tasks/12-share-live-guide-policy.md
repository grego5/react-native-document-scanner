# 12 — Share live guide and adaptive-inference policy

[Back to task index](../TASKS.md)

Status: Completed
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [11](11-share-flow-and-motion.md)

## Objective

Use one state machine for guide acceptance, tracked retention, and inference pacing.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveQuadStabilizer.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDetectionState.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/AdaptiveDetectionPolicy.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDocumentDetector.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/LiveQuadTraceReplayTest.kt

## Current behavior and invariants

Valid model results meeting the configured score threshold publish immediately; low scores clear live acceptance and pause inference until measured movement (or invalidation). This indirectly gates shutter readiness through the valid-document requirement, as clarified by the user. Reliable flow carries the outline; model inference remains independent of autofocus.

## Implementation steps

1. Move guide validity/acceptance, held expiry, tracking retention, and adaptive wake decisions into the core.
2. Keep Android analysis scheduling, overlays, callback dispatch, and model-runtime calls in its adapter.
3. Preserve monotonically rising acceptance IDs across resets and invalidate old binding/model completions.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Replay acceptance, held expiry, invalid/missing detections, tracking loss, region loss, and shape changes.
- Assert ordinary movement alone does not wake adaptive inference and focus transitions do not reset confirmation.

## Validation commands

- Run shared build and ctest, including migrated guide/adaptive/replay assertions.
- npm run test:device -- -Device <verified-device-id> -Class 'com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest'

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android delegates guide decisions to C++ and replay/state tests preserve visible behavior.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share live guide and adaptive detection policies



## Implementation report

- Added portable `cpp/include/document_scanner/LiveGuide.hpp` and `cpp/src/LiveGuide.cpp`: normalized convex/area validity, immediate model acceptance, held expiry, same-frame reliable-flow retention, duplicate/backward timestamps and acceptance IDs surviving resets/config changes. The core owns adaptive pause/revalidation, the last model-confirmed reference outline, scene-change checks, configured score rejection and movement wake from a low-score pause. Caller-supplied times only; no platform handles, pixels or internal clock reads.
- Added `android/src/main/cpp/NativeLiveGuide.hpp/.cpp`, `processing/NativeLiveGuide.kt`, CMake/JNI registration and keep rules. `LiveQuadStabilizer.kt` and `AdaptiveDetectionPolicy.kt` adapt one shared lazy analysis-executor HybridData owner in `LiveDocumentDetector`. Disposal drains queued analysis/session/flow work before closing; repeated close is harmless and use after close is rejected, including close before first use. Quads cross as value arrays; IDs/deadlines use LongArray metadata to retain precision beyond 2^53.
- Android retains CameraX/model resources and calls, synchronized scheduling (`LiveDetectionState`), binding/model generation invalidation, score-pause generation tokens, overlays/main-thread expiry and callback publication. Existing stale-completion guards, public/Nitro contracts and generated bindings remain unchanged. No later focus, torch or capture policy migration.
- Preserved score gating as clarified by the user: below the configured threshold (default 0.95), live acceptance clears and inference pauses until measured movement or invalidation. Shutter readiness is transitively gated by the valid-document requirement. Missing scores and a disabled threshold preserve existing behavior; raw predictions/scores remain available to samples/traces.
- Preserved the existing scene-change threshold: accumulated corner displacement greater than 5% of frame width, including large translation/rotation/scale/perspective changes. Small ordinary movement does not wake inference; the original test explicitly expects sufficiently large translation to wake it. Older shape-only/movement-never-wakes documentation conflicted with source/tests; thresholds were not redesigned. Focus transitions are not guide/adaptive policy inputs.
- Added `cpp/tests/LiveGuideTest.cpp` / `live_guide_contracts`: migrated guide/adaptive assertions, tracked-heartbeat replay, expiry/reacquisition, missing/invalid/tiny/perspective candidates, tracking loss, region degradation, scene/reference changes, reset IDs, score rejection and pause wake thresholds. Moved all original `LiveQuadStabilizerTest`, `AdaptiveDetectionPolicyTest` and `LiveQuadTraceReplayTest` assertions from JVM to JNI instrumentation; removed JVM tests are not counted as passes. Added a 240-frame exact comparison against a test-only former Kotlin stabilizer, covering resets/config changes, retention, invalid/missing inputs and timestamp discontinuities; native reference/lifetime/large-timestamp assertions also pass.
- Moved `LiveQuadTraceReplay.kt` into testSupport: native guide replay runs on Android; format/report checks remain JVM-compatible without loading native code. Moved synthetic TSV from JVM-only resources to shared `tests/fixtures/live-quad-trace/synthetic.tsv`. Optional recorded-guide replay uses instrumentation argument `liveQuadTrace=<device-file-or-directory>` and writes `live-quad-trace-reports/summary.txt` in the test app external files directory. Historical gyro reporting remains an explicitly named test-only oracle. Updated architecture/development/core guidance.
- C++ and Kotlin implementation stages only. Task 11 changes were already present and were committed separately as `0817a94` before this task commit; prior history was not rewritten.

### Validation

- Reused verified MSVC 19.51.36256 / VS toolset 14.51.36231, C++20, Windows x64, OpenCV 4.12.0 and zlib 1.3.1. Expanded build: `cmd /c .local\task10\build-core.cmd`, selecting VS and configuring `cmake -S cpp -B .local/build/core-tests -G "Ninja Multi-Config" -DDOCUMENT_SCANNER_BUILD_TESTS=ON -DOpenCV_DIR=C:/dev/react-native-document-scanner/.local/opencv-4.12.0/opencv/build/x64/vc16/lib -DZLIB_INCLUDE_DIR=C:/dev/react-native-document-scanner/.local/task09/zlib-build/include -DZLIB_LIBRARY=C:/dev/react-native-document-scanner/.local/task09/zlib-build/Release/zlibstatic.lib`, then building Release. Final build passed. With the verified OpenCV DLL folder on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: **9/9 suites passed**, 2.63 seconds.
- Verified authorized `192.168.1.55:5555` online using `C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe devices` (Samsung SM-G998U, arm64). Existing RN 0.88.1 / Nitro 0.37.1 / AGP 9.2.1 setup unchanged. Actual build reports Kotlin 2.2.10, compile/target/min SDK 37/36/24, NDK 27.1.12297006 and CMake 4.1.2. Kotlin LSP returned SERVER_UNAVAILABLE; focused source inspection and real builds supplied validation.
- Expanded device command: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest,com.margelo.nitro.documentscanner.processing.LiveQuadStabilizerTest,com.margelo.nitro.documentscanner.processing.AdaptiveDetectionPolicyTest,com.margelo.nitro.documentscanner.processing.LiveQuadTraceReplayTest'`: final APK built/installed; **OK (54 tests)**, 42.399 seconds: **53 passed, 1 optional external recorded-trace replay skipped** (no current-format device trace path supplied). Includes 34 detector tests, exact 240-frame parity, score pause/movement/threshold behavior, expiry, stale completions and focus independence. Log: ignored `.local/task12/device-final.log`.
- Initial instrumentation found the synthetic fixture was still JVM-only; moved it to the shared fixture root and reran successfully. Earlier failing runs were not counted as passes.
- `npm run test:native -- -Test '*Live*Test'`: build successful, **9/9 JVM tests passed** (4 generation/scheduling tests, 5 trace format/report tests); XML verified zero skips/failures/errors. Log: ignored `.local/task12/jvm.log`. Gradle automatically discarded a corrupt file-hash cache and completed; no product workaround was added.
- `npm run check`: both TypeScript checks and **16/16 JS contracts passed**. Final C++ formatting and `git diff --check` passed.
- No automated shutter, new camera recording, private-photo transfer, Mac/iOS run or performance measurement. Pixel-free replay cannot regenerate tracks; external full-recording validation was not run without a current-format input. iOS release validation remains deferred per user direction.
