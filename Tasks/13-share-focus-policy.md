# 13 — Share document and tap focus policy

[Back to task index](../TASKS.md)

Status: Completed

Review follow-up: scan completion now evaluates an already-overdue observed target loss immediately, while a shot still defers release until after capture. Added a fake-CameraX regression for late success with no further candidates, both with and without a shot. Focused validation: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.FocusControllerTest'` built/installed successfully and passed all 9 tests (0.061 seconds). The first sandboxed attempt failed during Gradle dependency-directory resolution before compilation; rerunning with filesystem escalation passed. No camera capture occurred.
Complexity: High
Dependencies: [12](12-share-live-guide-policy.md)

## Objective

Give focus decisions one portable owner while retaining platform hardware commands.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/FocusController.kt — FocusSnapshot, start, settle, unbind
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicy.kt
- android/src/test/java/com/margelo/nitro/documentscanner/processing/DocumentFocusPolicyTest.kt
- android/src/main/java/com/margelo/nitro/documentscanner/HybridDocumentScannerCameraView.kt — focus snapshot consumers

## Current behavior and invariants

Document focus runs independently of capture waiting; tap priority lasts five seconds; target loss releases after one second; focus generations change on start/settle/release.

## Implementation steps

1. Move document/tap ownership, retries/deadlines, learned-position lifecycle, and snapshot generation into portable policy state, following Task 02's timing convention: tap priority, retry, and release deadlines are returned to the adapter, which owns their wakeups.
2. Leave CameraX FocusMeteringAction, Samsung-specific retrigger eligibility, diopter parking, and callback interpretation in Kotlin.
3. Make platform scan completion an event with generation and known/unknown success; do not require Apple to fabricate an Android success flag.
4. Remove mirrored ownership fields from the adapter after confirming every hardware callback maps to the policy owner.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Cover tap takeover/expiry, document reacquisition, failed/unknown scans, size/sharpness changes, capture hold, stale completion, and unbind.
- Preserve Android's 30 cm initial parking and learned diopters; reserve iOS learned lens positions for its adapter.

## Validation commands

- Run shared build and ctest, including migrated document/tap focus assertions.
- npm run check

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Android focus actions/snapshots derive from shared policy without changing device behavior.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

refactor: share scanner focus ownership and policy



## Implementation report

- Added `cpp/include/document_scanner/Focus.hpp` and `cpp/src/Focus.cpp`. One portable `focus::Policy` owns document/tap focus, current scan token, known/unknown completion, held priority, pending/settled state and monotonically rising snapshot generations. Its document policy owns seeking/scanning/locked phases, trusted scale reference/votes, retry cooldown, sustained sharpness failure and observed-target loss. Preserved thresholds: minimum area 0.03, two trusted scale votes beyond 1.08, 1 s cooldown/loss hold, 500 ms failed-focus hold, 5 s tap priority and 2 s document-scan timeout.
- All policy time is caller-supplied signed 64-bit monotonic uptime nanoseconds (`SystemClock.uptimeMillis() * 1_000_000` on Android). Absolute tap, scan, retry and release deadlines return to the adapter. The core reads no clock and owns no timer, camera, callback or pixel storage. Adapter wakeups can release an observed missing target at its deadline without awaiting another frame; retry/sharpness/scale scans still require fresh candidates, so timers do not replay old geometry or add scale votes. Capture holds defer lens release until after capture; a waiting capture does not block focus.
- Added `android/src/main/cpp/NativeFocus.hpp/.cpp`, `processing/NativeFocus.kt`, Android/C++ CMake wiring, JNI registration and keep rules. Quads are synchronous value snapshots; generations, tokens and deadlines cross as Long values/arrays without double conversion. A completion has its stable scan token and success/failure/unknown. Unknown is settled without proving a lock; it is distinct from a scan still pending. Android continues interpreting CameraX results/exceptions as known success/failure and need not impose those flags on another platform.
- `FocusController.kt` now adapts CameraX actions/futures, current camera/request resources, latest hardware trigger attempt, main-thread publication and revision-checked wakeups. Removed its mirrored owner, lock, held-until and generation state; immutable published `FocusSnapshot` values derive from C++. Options still see ownership changes before hardware/parking changes. The 700 ms Samsung retrigger workaround and its hardware eligibility stay Kotlin-owned; stale camera/future/attempt callbacks cannot settle a newer scan. Unbind invalidates the core token and increments generation; view disposal closes HybridData and invalidates future/timer access.
- `focus::LearnedPosition` is synchronized portable value storage with adapter-selected units/lifetime. Android selects a process-wide diopter store, updates it only after existing metadata-generation checks and preserves it across unbind/remount. Removed the camera view's mirrored process field. Android capability checks, 30 cm initial parking, finite fallback/range clamping and Camera2 diopter commands remain Kotlin-owned; the core has no hardware-unit default. A future iOS adapter can retain its own lens-position store.
- Removed the former production Kotlin document state machine. `processing/DocumentFocusPolicy.kt` retains Android hardware helpers; a tiny test-only JNI adapter in `src/testSupport/.../NativeDocumentFocusPolicy.kt` lets migrated assertions exercise the same native owner. A former Kotlin policy remains an instrumentation-only reference oracle. No public/Nitro spec or generated source changed; no later torch/capture policy migration or model/threshold redesign was included.
- Added `cpp/tests/FocusTest.cpp` / `focus_contracts` for all migrated pure document-policy assertions plus tap takeover/expiry, unknown/failed scans, retries/deadlines, sharpness/scale/provisional cases, capture hold, stale completions, reset/unbind, integer timestamps beyond 2^53 and learned-position lifetime. Moved all 12 original `DocumentFocusPolicyTest` assertions from JVM to JNI instrumentation (including the three Android capability/parking tests); removed JVM tests are not reported as passes. Added a 500-event deterministic exact comparison against the former Kotlin policy, known/unknown JNI completion/deadline precision and learned-store tests.
- Added eight `FocusControllerTest` cases using fake CameraX controls/futures, explicit time and main-thread execution: tap hold/expiry, takeover, unbind/disposal, latest-trigger completion, document timeout during a shot, missing-target deadline/reacquisition, scale/failure retries requiring fresh candidates, capture blocking and invalid metering targets. No camera opens or photo capture occur. Updated architecture/development/core guidance. C++ and Kotlin implementation stages only; no optimization/cleanup or performance claim.

### Validation

- Started clean on `codex/ios-scanner-parity` at `9ae0628`. Kotlin LSP returned SERVER_UNAVAILABLE; used focused source/consumer inspection and actual native compilation. Verified the installed CameraX 1.5.3 AAR signatures with the local Android JDK before building the fake control tests.
- Reused verified MSVC 19.51.36256 / VS toolset 14.51.36231, C++20, Windows x64, OpenCV 4.12.0 and zlib 1.3.1. Expanded build: `cmd /c .local\task10\build-core.cmd`, selecting VS and configuring `cmake -S cpp -B .local/build/core-tests -G "Ninja Multi-Config" -DDOCUMENT_SCANNER_BUILD_TESTS=ON -DOpenCV_DIR=C:/dev/react-native-document-scanner/.local/opencv-4.12.0/opencv/build/x64/vc16/lib -DZLIB_INCLUDE_DIR=C:/dev/react-native-document-scanner/.local/task09/zlib-build/include -DZLIB_LIBRARY=C:/dev/react-native-document-scanner/.local/task09/zlib-build/Release/zlibstatic.lib`, then building Release. Shared build passed. With the verified OpenCV DLL folder on PATH, `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`: **10/10 suites passed**, 7.56 seconds, including focus contracts and earlier suites.
- `npm run check`: both TypeScript checks and **16/16 JS contracts passed**. C++ formatting and diff whitespace checks passed.
- Verified authorized `192.168.1.55:5555` online with `C:/Users/User/AppData/Local/Android/Sdk/platform-tools/adb.exe devices` (Samsung SM-G998U, arm64). Existing RN 0.88.1 / Nitro 0.37.1 / AGP 9.2.1 setup unchanged. Actual build reports Kotlin 2.2.10, compile/target/min SDK 37/36/24, NDK 27.1.12297006 and CMake 4.1.2.
- Initial Android compilation found Guava `Futures`/`SettableFuture` helpers were not on the test classpath. The test harness now implements the existing `ListenableFuture` interface over Java `CompletableFuture`; no dependency or product workaround was added. Subsequent native APK compilation/installation succeeded and all 23 focus tests passed.
- Broader detector runs hit the existing `heldGuidanceExpiresWithoutAnotherFrame` timing bound (400–600 ms) while the phone reported Dozing: about 737–760 ms, also on an isolated rerun. Waking before installation let it doze again during installation. The unchanged isolated check passed immediately when run after a wake against the already-installed APK: `adb -s 192.168.1.55:5555 shell input keyevent KEYCODE_WAKEUP`, then `adb -s 192.168.1.55:5555 shell am instrument -w -e class com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest#heldGuidanceExpiresWithoutAnotherFrame com.margelo.nitro.documentscanner.test/androidx.test.runner.AndroidJUnitRunner` (**1/1 passed**, 0.832 seconds). No detector code or timing tolerance was modified.
- Full device validation before the review follow-up: `npm run test:device -- -Device 192.168.1.55:5555 -Class 'com.margelo.nitro.documentscanner.FocusControllerTest,com.margelo.nitro.documentscanner.processing.DocumentFocusPolicyTest,com.margelo.nitro.documentscanner.processing.LiveDocumentDetectorTest'`: final APK built/installed; **57/57 tests passed**, 31.041 seconds (8 fake-CameraX lifecycle, 15 JNI document/learned-position tests including 500-event parity, 34 detector integration tests). A bounded PowerShell background job sent `adb -s 192.168.1.55:5555 shell input keyevent KEYCODE_WAKEUP` every 3 seconds during build/install/execution and was stopped/removed in `finally`; no persistent power setting changed. The original expiry bound passed. Final log: ignored `.local/task13/device-awake-final.log`. Earlier failing runs were not counted as passes.
- No automated shutter, new live lens/camera recording, private fixture transfer, Mac/iOS execution or performance measurement. Hardware-focused CameraX behavior is compiled and exercised with fake futures; actual lens behavior and later iOS integration are not claimed as physically validated.
