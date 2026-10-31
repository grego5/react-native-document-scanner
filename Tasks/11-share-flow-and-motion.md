# 11 — Share optical flow and motion evidence

[Back to task index](../TASKS.md)

Status: Planned
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

