# 20 — Implement shared iOS inference and photo analysis

[Back to task index](../TASKS.md)

Status: Completed — native inference and fixture validation passed
Complexity: High
Dependencies: [06](06-share-model-preprocessing.md), [17](17-add-ios-package-and-nitro-bindings.md), [19](19-implement-ios-source-store.md)

## Objective

Run the same corner model and photo-region contract on iOS.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerInference.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/CornerModelSize.kt
- android/src/main/java/com/margelo/nitro/documentscanner/processing/PhotoAnalysis.kt
- src/ScanSession.nitro.ts — session/page ownership
- src/DocumentScannerCamera.nitro.ts — quadTensorModel

## Current behavior and invariants

One serialized CPU interpreter serves live and photo callers; invalid replacement keeps the previous model; photo analysis performs one fresh inference on its selected region.

## Implementation steps

1. Add an iOS inference worker owning interpreter/model bytes/tensor workspace, with native leases and immutable model selection generation.
2. Use shared preprocessing and decoding; validate exact tensor count/types/shapes before installing a custom model and perform blank-input warmup.
3. Acquire source pixels from Task 19, apply shared seeded crop geometry, run inference once, and map results to full upright source coordinates.
4. Serialize replacement validation/publication on the inference owner, reset live generations on success, and preserve the old selection on failure.
5. Release buffers/runtime only after in-flight work and the last owner lease finish; never borrow CVPixelBuffer memory beyond completion.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Run model tensor parity ≤2e-5 and existing image fixture limits; compare resident-source and reloaded-source analysis.
- Test two owners, failed replacement, rectangular model dimensions, stale selection publication, warmup failure, and disposal.

## Validation commands

- npm run check
- Run focused iOS inference XCTest fixtures and shared core tests; report missing private fixtures explicitly.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Photo analysis and model ownership match Android contracts using the same model bytes and algorithms.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

feat(ios): implement shared corner inference and analysis


## Implementation report

Added `ios/CornerInference.swift` and `ios/ScannerCornerRuntime.h/.mm`.
The native service owns one serial CPU runtime/workspace shared by independently
closed owner leases. Model bytes are copied into immutable selections (128 MiB
limit), with dimensions and monotonically increasing generations. Replacement
validates exact tensor counts, float32 NHWC / [1,9] shapes and backing sizes,
allocates tensors, then performs blank-input invocation and shared output validation
before publication. Validation/warmup failure and outdated expected generations
preserve the installed selection. Frozen photo selections remain usable without
replacing the live selection. The last lease releases interpreter and workspace
after running work; the immutable selected bytes remain available for a later owner.

Photo analysis acquires Task 19 pixels before entering inference, applies shared
seeded crop geometry, runs exactly one fresh invocation, then maps the shared
decoded candidate into full upright normalized coordinates. Invalid seed geometry
falls back to the whole photo, matching Android. Scores pass through unchanged;
later live policy owns acceptance gating. iOS preprocesses the full leased region;
Android retains its platform half-resolution photo decode. Both call the same
portable resize, normalization and decoder. JPEG-backed reload is compared within
existing photo budgets rather than falsely requiring raw equality.

The live entry accepts packed RGBA/BGRA CVPixelBuffers with encoded crop and EXIF
orientation. Lock, borrow, inference, unlock and release all execute synchronously
on the inference worker, including error exits. Results contain immutable values
and a model generation, never a borrowed platform pointer. No camera is opened.

Added `ios/Tests/CornerInferenceTests.swift` and five tiny synthetic TFLite fixtures
under `ios/Tests/Fixtures`; only the CocoaPods test target copies those models.
The production pod exports the bridge header for Swift. Architecture ownership
notes are updated. Android runtime, portable algorithms, public signatures and
generated bindings are unchanged. Machine-specific validation infrastructure stays
outside this repository.

### Validation

- Verified environment: Xcode 26.6 (17F113), Swift 6.3.3, Apple Clang 21,
  iOS 18 deployment, Intel iOS 26.5 simulator, OpenCV 4.12.0 and
  TensorFlowLiteC 2.17.0. Reused the existing package host and verified its workspace,
  scheme and destination before building. A final SHA-256 comparison matched all
  seven relevant local/remote implementation, test and podspec files.
- Intel simulator `build-for-testing`: passed. Expanded build command:

```sh
export DEVELOPER_DIR="/Users/admin/Downloads/Xcode 2.app/Contents/Developer"
cd /Users/admin/Projects/react-native-document-scanner/.local/task17/host
xcodebuild -workspace ScannerPackageValidation.xcworkspace \
  -scheme ReactNativeDocumentScanner-Unit-ScaffoldTests -configuration Debug \
  -destination "platform=iOS Simulator,id=7C539F34-E6DA-4D55-AF59-98A50A35C2D7" \
  -derivedDataPath ../derived-data CODE_SIGNING_ALLOWED=NO build-for-testing
```

- The external standalone XCTest runner signs and runs the produced test bundle
  with the simulator SDK's `xctest` agent, using the existing framework paths.
  Executed with
  `SIMCTL_CHILD_DOCUMENT_SCANNER_INFERENCE_FIXTURES=/Users/admin/Projects/react-native-document-scanner/.local/task17/task20-fixtures bash ../test.sh`.
  All **38 tests passed**, zero failures/skips, 75.826 seconds: six new inference
  cases and the 32 existing permission/package/source tests. This includes both
  Task 19 post-review regressions, now compiled and executed successfully.
- Inference tests cover two owners, old frozen photo selection versus current live
  selection, failed/stale replacements, exact native tensor metadata rejection,
  rectangular 320x192 input, nonfinite blank-warmup failure, shared seed mapping,
  invalid-seed fallback/recovery, disposal during blocked inference, deferred source
  deletion, last-owner release and reopening. Synthetic CVPixelBuffer checks cover
  BGRA, crop/rotation, invalid geometry/orientation, failure after locking, recovery,
  and release of the caller's buffer after completion. No camera was used.
- All **13 private reference photos** were available and executed; none were skipped.
  Canonical model hash is verified by the existing package test. Prepared-tensor
  output maximum absolute error was **7.301568984985352e-7**, below **2e-5**.
  Upright PNG/original JPEG normalized corner error mean/worst:
  **0.00011279764008893632 / 0.0016596847627786248** (budgets 0.005 / 0.015).
  Published resident pixels versus JPEG-reloaded source mean/worst:
  **0.0003238199593110231 / 0.0013863194301238684**, within those same budgets.
  These are fixture correctness results, not device quality/performance measurements.
- Windows shared-core build via `cmd /c .local\task05\build-core.cmd` passed.
  With `.local/opencv-4.12.0/opencv/build/x64/vc16/bin` on PATH,
  `ctest --test-dir .local/build/core-tests -C Release --output-on-failure`
  passed all **10 suites**, including independent tensor goldens, pixel/stride/
  orientation and geometry contracts.
- `npm run check`: library/example typechecks and all **40 JS contracts passed**.
  C++/ObjC++ clang-format dry run and `git diff --check` passed. No spec changes
  required regeneration; generated bindings have no diff.
- Initial compile attempts found Int/UInt conversions and a Swift-imported method
  name mismatch, both corrected. npm pack's normalized timestamps also allowed
  Xcode to reuse stale modules during an intermediate attempt. The external
  packaging harness now refreshes extracted file timestamps. The final successful
  build recompiled the updated sources; hashes verify the tested snapshot.

Evidence remains ignored: `.local/task20-ios-build.log`, `task20-ios-xctest.log`,
`task20-pods.log`, `task20-source-sha256.json`, `task20-core-build.log`,
`task20-core-tests.log`, `task20-check.log` and `task20-pack.json`.

Limits: public session processing and camera guidance remain scaffolds for their
later tasks. No full React Native iOS sample, physical-device run, new arm64 device
build, sanitizer run or Apple Silicon simulator validation was performed here.
The previously deferred physical TIFF/JPEG comparison remains deferred. This task
establishes the internal native inference contract, not iOS release readiness.
Swift/C++/Nitro work used the implementation stage only; no optimization pass or
performance claim was made.
