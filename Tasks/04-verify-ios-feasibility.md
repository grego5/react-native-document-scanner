# 04 — Verify iOS toolchain feasibility and source-format costs

[Back to task index](../TASKS.md)

Status: Planned
Complexity: High
Dependencies: [02](02-introduce-portable-core.md), [03](03-share-model-resources.md)

## Objective

Establish, before Android algorithms migrate, that the portable core builds and runs on iOS, and measure source-format costs before Tasks 19 and 28 commit to one.

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

Android pins OpenCV 4.12.0 and LiteRT 1.4.2. Photo tensor error is bounded by 2e-5; photo corner fixture limits are mean .005 and worst .015. TASKS.md prefers uncompressed BGRA pixels with TIFF backing when JPEG quality is omitted; that preference is not yet measured.

## Implementation steps

1. Establish the Mac host, Xcode version, SDK, and a throwaway test-host project; record them.
2. Integrate an OpenCV 4.12.0 iOS build (the official framework, or a verified alternative) and record which device and simulator slices actually link with the core. Do the same for the chosen TFLite runtime and version.
3. Build the core for device and simulator, run its tests there, and run the bundled model on Task 01 fixtures through the shared preprocessing; compare tensors and corners with Android's limits.
4. On a physical device, with existing photos decoded to BGRA at the capped capture size (no camera capture), measure for TIFF (uncompressed and each supported compression) and JPEG at Android's default qualities: file size, encode and decode time, peak resident memory, and repeat-edit cost from file versus from retained pixels.
5. Record a source-format recommendation for Tasks 19 and 28: TIFF backing, JPEG backing with retained native pixels, or another measured option, with the evidence. A result that contradicts the TASKS.md preference returns to architectural review before those tasks start.

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

Verified iOS OpenCV and TFLite integration, core and model results on iOS, and a recorded source-format decision exist under .local/ with their environment.

Required checks must pass before Status changes to Completed. A prerequisite may be prepared with unavailable validation explicitly recorded, but downstream release readiness must remain incomplete until required evidence exists. Record changed files, executed checks, actual results, and material limitations.

## Proposed commit title

test: verify iOS core feasibility and source-format costs
