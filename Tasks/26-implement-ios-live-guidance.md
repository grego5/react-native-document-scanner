# 26 — Connect iOS frame analysis to shared live guidance

[Back to task index](../TASKS.md)

Status: Implemented — portable and Intel simulator checks passed; physical-device preview/guide check pending
Complexity: High
Dependencies: [11](11-share-flow-and-motion.md), [12](12-share-live-guide-policy.md), [20](20-implement-ios-inference.md), [25](25-implement-ios-camera-session.md)

## Objective

Run iOS native detection/tracking and publish the same guide states.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDocumentDetector.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LiveDetectionState.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/LatestFrameCapture.kt
- src/DocumentScannerCamera.nitro.ts — guide, adaptiveDetection, document callbacks

## Current behavior and invariants

Live pixels stay native; guidance never captures; valid inference publishes immediately; flow retains the guide while adaptive inference pauses.

## Implementation steps

1. Process owned/retained CVPixelBuffer frames on a serial analysis context using shared transforms, preprocessing, flow, and guide policy.
2. Carry frame sequence/timestamp, geometry, model/session generation, and truthful available metadata into immutable native snapshots.
3. Keep only bounded latest-frame and shutter-thumbnail state; do not retain every analysis buffer.
4. Preload/warm the shared interpreter during session start; implement guide enable/disable without rebinding, adaptive pacing, overlay easing/color, and current-state callbacks. Guide acceptance and flow evidence come from shared C++; adaptive pacing is a Swift port of Android's `AdaptiveDetectionPolicy` and must pass `tests/fixtures/parity/adaptive.tsv`.
5. Suppress stale model/binding completions and preserve guide IDs across resets.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Replay portable guide fixtures through the iOS adapter; test padded BGRA, orientation, square ROI, guide toggles, model changes, and tracking loss.
- Use physical scenes for motion/occlusion/recovery; do not claim pixel-free replay proves optical tracking quality.

## Validation commands

- Run shared guide/flow tests and focused iOS frame-adapter tests.
- Perform physical-device preview/guide checks without automated shutter actions.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

iOS shows matching guide-state behavior and adaptive tracking with bounded native frame ownership.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): connect shared live detection and tracking

## Implementation report — 2026-11-05

Changed files:

- `cpp/src/FlowEstimator.cpp`, `cpp/tests/MotionTest.cpp`: the flow estimator accepts BGRA8 leases with the same luma weights; a new contract checks that red/blue-swapped BGRA measures exactly what RGBA does (rotated, tinted frames).
- `ios/ScannerLiveTracking.h/.mm` (new, public header): ObjC++ adapters for the shared guide stabilizer, score rules, flow estimator and its transform history. The flow adapter locks the CVPixelBuffer read-only and unlocks before returning.
- `ios/AdaptiveDetectionPolicy.swift` (new): Swift port of Android's pacing policy.
- `ios/LiveDocumentDetector.swift` (new): the detector, its generation/pacing state, immutable `LiveGuideResult` snapshots (status, quad, framing, acceptance ID, deadline, detector and model generations, frame sequence, sensor time, score), main delivery with expiry, and a single-slot framed-region frame request.
- `ios/LiveGuideOverlay.swift` (new): the guide overlay, with easing, colour, held dimming and searching brackets.
- `ios/QuadModelLoader.swift` (new): prepares `quadTensorModel` (absolute path, file URL or bundle resource); the newest request wins.
- `ios/CameraSession.swift`: a `frameSink` receives each armed-run buffer and its nanosecond presentation time on the frame queue.
- `ios/CornerInference.swift`: `Lease.prepare()` opens and warms the interpreter ahead of the first frame.
- `ios/CameraPreviewView.swift`, `ios/HybridDocumentScannerCameraView.swift`: the overlay is hosted over the preview. The camera waits for the model; a model failure reports `onError` and keeps the camera suspended. Wired props: `guide` (toggles without restarting the camera), `adaptiveDetection`, `minCornerScore`, `guideColor` and `guideTransitionDuration`. The document state is reported from the results.
- `ReactNativeDocumentScanner.podspec`: public tracking header; the `adaptive.tsv` test resource.
- Tests: `ios/Tests/LiveGuidanceTests.swift` (new, 15 tests); `ios/Tests/CameraSessionTests.swift` harness (model seam, frame sink, a started camera reports `searching`).
- Docs: `ios/README.md` (Live guidance), `.agents/skills/docs/references/architecture.md`, `cpp/README.md`, `tests/fixtures/README.md`.

Checks:

- Portable core, MSVC (VS 18 Build Tools vcvars64) with the existing `.local/build/core-tests` configuration (OpenCV 4.12.0): `cmake --build .local/build/core-tests --config Release`, then `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`. **9/9 suites passed**, including the new BGRA flow contract.
- iOS (vmac, project `docscanner`, Intel simulator, Xcode 26.3), selecting `LiveGuidanceTests`, `CameraSessionTests`, `CornerInferenceTests` and `ScaffoldTests`. GitHub Actions run 42016047435: **42 tests, 0 failures, 1 expected skip** (the optional private inference fixture). Per class: LiveGuidanceTests 15/15, CameraSessionTests 16/16, CornerInferenceTests 6 (1 skipped), ScaffoldTests 5/5.
- Earlier runs 42009091805, 42010496742 and 42011584414 failed to compile: the header was not public, an implicit `self` appeared in a nested closure, and `Point` was ambiguous with MacTypes. Run 42013017794 failed one test, because its test buffer had no row padding; the test was fixed to pad rows explicitly. None of these runs counts as a pass.
- `npm run check`: 41 JS contracts passed. `git diff --check` is clean.

Test coverage:

- Adaptive pacing replays the shared `adaptive.tsv` exactly.
- Padded BGRA reaches inference with the framing's EXIF orientation and the square crop, at 90 and 0 degrees.
- Guide toggles run no inference or publication, and acceptance IDs stay monotonic.
- A stale generation is never delivered, and a stopped detector analyzes nothing.
- A replaced model publishes `searching` with its new generation, then reacquires.
- A low score clears the guide and pauses inference on a still scene; a threshold change resumes it.
- A missing page clears the guide; hold expiry on main returns to `searching`.
- A runtime failure reports `unavailable` until the next start.
- With `adaptiveDetection`, reliable flow keeps inference paused while it carries and refreshes the guide (score nil), and losing texture wakes inference.
- The frame request copies only the framed region, unpadded, once.
- Easing and snapping, overlay mapping and held dimming are covered.
- The camera waits for the model, a model failure keeps it suspended, guide results drive the document state, and the toggle keeps the camera running.

Not run:

- Physical-device preview and guide checks: no device is available. Motion, occlusion, recovery and optical tracking quality on real scenes are unverified. The synthetic flow tests use static or blank pixels and prove only the paths.
- Real AVFoundation frame delivery to the detector: the simulator has no camera.

Limitations:

- Analysis runs on the camera's frame queue, so a stop waits for any inference in flight while it drains.
- No gyroscope fallback, frame-state history, focus candidates or stats strip yet. Capture gating and controls belong to later tasks; `showStats` is ignored.
- Android's provisional `DETECTED` state is not ported: the shared stabilizer never emits `acquiring`, so it is unreachable on both platforms today.
- The frame request has no consumer until still capture lands.

## Review follow-up — 2026-11-05

Three review findings were confirmed in source and fixed:

- Guide orientation: the overlay mapped detection quads straight into the capture framing's presentation rectangle, while the preview layer is placed with the preview rotation angle, and AVFoundation lets the two angles differ. `LiveGuideOverlay` now maps each corner back to normalized buffer coordinates through the detection framing, then into `previewFraming`, which `CameraPreviewView` sets whenever it places the preview layer. A change of preview framing clears the drawn guide.
- Superseded model activation: `QuadModelLoader` checked its request before reading the file and then published unconditionally. `CornerInference.replace` now takes an `admit` closure that runs on the inference queue with the validated selection immediately before it is published. The loader checks request ownership and records the active source under its own lock there, and a refused admission throws `staleSelection`, which the loader reports as `model/superseded`.
- Lease acquisition: `currentLease()` held the detector lock while `CornerInference.acquire()` waited on the shared inference queue, so a main-thread settings update or stop could stall behind other inference. A stop between the preload's generation check and acquisition could also store a new lease after the previous one was released. Acquisition now happens outside the lock, and the lease is kept only while the detector runs (between `begin` and `stop`) and no other caller kept one first. Otherwise it is closed, and the frame that raced the stop returns.

Tests added to `LiveGuidanceTests`:

- The overlay maps through the buffer when the preview and capture angles differ, and clears on a preview framing change.
- Settings and stop return while another owner holds the inference queue, and the late preload lease is closed, so the interpreter is dropped.
- A model request superseded during validation is never selected, and the newest request reports its own failure.

Checks: iOS run 42026805886: 45 tests, 0 failures, 1 expected skip (LiveGuidanceTests 18/18, CameraSessionTests 16/16, CornerInferenceTests 6 with 1 skipped, ScaffoldTests 5/5). `npm run check`: 41 passed. `git diff --check` is clean. The portable core was not rerun because no C++ changed.
