# 04 — Verify iOS toolchain feasibility and source-format costs

[Back to task index](../TASKS.md)

Status: Completed — approved feasibility scope verified; physical-device validation deferred; Intel simulator supported
Complexity: High
Dependencies: [02](02-introduce-portable-core.md), [03](03-share-model-resources.md)

## Objective

Establish, before Android algorithms migrate, that the portable core builds and runs on iOS. Per user direction (2026-10-31), defer source-format measurements until a working sample exists on a real device; use provisional JPEG backing to keep that sample low friction.

## Non-goals

Do not implement later task outcomes, redesign unrelated Android behavior, change the model/thresholds, or perform an optimization/cleanup pass. Do not hand-edit generated sources. Missing environment facts require verification, not guessed substitutions. This is a spike: no Nitro registrations, camera session, or published library changes; keep its harness out of the shipped package.

## Read before editing

This is a lookup map. Locate symbols and read only the ranges needed for the next implementation decision. Prefer a configured matching LSP project; use focused rg when indexing is unavailable.

- cpp/CMakeLists.txt — core target from Task 02
- android/build.gradle — pinned OpenCV and LiteRT versions
- android/src/main/java/com/margelo/nitro/documentscanner/processing/DocCornerSession.kt — model I/O checks
- tools/models/README.md — private fixture preparation
- OpenCV 4.12.0 release assets — official iOS framework

## Current behavior and invariants

Android pins OpenCV 4.12.0 and LiteRT 1.4.2. Photo tensor error is bounded by 2e-5; photo corner fixture limits are mean .005 and worst .015. The original unmeasured TIFF/BGRA preference is superseded by the user-approved provisional JPEG default. Omitted quality follows existing capture-mode defaults (quality 1.0, latency 0.95); explicit quality remains honored.

## Implementation steps

1. Establish the Mac host, Xcode version, SDK, and a throwaway test-host project; record them.
2. Integrate an OpenCV 4.12.0 iOS build (the official framework, or a verified alternative) and record which device and simulator slices actually link with the core. Do the same for the chosen TFLite runtime and version.
3. Build the core for device and simulator, run its tests there, and run the bundled model on Task 01 fixtures through the shared preprocessing; compare tensors and corners with Android's limits.
4. Deferred by user until a working sample exists on a physical device: with existing photos decoded to BGRA at the capped capture size (no camera capture), measure for TIFF (uncompressed and each supported compression) and JPEG at Android's default qualities: file size, encode and decode time, peak resident memory, and repeat-edit cost from file versus from retained pixels.
5. Use provisional JPEG backing for the initial Tasks 19/28 sample, keeping the existing pixel leases/cache and memory bound. After the deferred measurements, record a measured recommendation and review any format change before adoption. Do not describe the provisional choice as a measured performance result.

## Ownership and boundary rules

Keep platform handles out of portable C++ interfaces; borrow pixels only through their owner's lease. Keep UIKit on main and camera/runtime/processing work on its declared serial owner. Cross-owner results carry immutable snapshots/generations. Existing public quads remain normalized upright TL/TR/BR/BL; framing crops and model resizing are separate transforms. Capture resources and Promises settle once and stale completion must not publish.

## Tests and expected results

- Core tests pass on simulator and device builds, or each failing slice is recorded with its cause.
- Model inference on iOS meets the existing tensor and corner limits, or the drift is attributed to preprocessing or runtime.
- Format measurements record device, OS, image dimensions, method, and repetition count.

## Validation commands

- Run shared CMake build and ctest on the development host.
- On the verified Mac, build and run the core test target for a verified simulator and device destination; record exact commands.

Verification prerequisites: establish the exact compiler/OpenCV setup before shared builds and the exact Mac workspace/scheme/destination before Xcode commands. Replace command placeholders only with verified values and record the expanded command. Report each unavailable environment or private fixture as not run with its reason; never report missing tests or compilation as passed. When moving a pure algorithm to C++, move its assertions in the same task so removed JVM tests do not become a false validation signal. Native camera captures always require a human tap.

## Completion criteria

Verified iOS OpenCV and TFLite integration, core and model results on iOS, and a recorded provisional source-format decision exist with their environment. Machine-specific evidence and harnesses are retained outside this repository, per user direction. Physical-device measurements and any unavailable physical test run stay explicitly deferred until the working real-device sample. Shared-preprocessing parity must be checked when Task 06 supplies that implementation; prepared-input runtime parity cannot substitute for it.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test: verify iOS core feasibility and source-format costs

## Feasibility report — 2026-10-31

User-approved scope update: defer physical-device format measurements until a working sample exists on a real device; use provisional native JPEG capture/backing meanwhile. Explicit quality remains honored, with existing capture-mode defaults when omitted (quality 1.0, latency 0.95). Tasks 19/28 and TASKS.md reflect this choice; it is not a measured performance recommendation.

Validation environment: Intel x86_64, macOS 26.7, Xcode 26.6, Apple Clang 21.0.0, iOS/simulator SDK 26.5. Machine-specific configuration, execution scripts and raw infrastructure evidence are maintained outside this repository.

Results:

- Development-host core build and CTest passed (1/1).
- Official OpenCV 4.12.0 iOS archive checksum verified. TensorFlowLiteC 2.17.0 CPU runtime resolved from the official CocoaPods specification; this differs from Android LiteRT 1.4.2 and was checked against fixtures.
- x86_64 iOS simulator core and runtime builds/links passed. All core objects were force-linked. Existing pixel contract tests passed through documented standalone simulator execution. Normal bootstrap launch failed with LaunchdSimError 111; no UIKit sample-app readiness is claimed.
- All 13 prepared reference tensor fixtures passed runtime parity. Maximum output drift: `2.981341552521499e-7`; largest photo mean corner drift: `1.8034698005117905e-7`; worst corner drift: `3.0411670280517396e-7`. Comparison uses existing fixture TFLite outputs. No iOS preprocessing parity result is claimed: shared preprocessing arrives in Task 06 and must validate the 2e-5 tensor budget and end-to-end corners there.
- Generic arm64 iOS device build/link passed for core, tests and runtime probe; no physical run (no connected device).
- Arm64 simulator core link failed because the official OpenCV arm64 slice was built for iOS devices. Exact linker cause and log are recorded. Task 17 needs a verified alternative/source-built simulator slice before claiming Apple Silicon simulator support.

The diagnostic harness, environment, expanded commands, dependencies/checksums, build logs and parity report are retained outside this repository. No published library, model bytes, thresholds or generated bindings changed. Task 03 remains committed as `2d17e0f`.

Outstanding/deferred: physical-device runtime and format-cost measurements after the working sample; preprocessing parity in Task 06; arm64 simulator integration in Task 17. These are not passed and downstream iOS release readiness remains incomplete.

The same portable assertions also passed as three XCTest cases. Routine typechecks and all 16 JS contracts passed; the Windows core CTest passed 1/1.
